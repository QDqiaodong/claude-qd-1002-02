package com.electronics.assembly.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.common.Dict;
import com.electronics.assembly.dto.BatchSplitRequest;
import com.electronics.assembly.entity.BomItem;
import com.electronics.assembly.entity.Component;
import com.electronics.assembly.entity.OrderLineConfig;
import com.electronics.assembly.entity.ProductionBatch;
import com.electronics.assembly.entity.ProductionBatchEvent;
import com.electronics.assembly.entity.SmtStep;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.repository.BomItemRepository;
import com.electronics.assembly.repository.ComponentRepository;
import com.electronics.assembly.repository.OrderLineConfigRepository;
import com.electronics.assembly.repository.ProductionBatchEventRepository;
import com.electronics.assembly.repository.ProductionBatchRepository;
import com.electronics.assembly.repository.RepairTaskRepository;
import com.electronics.assembly.repository.SmtStepRepository;
import com.electronics.assembly.repository.WorkOrderRepository;

/**
 * 工单分批生产业务：母工单拆成多个制造批次，每批记录批次号 / 计划数量 / 交付顺序 / 状态，
 * 并独立走齐套、投产、五道 SMT 工序与质检。
 *
 * <h3>数量守恒（整份方案不守恒则整份不保存）</h3>
 * <pre>
 *   Σ 有效批次.planQty + Σ 减产.reduceQty = 母工单.planQty
 * </pre>
 * 首次分批时母工单原有完成数量先落到只读历史批次冻结，不与新批次重复累计；
 * 故新批次方案合计必须等于 {@code 母工单计划 - 历史冻结数量}。拆分数量不足、
 * 超额或出现空批次（计划 ≤ 0）一律整份拒绝。
 *
 * <h3>生产后调整限制</h3>
 * 批次未投产（待投产且无任何数量落账）时可调整计划数量 / 删除；任一批次开始生产后，
 * 只能调整仍未投产的批次——已报工 / 已送检 / 已完成的数量不得挪走，批次状态不可倒退。
 *
 * <h3>取消回收与减产</h3>
 * 取消批次时，已落账数量冻结在本批只读，剩余未产数量必须回收到某个未投产批次，
 * 或由计划员确认减产（母工单计划相应调减），二者必选其一，否则取消不落库。
 *
 * <h3>母子状态汇总</h3>
 * 启用分批后，母工单的完成数量、状态与交付进度全部由批次汇总，母工单不再直接
 * 报工 / 投产；只有全部有效批次完成后母工单才能完成。历史工单（split_enabled=false）
 * 继续按原流程运行，不经本服务。
 *
 * <p>并发口径：所有批次写操作先悲观锁母工单行串行化（与换线 / 返修一致），
 * 批次方案的读改写都在同一把工单锁内完成。</p>
 */
@Service
public class ProductionBatchService {

    private final ProductionBatchRepository batchRepository;
    private final ProductionBatchEventRepository eventRepository;
    private final WorkOrderRepository orderRepository;
    private final BomItemRepository bomRepository;
    private final ComponentRepository componentRepository;
    private final SmtStepRepository stepRepository;
    private final OrderLineConfigRepository configRepository;
    private final RepairTaskRepository repairTaskRepository;

    public ProductionBatchService(ProductionBatchRepository batchRepository,
                                  ProductionBatchEventRepository eventRepository,
                                  WorkOrderRepository orderRepository,
                                  BomItemRepository bomRepository,
                                  ComponentRepository componentRepository,
                                  SmtStepRepository stepRepository,
                                  OrderLineConfigRepository configRepository,
                                  RepairTaskRepository repairTaskRepository) {
        this.batchRepository = batchRepository;
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.bomRepository = bomRepository;
        this.componentRepository = componentRepository;
        this.stepRepository = stepRepository;
        this.configRepository = configRepository;
        this.repairTaskRepository = repairTaskRepository;
    }

    // ---------------- 查询 ----------------

    public ProductionBatch get(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new BizException("制造批次不存在：" + id));
    }

    /** 某工单全部批次（交付顺序，历史批次在最前） */
    public List<ProductionBatch> listByOrder(Long orderId) {
        return batchRepository.findByOrderIdOrderBySeqNoAsc(orderId);
    }

    /**
     * 工单分批上下文：批次树（每批齐套率 / 工序进度 / 数量差异 / 调整记录）+ 母工单汇总口径。
     * 工单卡片展开时一次性取回。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> context(Long orderId) {
        WorkOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException("工单不存在：" + orderId));
        List<ProductionBatch> batches = batchRepository.findByOrderIdOrderBySeqNoAsc(orderId);

        List<Map<String, Object>> tree = new ArrayList<>();
        int planSum = 0;
        int doneSum = 0;
        int reduceSum = 0;
        // 已取消批次上「已报工」的在制数量按规则不能挪走，物理上已产出、冻结在本批，
        // 与有效计划、减产一起构成守恒恒等式的一项（既不会重复投产，也不会被覆盖）。
        int frozenWip = 0;
        for (ProductionBatch b : batches) {
            tree.add(batchRow(b));
            if (b.isCancelled()) {
                reduceSum += nz(b.getReduceQty());
                frozenWip += nz(b.getReportedQty());
            } else {
                planSum += nz(b.getPlanQty());
            }
            doneSum += nz(b.getDoneQty());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", order.getId());
        out.put("orderNo", order.getOrderNo());
        out.put("splitEnabled", Boolean.TRUE.equals(order.getSplitEnabled()));
        out.put("orderPlanQty", order.getPlanQty());
        out.put("orderDoneQty", order.getDoneQty());
        out.put("orderStatus", order.getStatus());
        out.put("batches", tree);
        // 守恒恒等式：有效批次计划 + 已确认减产 + 取消批次冻结在制 = 母工单（原始）计划
        out.put("planSum", planSum);
        out.put("doneSum", doneSum);
        out.put("reduceSum", reduceSum);
        out.put("frozenWip", frozenWip);
        out.put("conserved", planSum + reduceSum + frozenWip == order.getPlanQty());
        // 母工单可完成条件：不存在「在途」（非完成/取消/历史）的新批次
        out.put("allFinished", allRegularFinished(batches));
        out.put("events", orderEvents(orderId));
        return out;
    }

    /** 单批齐套校验：需求 = 单板用量 × 本批计划数量，口径与工单齐套一致 */
    @Transactional(readOnly = true)
    public Map<String, Object> kitCheck(Long batchId) {
        ProductionBatch batch = get(batchId);
        WorkOrder order = batch.getOrder();
        Map<String, Object> kit = kitFor(order.getProductModel(), batch.getPlanQty());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batch.getId());
        out.put("batchNo", batch.getBatchNo());
        out.put("planQty", batch.getPlanQty());
        out.put("bomLines", kit.get("bomLines"));
        out.put("shortageLines", kit.get("shortageLines"));
        out.put("shortages", kit.get("shortages"));
        out.put("kitRate", kit.get("kitRate"));
        out.put("ok", kit.get("ok"));
        return out;
    }

    /**
     * 齐套核心：按机型 BOM 逐行比库存（需求 = 单板用量 × 指定计划数量）。
     * 与 {@code WorkOrderRepositoryCustomImpl} 的工单齐套口径一致，只是数量取自单批。
     */
    private Map<String, Object> kitFor(String productModel, int planQty) {
        List<BomItem> bom = bomRepository.findByProductModel(productModel);
        int total = bom.size();
        int shortageCount = 0;
        List<Map<String, Object>> shortageLines = new ArrayList<>();
        for (BomItem item : bom) {
            Component c = componentRepository.findByPartNo(item.getPartNo()).orElse(null);
            int stock = c == null ? 0 : nz(c.getStockQty());
            String compStatus = c == null ? "缺料" : c.getStatus();
            int required = item.getUnitQty() * planQty;
            boolean shortMaterial = c == null || stock < required || !"正常".equals(compStatus);
            if (shortMaterial) {
                shortageCount++;
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("partNo", item.getPartNo());
                line.put("partName", c == null ? null : c.getPartName());
                line.put("unitQty", item.getUnitQty());
                line.put("required", required);
                line.put("stock", stock);
                line.put("gap", Math.max(0, required - stock));
                line.put("status", compStatus);
                shortageLines.add(line);
            }
        }
        double rate = total == 0 ? 0.0
                : Math.round(((total - shortageCount) * 1000.0 / total)) / 10.0;
        Map<String, Object> kit = new LinkedHashMap<>();
        kit.put("bomLines", total);
        kit.put("shortageLines", shortageCount);
        kit.put("shortages", shortageLines);
        kit.put("kitRate", rate);
        kit.put("ok", shortageCount == 0 && rate >= Dict.MIN_KIT_RATE);
        return kit;
    }

    // ---------------- 分批方案：首次启用 / 调整（整份提交、整份守恒） ----------------

    /**
     * 保存分批方案。首次启用时把母工单原有完成数量落到只读历史批次（历史迁移），
     * 再按方案建新批次；之后再次保存即为「调整」——只允许改 / 增 / 删未投产批次。
     * 数量不足、超额、空批次、动到已开工批次都会整份拒绝，不落任何数据。
     */
    @Transactional
    public List<ProductionBatch> savePlan(Long orderId, BatchSplitRequest req) {
        if (req == null || req.getBatches() == null || req.getBatches().isEmpty()) {
            throw new BizException("分批方案不能为空：至少要有一个批次");
        }
        String actor = actor(req.getOperator());
        WorkOrder order = lockOrder(orderId);
        if ("已取消".equals(order.getStatus()) || "已完成".equals(order.getStatus())) {
            throw new BizException("工单已处于「" + order.getStatus() + "」，不能再分批");
        }

        List<ProductionBatch> existing = batchRepository.findByOrderIdOrderBySeqNoAsc(orderId);
        ProductionBatch history = existing.stream().filter(b -> Boolean.TRUE.equals(b.getReadOnly())).findFirst().orElse(null);

        // ① 首次启用：历史迁移——母工单原有完成数量冻结到只读历史批次，不与新批次重复累计
        boolean firstEnable = !Boolean.TRUE.equals(order.getSplitEnabled());
        int frozenDone;
        if (firstEnable) {
            if (history != null) {
                throw new BizException("分批状态不一致：已存在历史批次却未启用分批");
            }
            frozenDone = nz(order.getDoneQty());
            if (frozenDone > 0) {
                history = createHistoryBatch(order, frozenDone, actor);
            }
            order.setSplitEnabled(true);
        } else {
            frozenDone = history == null ? 0 : nz(history.getPlanQty());
        }

        // ② 方案行校验：空批次整份拒绝
        List<Integer> lineQtys = new ArrayList<>();
        for (BatchSplitRequest.Line line : req.getBatches()) {
            if (line.getPlanQty() == null || line.getPlanQty() <= 0) {
                throw new BizException("存在空批次（计划数量必须为大于 0 的整数），整份方案未保存");
            }
            lineQtys.add(line.getPlanQty());
        }

        // ③ 守恒：新批次计划合计必须等于「母工单计划 - 历史冻结 - 已确认减产 - 取消批次冻结在制」。
        // 取消批次若还有已报工在制数量，这部分按规则不能挪走、冻结在本批，也是守恒的一项。
        int reduced = existing.stream().filter(ProductionBatch::isCancelled)
                .mapToInt(b -> nz(b.getReduceQty())).sum();
        int frozenReported = existing.stream().filter(ProductionBatch::isCancelled)
                .mapToInt(b -> nz(b.getReportedQty())).sum();
        int target = order.getPlanQty() - frozenDone - reduced - frozenReported;
        int lineSum = lineQtys.stream().mapToInt(Integer::intValue).sum();
        if (lineSum != target) {
            throw new BizException("数量不守恒：有效批次计划合计 " + lineSum + " 板，必须等于母工单可拆数量 "
                    + target + " 板（计划 " + order.getPlanQty() + " - 历史冻结 " + frozenDone
                    + " - 已减产 " + reduced + " - 取消冻结在制 " + frozenReported + "），整份方案未保存");
        }

        // ④ 行 id 归属与「只能动未投产批次」校验。
        // 已开工批次（含已报工/送检/完成或状态越过待投产）为锚点：必须原样保留在方案中，
        // 计划数量与交付顺序都冻结；计划员只能在锚点之间调整未投产批次。
        Map<Long, ProductionBatch> regularById = new LinkedHashMap<>();
        List<ProductionBatch> anchors = new ArrayList<>();
        for (ProductionBatch b : existing) {
            if (Boolean.TRUE.equals(b.getReadOnly()) || b.isCancelled()) {
                continue; // 历史批次与已取消批次不进方案
            }
            regularById.put(b.getId(), b);
            if (b.isStarted()) {
                anchors.add(b);
            }
        }
        int maxBatchNo = existing.stream()
                .filter(b -> Boolean.FALSE.equals(b.getReadOnly()))
                .mapToInt(b -> batchSerial(b.getBatchNo())).max().orElse(0);
        // 新批次先占用「现有最大 seq 之后」的临时序号落库，避免多行 seq=0 撞 uk_pb_seq；
        // 方案全部解析完后在第 ⑥ 步按行顺序统一重排为 1..n
        int tempSeq = existing.stream().mapToInt(b -> nz(b.getSeqNo())).max().orElse(0) + 1;

        List<ProductionBatch> planBatches = new ArrayList<>();
        for (BatchSplitRequest.Line line : req.getBatches()) {
            ProductionBatch b;
            if (line.getId() != null) {
                b = regularById.get(line.getId());
                if (b == null) {
                    throw new BizException("批次 " + line.getId() + " 不存在、已取消或不属于本工单，整份方案未保存");
                }
                if (b.isStarted()) {
                    // 锚点：数量必须与现状一致，已报工/送检/完成数量不得挪走
                    if (nz(b.getPlanQty()) != line.getPlanQty()) {
                        throw new BizException("批次「" + b.getBatchNo() + "」已开始生产（状态：" + b.getStatus()
                                + "），计划数量 " + b.getPlanQty() + " 已冻结，不能改为 " + line.getPlanQty()
                                + "；只能调整未投产批次，整份方案未保存");
                    }
                } else if (nz(b.getPlanQty()) != line.getPlanQty()) {
                    int oldQty = b.getPlanQty();
                    b.setPlanQty(line.getPlanQty());
                    b = batchRepository.save(b);
                    log(b, "调整计划", actor, "计划数量 " + oldQty + " → " + line.getPlanQty() + " 板（未投产批次调整）");
                }
            } else {
                maxBatchNo++;
                b = new ProductionBatch();
                b.setOrder(order);
                b.setBatchNo(order.getOrderNo() + "-B" + maxBatchNo);
                b.setPlanQty(line.getPlanQty());
                b.setReportedQty(0);
                b.setInspectedQty(0);
                b.setDoneQty(0);
                b.setReduceQty(0);
                b.setStatus("待投产");
                b.setReadOnly(false);
                b.setCreatedAt(LocalDateTime.now());
                b.setSeqNo(tempSeq++); // 临时序号，第 ⑥ 步按方案行顺序重排
                b = batchRepository.save(b);
                log(b, "建批", actor, "新建批次，计划 " + line.getPlanQty() + " 板");
            }
            planBatches.add(b);
        }

        // ⑤ 已开工锚点一个都不能少、不能被删除；未出现在方案里的未投产批次视为删除
        for (ProductionBatch anchor : anchors) {
            if (planBatches.stream().noneMatch(b -> b.getId().equals(anchor.getId()))) {
                throw new BizException("批次「" + anchor.getBatchNo() + "」已开始生产，不能删除；"
                        + "已报工/送检/完成数量不得挪走，整份方案未保存");
            }
        }
        java.util.Set<Long> planIds = new java.util.HashSet<>();
        for (ProductionBatch b : planBatches) {
            if (b.getId() != null) {
                planIds.add(b.getId());
            }
        }
        List<ProductionBatch> toDelete = new ArrayList<>();
        for (ProductionBatch b : regularById.values()) {
            if (!b.isStarted() && !planIds.contains(b.getId())) {
                toDelete.add(b);
            }
        }
        ProductionBatch deleteAnchor = planBatches.get(0);
        for (ProductionBatch leftover : toDelete) {
            String no = leftover.getBatchNo();
            int qty = leftover.getPlanQty();
            eventRepository.deleteAll(eventRepository.findByBatchIdOrderByIdAsc(leftover.getId()));
            batchRepository.delete(leftover);
            // 删除事件挂到保留批次（被删行需要外键，事件必须指向仍存在的批次）
            log(deleteAnchor, "删除", actor, "删除未投产批次「" + no + "」，其计划 " + qty
                    + " 板由本份守恒方案重新安排");
        }

        // ⑥ 交付顺序：方案行顺序即新的 1..n 交付顺序。已开工锚点的相对先后必须与现状一致
        // （只能在锚点之间安插/调整未投产批次，不允许把已开工批次提前或挪后 → 状态不倒退）。
        int prevAnchorSeq = 0;
        for (ProductionBatch b : planBatches) {
            if (b.isStarted()) {
                if (b.getSeqNo() < prevAnchorSeq) {
                    throw new BizException("已开始生产的批次「" + b.getBatchNo()
                            + "」交付顺序不能提前，整份方案未保存");
                }
                prevAnchorSeq = b.getSeqNo();
            }
        }
        int seq = 1;
        for (ProductionBatch b : planBatches) {
            b.setSeqNo(seq++);
            batchRepository.save(b);
        }

        orderRepository.save(order);
        return batchRepository.findByOrderIdOrderBySeqNoAsc(orderId);
    }

    // ---------------- 批次流转：投产 / 报工 / 送检 / 完成 / 取消 ----------------

    /** 投产：齐套通过后铺开本批五道工序，批次 → 贴片中，并重算母工单汇总 */
    @Transactional
    public ProductionBatch release(Long batchId) {
        ProductionBatch batch = get(batchId);
        WorkOrder order = lockOrder(batch.getOrder().getId());
        batch = reload(batchId);
        if (Boolean.TRUE.equals(batch.getReadOnly())) {
            throw new BizException("历史批次「" + batch.getBatchNo() + "」为只读冻结数据，不能投产");
        }
        if (!"待投产".equals(batch.getStatus())) {
            throw new BizException("只有「待投产」批次能投产，当前状态：" + batch.getStatus());
        }
        Map<String, Object> kit = kitCheck(batchId);
        if (Boolean.FALSE.equals(kit.get("ok"))) {
            throw new BizException("批次「" + batch.getBatchNo() + "」齐套不通过：缺料 " + kit.get("shortageLines")
                    + " 项，齐套率 " + kit.get("kitRate") + "%（需 ≥ " + Dict.MIN_KIT_RATE + "%）");
        }
        batch.setStatus("贴片中");
        batch = batchRepository.save(batch);
        for (String step : Dict.STEPS) {
            if (!stepRepository.existsByBatchIdAndStepName(batch.getId(), step)) {
                SmtStep s = new SmtStep();
                s.setOrder(order);
                s.setBatch(batch);
                s.setStepName(step);
                s.setStatus("待执行");
                stepRepository.save(s);
            }
        }
        // 母工单首条在制批次投产时补登记初始产线配置（换线比对基准），与原工单投产口径一致
        if (configRepository.findById(order.getId()).isEmpty()) {
            OrderLineConfig cfg = new OrderLineConfig();
            cfg.setOrderId(order.getId());
            cfg.setEquipmentNo("SMT-01");
            cfg.setFeederConfig("按 BOM 默认飞达位");
            cfg.setKeyParams("标准工艺参数");
            cfg.setConfigHash(Dict.configHash(cfg.getEquipmentNo(), cfg.getFeederConfig(), cfg.getKeyParams()));
            cfg.setUpdatedBy("系统");
            cfg.setUpdatedAt(LocalDateTime.now());
            configRepository.save(cfg);
        }
        log(batch, "投产", "系统", "齐套通过，铺开五道 SMT 工序");
        recomputeOrder(order);
        return batch;
    }

    /** 报工：累加本批已报工数量，不得超过本批计划，已报工数量不可挪走 */
    @Transactional
    public ProductionBatch report(Long batchId, Integer qty, String operator) {
        if (qty == null || qty <= 0) {
            throw new BizException("报工数量必须大于 0");
        }
        ProductionBatch batch = get(batchId);
        WorkOrder order = lockOrder(batch.getOrder().getId());
        batch = reload(batchId);
        assertNotHistory(batch);
        if (batch.isCancelled()) {
            throw new BizException("批次「" + batch.getBatchNo() + "」已取消，不能再报工");
        }
        if (nz(batch.getReportedQty()) + qty > nz(batch.getPlanQty())) {
            throw new BizException("批次「" + batch.getBatchNo() + "」报工后 " + (nz(batch.getReportedQty()) + qty)
                    + " 板超过本批计划 " + batch.getPlanQty() + " 板");
        }
        batch.setReportedQty(nz(batch.getReportedQty()) + qty);
        batch = batchRepository.save(batch);
        log(batch, "报工", actor(operator), "报工 +" + qty + "，累计报工 " + batch.getReportedQty()
                + " / 计划 " + batch.getPlanQty());
        recomputeOrder(order);
        return batch;
    }

    /** 送检：累加已送检数量，送检量不得超过已报工数量（未报工不能送检） */
    @Transactional
    public ProductionBatch inspect(Long batchId, Integer qty, String operator) {
        if (qty == null || qty <= 0) {
            throw new BizException("送检数量必须大于 0");
        }
        ProductionBatch batch = get(batchId);
        WorkOrder order = lockOrder(batch.getOrder().getId());
        batch = reload(batchId);
        assertNotHistory(batch);
        if (batch.isCancelled()) {
            throw new BizException("批次「" + batch.getBatchNo() + "」已取消，不能再送检");
        }
        if (nz(batch.getInspectedQty()) + qty > nz(batch.getReportedQty())) {
            throw new BizException("批次「" + batch.getBatchNo() + "」送检后 " + (nz(batch.getInspectedQty()) + qty)
                    + " 板超过已报工 " + batch.getReportedQty() + " 板");
        }
        batch.setInspectedQty(nz(batch.getInspectedQty()) + qty);
        batch = batchRepository.save(batch);
        log(batch, "送检", actor(operator), "送检 +" + qty + "，累计送检 " + batch.getInspectedQty()
                + " / 报工 " + batch.getReportedQty());
        recomputeOrder(order);
        return batch;
    }

    /** 完成（合格入库）：累加已完成数量，不得超过已送检数量；自动完成整批可联动状态 */
    @Transactional
    public ProductionBatch completeQty(Long batchId, Integer qty, String operator) {
        if (qty == null || qty <= 0) {
            throw new BizException("完成数量必须大于 0");
        }
        ProductionBatch batch = get(batchId);
        WorkOrder order = lockOrder(batch.getOrder().getId());
        batch = reload(batchId);
        assertNotHistory(batch);
        if (batch.isCancelled()) {
            throw new BizException("批次「" + batch.getBatchNo() + "」已取消，不能再登记完成");
        }
        if (nz(batch.getDoneQty()) + qty > nz(batch.getInspectedQty())) {
            throw new BizException("批次「" + batch.getBatchNo() + "」完成后 " + (nz(batch.getDoneQty()) + qty)
                    + " 板超过已送检 " + batch.getInspectedQty() + " 板");
        }
        batch.setDoneQty(nz(batch.getDoneQty()) + qty);
        batch = batchRepository.save(batch);
        log(batch, "完成", actor(operator), "合格入库 +" + qty + "，累计完成 " + batch.getDoneQty()
                + " / 计划 " + batch.getPlanQty());
        recomputeOrder(order);
        return batch;
    }

    /**
     * 批次状态推进：待投产 → 贴片中 → 待检验 → 已完成，只能逐级、不能倒退；
     * 已取消为旁路终态。完成时要求本批数量全部合格入库且无未闭环返修，
     * 且全部五道工序已完成。
     */
    @Transactional
    public ProductionBatch transfer(Long batchId, String target) {
        if (!Dict.isValidBatchStatus(target)) {
            throw new BizException("批次状态不合法：" + target);
        }
        ProductionBatch batch = get(batchId);
        WorkOrder order = lockOrder(batch.getOrder().getId());
        batch = reload(batchId);
        assertNotHistory(batch);
        if ("已取消".equals(target)) {
            throw new BizException("取消批次请走专用取消接口（需回收剩余数量或确认减产）");
        }
        if (batch.isCancelled()) {
            throw new BizException("批次已取消，状态为终态");
        }
        // 状态不可倒退
        if (Dict.batchStageIndex(target) >= 0 && Dict.batchStageIndex(target) < Dict.batchStageIndex(batch.getStatus())) {
            throw new BizException("批次状态不能倒退：不允许从「" + batch.getStatus() + "」回到「" + target + "」");
        }
        if (!Dict.canTransferBatch(batch.getStatus(), target)) {
            throw new BizException("批次只能逐级推进，不允许从「" + batch.getStatus() + "」到「" + target + "」");
        }
        if ("已完成".equals(target)) {
            if (nz(batch.getDoneQty()) != nz(batch.getPlanQty())) {
                throw new BizException("批次「" + batch.getBatchNo() + "」还欠 "
                        + (nz(batch.getPlanQty()) - nz(batch.getDoneQty())) + " 板未合格入库，不能完成");
            }
            if (!repairTaskRepository.findOpenByBatchId(batch.getId()).isEmpty()) {
                throw new BizException("批次「" + batch.getBatchNo() + "」还有未复验闭环的返修任务，不能完成");
            }
            List<SmtStep> steps = stepRepository.findByBatchIdOrderById(batch.getId());
            long doneSteps = steps.stream().filter(s -> "已完成".equals(s.getStatus())).count();
            if (steps.size() < Dict.STEPS.size() || doneSteps < Dict.STEPS.size()) {
                throw new BizException("批次「" + batch.getBatchNo() + "」五道 SMT 工序未全部完成，不能完成");
            }
        }
        batch.setStatus(target);
        batch = batchRepository.save(batch);
        log(batch, "完成".equals(target) ? "完成" : "投产", "系统",
                "批次状态推进为「" + target + "」");
        recomputeOrder(order);
        return batch;
    }

    /**
     * 取消批次：已报工 / 已送检 / 已完成数量冻结在本批只读，剩余未产数量
     * （planQty - reportedQty）必须二选一处理，否则取消不落库：
     * <ul>
     *   <li>回收：{@code reclaimToBatchId} 指定一个<b>未投产</b>批次承接，整笔加到其计划上；</li>
     *   <li>减产：{@code confirmReduce=true} 由计划员确认，母工单计划相应调减并记 reduce_qty。</li>
     * </ul>
     */
    @Transactional
    public ProductionBatch cancel(Long batchId, Long reclaimToBatchId, boolean confirmReduce, String operator) {
        String actor = actor(operator);
        ProductionBatch batch = get(batchId);
        WorkOrder order = lockOrder(batch.getOrder().getId());
        batch = reload(batchId);
        assertNotHistory(batch);
        if (batch.isCancelled()) {
            throw new BizException("批次「" + batch.getBatchNo() + "」已取消");
        }
        if ("已完成".equals(batch.getStatus())) {
            throw new BizException("批次「" + batch.getBatchNo() + "」已完成，不能取消");
        }
        int leftover = batch.getUnreportedQty();
        if (leftover <= 0) {
            throw new BizException("批次「" + batch.getBatchNo() + "」计划数量已全部报工，无剩余可取消；"
                    + "已报工/送检/完成数量不得挪走");
        }
        if (reclaimToBatchId == null && !confirmReduce) {
            throw new BizException("取消批次有 " + leftover + " 板未生产：必须回收到未投产批次，"
                    + "或勾选由计划员确认减产，否则不能取消");
        }
        if (reclaimToBatchId != null && confirmReduce) {
            throw new BizException("回收与确认减产只能二选一");
        }

        if (reclaimToBatchId != null) {
            if (reclaimToBatchId.equals(batch.getId())) {
                throw new BizException("不能回收到批次自身");
            }
            ProductionBatch target = get(reclaimToBatchId);
            if (!target.getOrder().getId().equals(order.getId())) {
                throw new BizException("回收目标批次必须属于同一母工单");
            }
            if (target.isCancelled() || Boolean.TRUE.equals(target.getReadOnly())) {
                throw new BizException("只能回收到未投产的有效批次，目标「" + target.getBatchNo()
                        + "」状态：" + target.getStatus());
            }
            if (!"待投产".equals(target.getStatus()) || target.isStarted()) {
                throw new BizException("只能回收到未投产批次，目标「" + target.getBatchNo() + "」已开始生产");
            }
            int oldPlan = target.getPlanQty();
            target.setPlanQty(oldPlan + leftover);
            target = batchRepository.save(target);
            log(target, "回收", actor, "接收取消批次「" + batch.getBatchNo() + "」剩余 " + leftover
                    + " 板，计划 " + oldPlan + " → " + target.getPlanQty());
        } else {
            // 确认减产：母工单原始计划板数保持不变（数量口径统一锚定原计划，避免交付进度失真），
            // 本批记 reduce_qty，守恒恒等式里「有效计划 + 减产 + 冻结在制 = 原计划」仍成立。
            batch.setReduceQty(leftover);
            log(batch, "减产", actor, "计划员确认减产 " + leftover
                    + " 板（母工单计划仍为 " + order.getPlanQty() + " 板，交付按剩余有效批次统计）");
        }

        batch.setStatus("已取消");
        batch = batchRepository.save(batch);
        log(batch, "取消", actor, "批次取消，已报工 " + nz(batch.getReportedQty()) + " / 已送检 "
                + nz(batch.getInspectedQty()) + " / 已完成 " + nz(batch.getDoneQty())
                + " 板冻结在本批，未产 " + leftover + " 板已" + (confirmReduce ? "确认减产" : "回收"));
        recomputeOrder(order);
        return batch;
    }

    // ---------------- 母子汇总 ----------------

    /**
     * 按批次重算母工单的完成数量、状态与交付进度（启用分批后唯一的母工单数量写入口）。
     * <ul>
     *   <li>doneQty = Σ 全部批次.doneQty（含历史 / 已取消的冻结数量）；</li>
     *   <li>状态取在途新批次的最前阶段：有贴片中→贴片中，否则有待检验→待检验，
     *       否则有待投产→待投产；没有在途批次（全完成 / 全取消 / 仅历史）维持现状，
     *       母工单「已完成」只能由计划员在全部批次完成后显式推进。</li>
     * </ul>
     */
    @Transactional
    public WorkOrder recomputeOrder(WorkOrder order) {
        if (!Boolean.TRUE.equals(order.getSplitEnabled())) {
            return order;
        }
        List<ProductionBatch> batches = batchRepository.findByOrderIdOrderBySeqNoAsc(order.getId());
        int done = batches.stream().mapToInt(b -> nz(b.getDoneQty())).sum();
        order.setDoneQty(done);

        if (!"已取消".equals(order.getStatus()) && !"已完成".equals(order.getStatus())) {
            boolean producing = false, inspecting = false, waiting = false, everStarted = false;
            for (ProductionBatch b : batches) {
                if (Boolean.TRUE.equals(b.getReadOnly()) || b.isCancelled()) {
                    continue;
                }
                switch (b.getStatus()) {
                    case "贴片中" -> producing = true;
                    case "待检验" -> inspecting = true;
                    case "待投产" -> waiting = true;
                    default -> { }
                }
                // 已完成批次、或已有数量落账的批次，说明母工单整体已进入生产
                if (!"待投产".equals(b.getStatus()) || nz(b.getReportedQty()) > 0) {
                    everStarted = true;
                }
            }
            if (producing) {
                order.setStatus("贴片中");
            } else if (inspecting) {
                order.setStatus("待检验");
            } else if (waiting) {
                // 前序批次已完成/送检、后序批次尚未投产：母工单整体仍在生产中，不回落到待投产
                order.setStatus(everStarted ? "贴片中" : "待投产");
            }
            // 全部新批次已完成 / 已取消（或仅历史）：不自动置已完成，等计划员显式完成母工单
        }
        return orderRepository.save(order);
    }

    /** 母工单是否可完成：全部新批次均已完成或取消（历史批次不阻塞） */
    @Transactional(readOnly = true)
    public boolean allRegularFinished(Long orderId) {
        return allRegularFinished(batchRepository.findByOrderIdOrderBySeqNoAsc(orderId));
    }

    // ---------------- 内部 ----------------

    private boolean allRegularFinished(List<ProductionBatch> batches) {
        return batches.stream()
                .filter(b -> Boolean.FALSE.equals(b.getReadOnly()))
                .allMatch(b -> "已完成".equals(b.getStatus()) || "已取消".equals(b.getStatus()));
    }

    /** 首次启用分批：母工单原完成数量落到只读历史批次（计划=完成=doneQty，seq=0，已完成） */
    private ProductionBatch createHistoryBatch(WorkOrder order, int frozenDone, String actor) {
        ProductionBatch h = new ProductionBatch();
        h.setOrder(order);
        h.setBatchNo(order.getOrderNo() + "-H");
        h.setSeqNo(0);
        h.setPlanQty(frozenDone);
        h.setReportedQty(frozenDone);
        h.setInspectedQty(frozenDone);
        h.setDoneQty(frozenDone);
        h.setReduceQty(0);
        h.setStatus("已完成");
        h.setReadOnly(true);
        h.setCreatedAt(LocalDateTime.now());
        h = batchRepository.save(h);
        log(h, "历史迁移", actor, "首次启用分批：母工单原完成 " + frozenDone
                + " 板落到只读历史批次冻结，不与新批次重复累计");
        return h;
    }

    private Map<String, Object> batchRow(ProductionBatch b) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", b.getId());
        row.put("batchNo", b.getBatchNo());
        row.put("seqNo", b.getSeqNo());
        row.put("planQty", b.getPlanQty());
        row.put("reportedQty", nz(b.getReportedQty()));
        row.put("inspectedQty", nz(b.getInspectedQty()));
        row.put("doneQty", nz(b.getDoneQty()));
        row.put("reduceQty", nz(b.getReduceQty()));
        row.put("status", b.getStatus());
        row.put("readOnly", Boolean.TRUE.equals(b.getReadOnly()));
        row.put("started", b.isStarted());
        // 数量差异：计划 - 已完成（欠产）；已取消批次显示其冻结/减产拆解
        row.put("gapQty", b.isCancelled() ? 0 : Math.max(0, nz(b.getPlanQty()) - nz(b.getDoneQty())));
        row.put("unreportedQty", b.getUnreportedQty());
        row.put("progressRate", b.getProgressRate());

        // 齐套率：按本批计划数量对 BOM 逐行比库存
        Map<String, Object> kit = kitFor(b.getOrder().getProductModel(), b.getPlanQty());
        row.put("kitRate", kit.get("kitRate"));
        row.put("kitOk", kit.get("ok"));

        // 工序进度：本批五道工序
        List<SmtStep> steps = stepRepository.findByBatchIdOrderById(b.getId());
        long doneSteps = steps.stream().filter(s -> "已完成".equals(s.getStatus())).count();
        row.put("stepDone", doneSteps);
        row.put("stepTotal", steps.size());
        List<String> stepStatus = new ArrayList<>();
        for (String name : Dict.STEPS) {
            stepStatus.add(steps.stream().filter(s -> name.equals(s.getStepName())).findFirst()
                    .map(SmtStep::getStatus).orElse("未建立"));
        }
        row.put("stepStatus", stepStatus);

        row.put("events", events(b.getId()));
        return row;
    }

    private List<Map<String, Object>> orderEvents(Long orderId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProductionBatchEvent e : eventRepository.findByBatchOrderIdOrderByIdAsc(orderId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("batchId", e.getBatch().getId());
            m.put("batchNo", e.getBatch().getBatchNo());
            m.put("action", e.getAction());
            m.put("actor", e.getActor());
            m.put("detail", e.getDetail());
            m.put("createdAt", e.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> events(Long batchId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProductionBatchEvent e : eventRepository.findByBatchIdOrderByIdAsc(batchId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", e.getAction());
            m.put("actor", e.getActor());
            m.put("detail", e.getDetail());
            m.put("createdAt", e.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private void log(ProductionBatch batch, String action, String actor, String detail) {
        ProductionBatchEvent e = new ProductionBatchEvent();
        e.setBatch(batch);
        e.setAction(action);
        e.setActor(actor);
        e.setDetail(detail);
        e.setCreatedAt(LocalDateTime.now());
        eventRepository.save(e);
    }

    private WorkOrder lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("工单不存在：" + orderId));
    }

    private ProductionBatch reload(Long batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> new BizException("制造批次不存在：" + batchId));
    }

    private void assertNotHistory(ProductionBatch batch) {
        if (Boolean.TRUE.equals(batch.getReadOnly())) {
            throw new BizException("历史批次「" + batch.getBatchNo() + "」为只读冻结数据，不能修改");
        }
    }

    /** 从批次号取序号：WO-x-B12 → 12；历史批次 H 视为 0 */
    private int batchSerial(String batchNo) {
        int idx = batchNo.lastIndexOf("-B");
        if (idx < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(batchNo.substring(idx + 2));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String actor(String v) {
        return v == null || v.isBlank() ? "计划员" : v.trim();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
