package com.electronics.assembly.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.common.Dict;
import com.electronics.assembly.dto.ShortageLine;
import com.electronics.assembly.entity.BomItem;
import com.electronics.assembly.entity.OrderLineConfig;
import com.electronics.assembly.entity.SmtStep;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.repository.BomItemRepository;
import com.electronics.assembly.repository.OrderLineConfigRepository;
import com.electronics.assembly.repository.SmtStepRepository;
import com.electronics.assembly.repository.WorkOrderRepository;

/**
 * 生产工单业务。
 *
 * <p>核心规则：① 机型必须有 BOM，否则算不出齐套；② 投产前必须通过齐套校验
 * （缺料行为 0，且齐套率不低于 {@link Dict#MIN_KIT_RATE}）；③ 状态只能逐级推进；
 * ④ 投产时自动铺开 SMT 五道工序，并登记初始产线配置作为换线比对基准。</p>
 */
@Service
public class WorkOrderService {

    private final WorkOrderRepository orderRepository;
    private final BomItemRepository bomRepository;
    private final SmtStepRepository stepRepository;
    private final OrderLineConfigRepository configRepository;
    private final RepairTaskService repairTaskService;
    private final ProductionBatchService batchService;

    public WorkOrderService(WorkOrderRepository orderRepository,
                            BomItemRepository bomRepository,
                            SmtStepRepository stepRepository,
                            OrderLineConfigRepository configRepository,
                            RepairTaskService repairTaskService,
                            @Lazy ProductionBatchService batchService) {
        this.orderRepository = orderRepository;
        this.bomRepository = bomRepository;
        this.stepRepository = stepRepository;
        this.configRepository = configRepository;
        this.repairTaskService = repairTaskService;
        this.batchService = batchService;
    }

    @Transactional(readOnly = true)
    public List<WorkOrder> list(String status, String productModel) {
        List<WorkOrder> result;
        if (status != null && !status.isBlank()) {
            result = orderRepository.findByStatus(status);
        } else if (productModel != null && !productModel.isBlank()) {
            result = orderRepository.findByProductModel(productModel);
        } else {
            result = orderRepository.findAll();
        }
        return batchService.enrichAll(result);
    }

    @Transactional(readOnly = true)
    public WorkOrder get(Long id) {
        WorkOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new BizException("工单不存在：" + id));
        batchService.enrich(order);
        return order;
    }

    /**
     * 齐套校验：走自定义 fragment 的手写 JPQL。
     *
     * @return 缺料行、齐套率、是否可投产
     */
    @Transactional(readOnly = true)
    public Map<String, Object> kitCheck(Long id) {
        WorkOrder order = get(id);
        List<ShortageLine> shortages = orderRepository.findShortageLines(id);
        List<BomItem> bom = bomRepository.findByProductModel(order.getProductModel());
        int total = bom.size();
        int shortageCount = shortages.size();
        double rate = total == 0 ? 0.0
                : Math.round(((total - shortageCount) * 1000.0 / total)) / 10.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderNo", order.getOrderNo());
        out.put("productModel", order.getProductModel());
        out.put("planQty", order.getPlanQty());
        out.put("bomLines", total);
        out.put("shortageLines", shortageCount);
        out.put("kitRate", rate);
        out.put("shortages", shortages);
        out.put("ok", shortageCount == 0 && rate >= Dict.MIN_KIT_RATE);
        return out;
    }

    /** 齐套不通过的工单一览（fragment 手写查询） */
    @Transactional(readOnly = true)
    public List<WorkOrder> shortageOrders() {
        return orderRepository.findShortageOrders();
    }

    @Transactional
    public WorkOrder create(WorkOrder input) {
        if (input.getOrderNo() == null || input.getOrderNo().isBlank()) {
            throw new BizException("工单号不能为空");
        }
        orderRepository.findByOrderNo(input.getOrderNo().trim()).ifPresent(o -> {
            throw new BizException("工单号已存在：" + input.getOrderNo());
        });
        if (input.getProductModel() == null || input.getProductModel().isBlank()) {
            throw new BizException("机型不能为空");
        }
        // 规则①：机型必须有 BOM
        List<BomItem> bom = bomRepository.findByProductModel(input.getProductModel());
        if (bom.isEmpty()) {
            throw new BizException("机型 " + input.getProductModel() + " 尚未维护 BOM，无法计算齐套");
        }
        if (input.getPlanQty() == null || input.getPlanQty() <= 0) {
            throw new BizException("计划板数必须大于 0");
        }
        if (input.getDueDate() != null && input.getDueDate().isBefore(LocalDate.now())) {
            throw new BizException("交期不能早于今天");
        }
        WorkOrder order = new WorkOrder();
        order.setOrderNo(input.getOrderNo().trim());
        order.setProductModel(input.getProductModel());
        order.setPlanQty(input.getPlanQty());
        order.setDoneQty(0);
        order.setPriority(input.getPriority() == null || input.getPriority().isBlank()
                ? "中" : input.getPriority());
        if (!Dict.isValidPriority(order.getPriority())) {
            throw new BizException("优先级不合法：" + order.getPriority());
        }
        order.setStatus("待投产");
        order.setDueDate(input.getDueDate());
        order.setCreatedAt(LocalDateTime.now());
        WorkOrder saved = orderRepository.save(order);
        batchService.enrich(saved);
        return saved;
    }

    @Transactional
    public WorkOrder update(Long id, WorkOrder input) {
        WorkOrder order = get(id);
        if ("已取消".equals(order.getStatus()) || "已完成".equals(order.getStatus())) {
            throw new BizException("工单已处于「" + order.getStatus() + "」，不能再修改");
        }
        boolean batchOrder = batchService.isBatchOrder(id);
        if (batchOrder && input.getPlanQty() != null) {
            throw new BizException("已启用分批的母工单不能直接改计划板数，请在数量守恒前提下调整未投产批次或取消批次");
        }
        if (input.getPriority() != null && !input.getPriority().isBlank()) {
            if (!Dict.isValidPriority(input.getPriority())) {
                throw new BizException("优先级不合法：" + input.getPriority());
            }
            order.setPriority(input.getPriority());
        }
        if (input.getPlanQty() != null) {
            if (input.getPlanQty() <= 0) {
                throw new BizException("计划板数必须大于 0");
            }
            if (order.getDoneQty() != null && order.getDoneQty() > input.getPlanQty()) {
                throw new BizException("计划板数不能小于已完成的 " + order.getDoneQty() + " 板");
            }
            order.setPlanQty(input.getPlanQty());
        }
        if (input.getDueDate() != null) {
            order.setDueDate(input.getDueDate());
        }
        WorkOrder saved = orderRepository.save(order);
        batchService.enrich(saved);
        return saved;
    }

    /**
     * 投产：通过齐套校验后推进到「贴片中」，并铺开 SMT 五道工序。
     */
    @Transactional
    public WorkOrder release(Long id) {
        WorkOrder order = get(id);
        if (batchService.isBatchOrder(id)) {
            throw new BizException("该工单已启用分批，请选择具体制造批次投产");
        }
        if (!"待投产".equals(order.getStatus())) {
            throw new BizException("只有「待投产」的工单能投产，当前状态：" + order.getStatus());
        }
        // 规则②：齐套不通过不允许投产
        Map<String, Object> kit = kitCheck(id);
        if (Boolean.FALSE.equals(kit.get("ok"))) {
            throw new BizException("齐套不通过：缺料 " + kit.get("shortageLines") + " 项，齐套率 "
                    + kit.get("kitRate") + "%（需 ≥ " + Dict.MIN_KIT_RATE + "%）");
        }
        order.setStatus("贴片中");
        WorkOrder saved = orderRepository.save(order);

        // 规则④：铺开五道工序
        for (String step : Dict.STEPS) {
            boolean exists = stepRepository.findByOrderIdAndStepName(saved.getId(), step).isPresent();
            if (!exists) {
                SmtStep s = new SmtStep();
                s.setOrder(saved);
                s.setStepName(step);
                s.setStatus("待执行");
                stepRepository.save(s);
            }
        }

        // 规则④：登记初始产线配置，作为后续换线批次的比对基准
        if (configRepository.findById(saved.getId()).isEmpty()) {
            OrderLineConfig cfg = new OrderLineConfig();
            cfg.setOrderId(saved.getId());
            cfg.setEquipmentNo("SMT-01");
            cfg.setFeederConfig("按 BOM 默认飞达位");
            cfg.setKeyParams("标准工艺参数");
            cfg.setConfigHash(Dict.configHash(cfg.getEquipmentNo(), cfg.getFeederConfig(), cfg.getKeyParams()));
            cfg.setUpdatedBy("系统");
            cfg.setUpdatedAt(LocalDateTime.now());
            configRepository.save(cfg);
        }
        batchService.enrich(saved);
        return saved;
    }

    /** 报工：累加完成板数 */
    @Transactional
    public WorkOrder report(Long id, Integer qty) {
        WorkOrder order = get(id);
        if (batchService.isBatchOrder(id)) {
            throw new BizException("该工单已启用分批，请对具体制造批次报工");
        }
        if (qty == null || qty <= 0) {
            throw new BizException("报工数量必须大于 0");
        }
        if (!"贴片中".equals(order.getStatus())) {
            throw new BizException("只有「贴片中」的工单能报工，当前状态：" + order.getStatus());
        }
        if (order.getDoneQty() + qty > order.getPlanQty()) {
            throw new BizException("报工后 " + (order.getDoneQty() + qty)
                    + " 板超过计划 " + order.getPlanQty() + " 板");
        }
        order.setDoneQty(order.getDoneQty() + qty);
        WorkOrder saved = orderRepository.save(order);
        batchService.enrich(saved);
        return saved;
    }

    /** 状态推进：待投产 → 贴片中 → 待检验 → 已完成 */
    @Transactional
    public WorkOrder transfer(Long id, String target) {
        WorkOrder order = get(id);
        if (batchService.isBatchOrder(id)) {
            throw new BizException("分批工单的母状态由各批次自动汇总，只有全部批次完成后才能完成，请操作具体批次");
        }
        if (!Dict.isValidOrderStatus(target)) {
            throw new BizException("工单状态不合法：" + target);
        }
        if (!"已取消".equals(target) && !Dict.canTransferOrder(order.getStatus(), target)) {
            throw new BizException("工单只能逐级推进，不允许从「" + order.getStatus() + "」到「" + target + "」");
        }
        if ("已完成".equals(target) && order.getGapQty() > 0) {
            throw new BizException("还欠 " + order.getGapQty() + " 板未完成，不能置为已完成");
        }
        // 返修闭环未走完不能推进：有未复验通过的返修任务时不能把工单往前转
        int openRepairs = repairTaskService.countOpenByOrder(id);
        if (openRepairs > 0 && ("待检验".equals(target) || "已完成".equals(target))) {
            throw new BizException("工单还有 " + openRepairs
                    + " 个返修任务未复验闭环，必须经质检员复验通过后才能推进到「" + target + "」");
        }
        order.setStatus(target);
        WorkOrder saved = orderRepository.save(order);
        batchService.enrich(saved);
        return saved;
    }
}
