package com.electronics.assembly.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.common.Dict;
import com.electronics.assembly.dto.BatchAdjustRequest;
import com.electronics.assembly.dto.BatchCancelRequest;
import com.electronics.assembly.dto.BatchSplitRequest;
import com.electronics.assembly.dto.ShortageLine;
import com.electronics.assembly.entity.BatchAdjustmentEvent;
import com.electronics.assembly.entity.BomItem;
import com.electronics.assembly.entity.Inspection;
import com.electronics.assembly.entity.OrderLineConfig;
import com.electronics.assembly.entity.ProductionBatch;
import com.electronics.assembly.entity.RepairTask;
import com.electronics.assembly.entity.SmtStep;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.repository.BatchAdjustmentEventRepository;
import com.electronics.assembly.repository.BomItemRepository;
import com.electronics.assembly.repository.InspectionRepository;
import com.electronics.assembly.repository.OrderLineConfigRepository;
import com.electronics.assembly.repository.ProductionBatchRepository;
import com.electronics.assembly.repository.RepairTaskRepository;
import com.electronics.assembly.repository.SmtStepRepository;
import com.electronics.assembly.repository.WorkOrderRepository;

/**
 * 工单分批生产业务。
 *
 * <p>母工单只保留统一数量口径与汇总状态：有效批次计划数必须等于母工单计划数；
 * 已报工、已送检、已完成数量分别锁定，生产开始后不可调整或挪走。历史批次只读，
 * 承接首次分批前的旧完成数量，避免与新批次重复累计。</p>
 */
@Service
public class ProductionBatchService {

    private final WorkOrderRepository orderRepository;
    private final ProductionBatchRepository batchRepository;
    private final BatchAdjustmentEventRepository eventRepository;
    private final BomItemRepository bomRepository;
    private final SmtStepRepository stepRepository;
    private final InspectionRepository inspectionRepository;
    private final RepairTaskRepository repairTaskRepository;
    private final OrderLineConfigRepository configRepository;

    public ProductionBatchService(WorkOrderRepository orderRepository,
                                  ProductionBatchRepository batchRepository,
                                  BatchAdjustmentEventRepository eventRepository,
                                  BomItemRepository bomRepository,
                                  SmtStepRepository stepRepository,
                                  InspectionRepository inspectionRepository,
                                  RepairTaskRepository repairTaskRepository,
                                  OrderLineConfigRepository configRepository) {
        this.orderRepository = orderRepository;
        this.batchRepository = batchRepository;
        this.eventRepository = eventRepository;
        this.bomRepository = bomRepository;
        this.stepRepository = stepRepository;
        this.inspectionRepository = inspectionRepository;
        this.repairTaskRepository = repairTaskRepository;
        this.configRepository = configRepository;
    }

    // ---------------- 查询 / 卡片汇总 ----------------

    @Transactional(readOnly = true)
    public List<ProductionBatch> listByOrder(Long orderId) {
        getOrder(orderId);
        return enrichBatches(orderId, batchRepository.findTreeByOrderId(orderId));
    }

    @Transactional(readOnly = true)
    public ProductionBatch get(Long id) {
        ProductionBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new BizException("制造批次不存在：" + id));
        return enrich(batch);
    }

    public boolean isBatchOrder(Long orderId) {
        return !batchRepository.findByOrderIdOrderByDeliverySeqAscIdAsc(orderId).isEmpty();
    }

    /** 工单列表/详情统一装配：未分批历史工单不写 batches，旧页面仍按原流程运行。 */
    @Transactional(readOnly = true)
    public void enrich(WorkOrder order) {
        if (order == null || order.getId() == null) {
            return;
        }
        List<ProductionBatch> batches = batchRepository.findTreeByOrderId(order.getId());
        order.setBatchEnabled(!batches.isEmpty());
        order.setBatches(batches.isEmpty() ? null : enrichBatches(order.getId(), batches));
    }

    @Transactional(readOnly = true)
    public List<WorkOrder> enrichAll(List<WorkOrder> orders) {
        for (WorkOrder o : orders) {
            enrich(o);
        }
        return orders;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> kitCheck(Long batchId) {
        ProductionBatch batch = getLockedExisting(batchId);
        WorkOrder order = batch.getOrder();
        List<ShortageLine> shortages = orderRepository.findBatchShortageLines(order.getId(), batch.getPlanQty());
        int total = bomRepository.findByProductModel(order.getProductModel()).size();
        double rate = total == 0 ? 0.0
                : Math.round(((total - shortages.size()) * 1000.0 / total)) / 10.0;

        Map<String, Object> out = new HashMap<>();
        out.put("batchId", batch.getId());
        out.put("batchNo", batch.getBatchNo());
        out.put("orderId", order.getId());
        out.put("orderNo", order.getOrderNo());
        out.put("planQty", batch.getPlanQty());
        out.put("bomLines", total);
        out.put("shortageLines", shortages.size());
        out.put("kitRate", rate);
        out.put("shortages", shortages);
        out.put("ok", shortages.isEmpty() && rate >= Dict.MIN_KIT_RATE);
        return out;
    }

    // ---------------- 拆分 ----------------

    @Transactional
    public List<ProductionBatch> split(Long orderId, BatchSplitRequest req) {
        WorkOrder order = lockOrder(orderId);
        if (!"待投产".equals(order.getStatus())) {
            throw new BizException("只有「待投产」工单能首次分批，当前状态：" + order.getStatus());
        }
        List<ProductionBatch> existing = batchRepository.findTreeByOrderId(orderId);
        if (!existing.isEmpty()) {
            throw new BizException("工单已启用分批，请调整未投产批次，不能重复生成方案");
        }
        if (req == null || req.getBatches() == null || req.getBatches().isEmpty()) {
            throw new BizException("分批方案不能为空");
        }

        String operator = operator(req.getOperator());
        int historicalQty = n(order.getDoneQty());
        if (historicalQty > 0 && repairTaskRepository.findOpenByOrderId(orderId).size() > 0) {
            throw new BizException("仍有历史返修任务未闭环，不能把原有完成数量迁移到只读历史批次");
        }
        int remaining = requirePositive(order.getPlanQty() - historicalQty,
                "可拆分给新批次的剩余数量必须大于 0；历史完成工单请继续按原流程查看");

        List<ProductionBatch> created = new ArrayList<>();
        Set<String> nos = new HashSet<>();
        Set<Integer> seqs = new HashSet<>();
        int sum = 0;
        int index = 1;
        for (BatchSplitRequest.Line line : req.getBatches()) {
            if (line == null) {
                throw new BizException("第 " + index + " 行为空批次，整份方案不能保存");
            }
            if (line.getPlanQty() == null || line.getPlanQty() <= 0) {
                throw new BizException("第 " + index + " 行为空批次或计划数量不合法，整份方案不能保存");
            }
            String batchNo = line.getBatchNo() == null || line.getBatchNo().isBlank()
                    ? defaultBatchNo(order.getOrderNo(), index) : line.getBatchNo().trim();
            if (batchNo.length() > 36 || batchNo.endsWith("-H") || !nos.add(batchNo)
                    || batchRepository.findByOrderIdAndBatchNo(orderId, batchNo).isPresent()) {
                throw new BizException("批次号为空、重复、保留（-H 为历史批次）或已存在：" + batchNo);
            }
            int seq = line.getDeliverySeq() == null ? index : line.getDeliverySeq();
            if (seq <= 0 || !seqs.add(seq)) {
                throw new BizException("交付顺序必须为正整数且不能重复：" + seq);
            }
            sum += line.getPlanQty();

            ProductionBatch b = baseBatch(order, batchNo, line.getPlanQty(), seq, "待投产");
            b.setReadonlyHistory(false);
            created.add(batchRepository.save(b));
            index++;
        }
        if (sum != remaining) {
            throw new BizException("数量不守恒：新批次合计 " + sum + " 板，加上历史完成 "
                    + historicalQty + " 板后必须等于母工单计划 " + order.getPlanQty() + " 板");
        }

        ProductionBatch history = null;
        if (historicalQty > 0) {
            history = baseBatch(order, order.getOrderNo() + "-H", historicalQty, null, "历史");
            history.setReadonlyHistory(true);
            history.setReportedQty(historicalQty);
            history.setInspectedQty(historicalQty);
            history.setCompletedQty(historicalQty);
            history.setFinishedAt(LocalDateTime.now());
            history = batchRepository.save(history);
            migrateLegacyProduction(order, history);
            event(history, "历史迁移", null, historicalQty, null, operator,
                    "首次启用分批：原有完成数量 " + historicalQty + " 板落入只读历史批次，不与新批次重复累计");
        }
        for (ProductionBatch b : created) {
            event(b, "拆分", null, b.getPlanQty(), null, operator, "首批分批方案建立");
        }

        recalculateOrder(order);
        return listByOrder(order.getId());
    }

    // ---------------- 生产前调整 / 删除 / 取消 ----------------

    @Transactional
    public ProductionBatch adjust(Long batchId, BatchAdjustRequest req) {
        ProductionBatch probe = getExisting(batchId);
        WorkOrder order = lockOrder(probe.getOrder().getId());
        ProductionBatch batch = lockBatch(batchId);
        assertMutableBeforeProduction(batch);
        if (req == null) {
            throw new BizException("调整内容不能为空");
        }
        String operator = operator(req.getOperator());
        int before = batch.getPlanQty();
        int oldSeq = batch.getDeliverySeq();

        if (req.getPlanQty() != null) {
            if (req.getPlanQty() <= 0) {
                throw new BizException("批次计划数量必须大于 0");
            }
            List<ProductionBatch> others = activeBatches(order.getId()).stream()
                    .filter(b -> !b.getId().equals(batchId)).toList();
            int otherSum = others.stream().mapToInt(ProductionBatch::getPlanQty).sum();
            if (otherSum + req.getPlanQty() != order.getPlanQty()) {
                throw new BizException("调整后有效批次合计为 " + (otherSum + req.getPlanQty())
                        + " 板，不等于母工单计划 " + order.getPlanQty() + " 板，不能保存");
            }
            batch.setPlanQty(req.getPlanQty());
        }
        if (req.getDeliverySeq() != null) {
            if (req.getDeliverySeq() <= 0) {
                throw new BizException("交付顺序必须大于 0");
            }
            boolean duplicate = activeBatches(order.getId()).stream()
                    .anyMatch(b -> !b.getId().equals(batchId)
                            && b.getDeliverySeq().equals(req.getDeliverySeq()));
            if (duplicate) {
                throw new BizException("交付顺序与其他批次重复：" + req.getDeliverySeq());
            }
            batch.setDeliverySeq(req.getDeliverySeq());
        }
        batch = batchRepository.save(batch);
        assertConservation(order);
        event(batch, "调整", before, batch.getPlanQty(), null, operator,
                "交付顺序 " + oldSeq + " → " + batch.getDeliverySeq()
                        + (req.getReason() == null || req.getReason().isBlank() ? "" : "；" + req.getReason().trim()));
        recalculateOrder(order);
        return enrich(batch);
    }

    @Transactional
    public List<ProductionBatch> delete(Long batchId, Long recycleToBatchId, String operatorName) {
        ProductionBatch probe = getExisting(batchId);
        WorkOrder order = lockOrder(probe.getOrder().getId());
        ProductionBatch batch = lockBatch(batchId);
        assertMutableBeforeProduction(batch);
        if (recycleToBatchId == null) {
            throw new BizException("删除批次必须指定一个未投产批次接收数量；若要减产请使用取消并由计划员确认");
        }
        if (recycleToBatchId.equals(batchId)) {
            throw new BizException("不能把批次数量回收到本批次");
        }
        ProductionBatch target = lockBatch(recycleToBatchId);
        if (!target.getOrder().getId().equals(order.getId()) || !"待投产".equals(target.getStatus())
                || Boolean.TRUE.equals(target.getReadonlyHistory())) {
            throw new BizException("接收数量的目标批次必须是同一母工单下的未投产批次");
        }

        int qty = batch.getPlanQty();
        int targetBefore = target.getPlanQty();
        target.setPlanQty(targetBefore + qty);
        target = batchRepository.save(target);

        String batchNo = batch.getBatchNo();
        event(null, order.getId(), batchNo, "删除", qty, null, target, operator(operatorName),
                "删除未投产批次，" + qty + " 板回收到 " + target.getBatchNo());
        event(target, "回收", targetBefore, target.getPlanQty(), null, operator(operatorName),
                "接收删除批次 " + batchNo + " 的 " + qty + " 板");
        batchRepository.delete(batch);
        batchRepository.flush();

        assertConservation(order);
        recalculateOrder(order);
        return listByOrder(order.getId());
    }

    @Transactional
    public List<ProductionBatch> cancel(Long batchId, BatchCancelRequest req) {
        ProductionBatch probe = getExisting(batchId);
        WorkOrder order = lockOrder(probe.getOrder().getId());
        ProductionBatch batch = lockBatch(batchId);
        assertMutableBeforeProduction(batch);
        if (req == null) {
            throw new BizException("取消批次必须回收剩余数量，或由计划员确认减产");
        }
        String operator = operator(req.getOperator());
        String reason = req.getReason() == null || req.getReason().isBlank() ? "计划员取消" : req.getReason().trim();
        int qty = batch.getPlanQty();
        ProductionBatch target = null;

        if (req.getRecycleToBatchId() != null) {
            if (req.getRecycleToBatchId().equals(batchId)) {
                throw new BizException("不能把取消数量回收到本批次");
            }
            target = lockBatch(req.getRecycleToBatchId());
            if (!target.getOrder().getId().equals(order.getId()) || !"待投产".equals(target.getStatus())
                    || Boolean.TRUE.equals(target.getReadonlyHistory())) {
                throw new BizException("取消数量只能回收到同一母工单的未投产批次");
            }
            int before = target.getPlanQty();
            target.setPlanQty(before + qty);
            target = batchRepository.save(target);
        } else if (!Boolean.TRUE.equals(req.getConfirmReduction())) {
            throw new BizException("取消批次的 " + qty + " 板必须回收到未投产批次，或勾选计划员确认减产");
        } else {
            int newPlan = order.getPlanQty() - qty;
            if (newPlan < n(order.getDoneQty())) {
                throw new BizException("减产后计划 " + newPlan + " 板不能小于已完成 " + order.getDoneQty() + " 板");
            }
            order.setPlanQty(newPlan);
            orderRepository.save(order);
        }

        batch.setStatus("已取消");
        batch.setCancelReason(reason);
        batch = batchRepository.save(batch);
        event(batch, "取消", qty, 0, target, operator, reason);
        if (target != null) {
            event(target, "回收", null, target.getPlanQty(), batch, operator,
                    "接收取消批次 " + batch.getBatchNo() + " 的 " + qty + " 板");
        } else {
            event(batch, "减产", qty, 0, null, operator,
                    "计划员确认母工单减产 " + qty + " 板，新计划 " + order.getPlanQty() + " 板");
        }

        assertConservation(order);
        recalculateOrder(order);
        return listByOrder(order.getId());
    }

    // ---------------- 批次独立生产 ----------------

    @Transactional
    public ProductionBatch releaseBatch(Long batchId) {
        ProductionBatch probe = getExisting(batchId);
        WorkOrder order = lockOrder(probe.getOrder().getId());
        ProductionBatch batch = lockBatch(batchId);
        if (!"待投产".equals(batch.getStatus()) || Boolean.TRUE.equals(batch.getReadonlyHistory())) {
            throw new BizException("只有未投产的新批次能投产，当前状态：" + batch.getStatus());
        }
        Map<String, Object> kit = kitCheck(batchId);
        if (Boolean.FALSE.equals(kit.get("ok"))) {
            throw new BizException("批次齐套不通过：缺料 " + kit.get("shortageLines") + " 项，齐套率 "
                    + kit.get("kitRate") + "%（需 ≥ " + Dict.MIN_KIT_RATE + "%）");
        }
        batch.setStatus("贴片中");
        batch.setReleasedAt(LocalDateTime.now());
        batch = batchRepository.save(batch);

        for (String stepName : Dict.STEPS) {
            if (stepRepository.findByBatchIdAndStepName(batchId, stepName).isEmpty()) {
                SmtStep step = new SmtStep();
                step.setOrder(order);
                step.setBatch(batch);
                step.setStepName(stepName);
                step.setStatus("待执行");
                stepRepository.save(step);
            }
        }
        ensureOrderConfig(order);
        event(batch, "调整", null, null, null, "系统", "批次齐套通过并投产，五道 SMT 工序已铺开");
        recalculateOrder(order);
        return enrich(batch);
    }

    @Transactional
    public ProductionBatch report(Long batchId, Integer qty) {
        if (qty == null || qty <= 0) {
            throw new BizException("报工数量必须大于 0");
        }
        ProductionBatch probe = getExisting(batchId);
        lockOrder(probe.getOrder().getId());
        ProductionBatch batch = lockBatch(batchId);
        if (!"贴片中".equals(batch.getStatus())) {
            throw new BizException("只有「贴片中」的批次能报工，当前状态：" + batch.getStatus());
        }
        int next = n(batch.getReportedQty()) + qty;
        if (next > batch.getPlanQty()) {
            throw new BizException("批次报工后 " + next + " 板超过计划 " + batch.getPlanQty() + " 板");
        }
        batch.setReportedQty(next);
        return enrich(batchRepository.save(batch));
    }

    @Transactional
    public ProductionBatch transfer(Long batchId, String target) {
        if (!Dict.isValidOrderStatus(target) || "已取消".equals(target)) {
            throw new BizException("制造批次状态不合法：" + target);
        }
        ProductionBatch probe = getExisting(batchId);
        WorkOrder order = lockOrder(probe.getOrder().getId());
        ProductionBatch batch = lockBatch(batchId);
        if (Boolean.TRUE.equals(batch.getReadonlyHistory()) || "历史".equals(batch.getStatus())) {
            throw new BizException("只读历史批次不能调整状态");
        }
        if (!Dict.canTransferBatch(batch.getStatus(), target)) {
            throw new BizException("批次状态只能逐级推进，不允许从「" + batch.getStatus() + "」到「" + target + "」");
        }

        if ("待检验".equals(target)) {
            assertStepsFinished(batch);
            if (n(batch.getReportedQty()) != batch.getPlanQty()) {
                throw new BizException("批次还有 " + (batch.getPlanQty() - n(batch.getReportedQty()))
                        + " 板未报工，不能整批送检");
            }
            if (repairTaskRepository.findOpenByBatchId(batchId).size() > 0) {
                throw new BizException("批次仍有返修任务未复验闭环，不能送检");
            }
            batch.setInspectedQty(batch.getReportedQty());
        } else if ("已完成".equals(target)) {
            assertStepsFinished(batch);
            if (n(batch.getInspectedQty()) != batch.getPlanQty()) {
                throw new BizException("批次尚未整批送检，不能完成");
            }
            if (repairTaskRepository.findOpenByBatchId(batchId).size() > 0) {
                throw new BizException("批次仍有返修任务未复验闭环，不能完成");
            }
            if (inspectionRepository.findByBatchIdOrderByIdDesc(batchId).isEmpty()) {
                throw new BizException("批次尚未登记质检记录，不能完成");
            }
            batch.setCompletedQty(batch.getInspectedQty());
            batch.setFinishedAt(LocalDateTime.now());
        }
        batch.setStatus(target);
        batch = batchRepository.save(batch);
        event(batch, "调整", null, null, null, "系统", "批次状态推进为「" + target + "」");
        recalculateOrder(order);
        return enrich(batch);
    }

    // ---------------- 供其他服务使用的断言 / 汇总 ----------------

    public ProductionBatch requireBatchOfOrder(Long orderId, Long batchId) {
        if (batchId == null) {
            throw new BizException("该工单已启用分批，必须指定制造批次");
        }
        ProductionBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new BizException("制造批次不存在：" + batchId));
        if (!batch.getOrder().getId().equals(orderId)) {
            throw new BizException("批次不属于该工单");
        }
        return batch;
    }

    @Transactional(readOnly = true)
    public int countOpenRepairs(Long batchId) {
        return repairTaskRepository.findOpenByBatchId(batchId).size();
    }

    /** 重新按批次汇总母工单完成数量、状态和交付进度。 */
    @Transactional
    public WorkOrder recalculateOrder(WorkOrder input) {
        WorkOrder order = input == null || input.getId() == null ? input : orderRepository.findById(input.getId()).orElse(input);
        if (order == null) return null;
        List<ProductionBatch> batches = batchRepository.findTreeByOrderId(order.getId());
        if (batches.isEmpty()) {
            return order;
        }
        int done = batches.stream().mapToInt(b -> n(b.getCompletedQty())).sum();
        order.setDoneQty(done);

        List<ProductionBatch> active = batches.stream()
                .filter(b -> !"已取消".equals(b.getStatus()) && !"历史".equals(b.getStatus()))
                .toList();
        boolean hasCanceled = batches.stream().anyMatch(b -> "已取消".equals(b.getStatus()));
        if (active.isEmpty()) {
            if (order.getPlanQty() > 0 && n(order.getDoneQty()) >= order.getPlanQty()) {
                order.setStatus("已完成");
            } else if (hasCanceled) {
                order.setStatus("已取消");
            }
        } else if (active.stream().allMatch(b -> "已完成".equals(b.getStatus()))) {
            if (done != order.getPlanQty()) {
                throw new BizException("全部批次已完成但完成数量 " + done
                        + " 板不等于母工单计划 " + order.getPlanQty() + " 板，禁止汇总为已完成");
            }
            order.setStatus("已完成");
        } else if (active.stream().anyMatch(b -> "待检验".equals(b.getStatus()))) {
            order.setStatus("待检验");
        } else if (active.stream().anyMatch(b -> "贴片中".equals(b.getStatus()))
                || (active.stream().anyMatch(b -> "已完成".equals(b.getStatus()))
                    && active.stream().anyMatch(b -> "待投产".equals(b.getStatus())))) {
            order.setStatus("贴片中");
        } else {
            order.setStatus("待投产");
        }
        return orderRepository.save(order);
    }

    // ---------------- 内部 ----------------

    private List<ProductionBatch> enrichBatches(Long orderId, List<ProductionBatch> batches) {
        if (batches.isEmpty()) {
            return batches;
        }
        WorkOrder order = batches.get(0).getOrder();
        int bomCount = bomRepository.findByProductModel(order.getProductModel()).size();
        List<SmtStep> steps = stepRepository.findByOrderIdOrderById(orderId);
        Map<Long, List<SmtStep>> stepMap = new HashMap<>();
        for (SmtStep s : steps) {
            if (s.getBatch() != null) {
                stepMap.computeIfAbsent(s.getBatch().getId(), k -> new ArrayList<>()).add(s);
            }
        }
        List<BatchAdjustmentEvent> allEvents = eventRepository.findByOrderIdOrderByIdAsc(orderId);
        Map<Long, List<BatchAdjustmentEvent>> eventMap = new HashMap<>();
        for (BatchAdjustmentEvent e : allEvents) {
            if (e.getBatch() != null) {
                eventMap.computeIfAbsent(e.getBatch().getId(), k -> new ArrayList<>()).add(e);
            }
        }

        for (ProductionBatch b : batches) {
            List<SmtStep> bs = stepMap.getOrDefault(b.getId(), List.of());
            int doneSteps = (int) bs.stream().filter(s -> "已完成".equals(s.getStatus())).count();
            b.setStepDoneCount(doneSteps);
            b.setStepTotalCount(bs.isEmpty() ? 0 : Dict.STEPS.size());
            if (Boolean.TRUE.equals(b.getReadonlyHistory())) {
                b.setKitRate(100.0);
                b.setKitOk(true);
            } else if (bomCount == 0) {
                b.setKitRate(0.0);
                b.setKitOk(false);
            } else {
                List<ShortageLine> shortages = orderRepository.findBatchShortageLines(orderId, b.getPlanQty());
                double rate = Math.round(((bomCount - shortages.size()) * 1000.0 / bomCount)) / 10.0;
                b.setKitRate(rate);
                b.setKitOk(shortages.isEmpty() && rate >= Dict.MIN_KIT_RATE);
            }
            List<BatchAdjustmentEvent> es = eventMap.getOrDefault(b.getId(), new ArrayList<>());
            es.sort(Comparator.comparing(BatchAdjustmentEvent::getId));
            b.setEvents(es);
        }
        return batches;
    }

    private ProductionBatch enrich(ProductionBatch batch) {
        List<ProductionBatch> one = enrichBatches(batch.getOrder().getId(), new ArrayList<>(List.of(batch)));
        return one.get(0);
    }

    private void migrateLegacyProduction(WorkOrder order, ProductionBatch history) {
        List<SmtStep> oldSteps = stepRepository.findByOrderIdOrderById(order.getId());
        Map<String, SmtStep> byName = new HashMap<>();
        for (SmtStep s : oldSteps) {
            if (s.getBatch() == null) {
                s.setBatch(history);
                byName.put(s.getStepName(), s);
            }
        }
        for (String name : Dict.STEPS) {
            SmtStep s = byName.get(name);
            if (s == null) {
                s = new SmtStep();
                s.setOrder(order);
                s.setBatch(history);
                s.setStepName(name);
                byName.put(name, s);
            }
            s.setStatus("已完成");
            if (s.getStartedAt() == null) s.setStartedAt(LocalDateTime.now());
            s.setFinishedAt(LocalDateTime.now());
            stepRepository.save(s);
        }
        for (Inspection i : inspectionRepository.findByOrderId(order.getId())) {
            if (i.getBatch() == null) {
                i.setBatch(history);
                inspectionRepository.save(i);
            }
        }
        for (RepairTask t : repairTaskRepository.findByOrderIdOrderByIdDesc(order.getId())) {
            if (t.getBatch() == null) {
                t.setBatch(history);
                repairTaskRepository.save(t);
            }
        }
    }

    private void assertStepsFinished(ProductionBatch batch) {
        List<SmtStep> steps = stepRepository.findByBatchIdOrderById(batch.getId());
        if (steps.size() != Dict.STEPS.size()) {
            throw new BizException("批次尚未铺开五道 SMT 工序");
        }
        for (String name : Dict.STEPS) {
            SmtStep s = stepRepository.findByBatchIdAndStepName(batch.getId(), name)
                    .orElseThrow(() -> new BizException("工序不存在：" + name));
            if (!"已完成".equals(s.getStatus())) {
                throw new BizException("工序「" + name + "」尚未完成，不能推进批次");
            }
        }
    }

    private void assertMutableBeforeProduction(ProductionBatch batch) {
        if (Boolean.TRUE.equals(batch.getReadonlyHistory()) || "历史".equals(batch.getStatus())) {
            throw new BizException("只读历史批次不能调整或删除");
        }
        if (batch.isStarted()) {
            throw new BizException("批次已开始生产，只能调整其他未投产批次；已报工、已送检或已完成数量不得挪走");
        }
        if (!"待投产".equals(batch.getStatus())) {
            throw new BizException("只有未投产批次能调整，当前状态：" + batch.getStatus());
        }
    }

    private void assertConservation(WorkOrder order) {
        List<ProductionBatch> batches = batchRepository.findTreeByOrderId(order.getId());
        int effective = batches.stream()
                .filter(b -> Dict.isQuantityEffectiveBatchStatus(b.getStatus()))
                .mapToInt(ProductionBatch::getPlanQty).sum();
        if (effective != order.getPlanQty()) {
            throw new BizException("数量守恒校验失败：有效批次合计 " + effective
                    + " 板，母工单计划 " + order.getPlanQty() + " 板");
        }
    }

    private List<ProductionBatch> activeBatches(Long orderId) {
        return batchRepository.findTreeByOrderId(orderId).stream()
                .filter(b -> Dict.isQuantityEffectiveBatchStatus(b.getStatus())).toList();
    }

    private ProductionBatch baseBatch(WorkOrder order, String no, int qty, Integer seq, String status) {
        ProductionBatch b = new ProductionBatch();
        b.setOrder(order);
        b.setBatchNo(no);
        b.setPlanQty(qty);
        b.setReportedQty(0);
        b.setInspectedQty(0);
        b.setCompletedQty(0);
        b.setDeliverySeq(seq);
        b.setStatus(status);
        b.setReadonlyHistory(false);
        b.setCreatedAt(LocalDateTime.now());
        return b;
    }

    private void ensureOrderConfig(WorkOrder order) {
        if (configRepository.findById(order.getId()).isPresent()) {
            return;
        }
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

    private String defaultBatchNo(String orderNo, int index) {
        return orderNo + "-B" + String.format("%02d", index);
    }

    private int requirePositive(int v, String msg) {
        if (v <= 0) {
            throw new BizException(msg);
        }
        return v;
    }

    private WorkOrder getOrder(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new BizException("工单不存在：" + id));
    }

    private ProductionBatch getExisting(Long id) {
        return batchRepository.findById(id).orElseThrow(() -> new BizException("制造批次不存在：" + id));
    }

    private ProductionBatch getLockedExisting(Long id) {
        ProductionBatch b = getExisting(id);
        // 触发懒加载 order，供未加锁的只读校验使用。
        b.getOrder().getOrderNo();
        return b;
    }

    private WorkOrder lockOrder(Long id) {
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("工单不存在：" + id));
    }

    private ProductionBatch lockBatch(Long id) {
        return batchRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("制造批次不存在：" + id));
    }

    private String operator(String v) {
        if (v == null || v.isBlank()) {
            throw new BizException("操作员 / 计划员不能为空");
        }
        return v.trim();
    }

    private void event(ProductionBatch batch, String action, Integer before, Integer after,
                       ProductionBatch related, String operator, String detail) {
        Long orderId = batch == null ? null : batch.getOrder().getId();
        String batchNo = batch == null ? null : batch.getBatchNo();
        event(batch, orderId, batchNo, action, before, after, related, operator, detail);
    }

    private void event(ProductionBatch batch, Long orderId, String batchNo, String action,
                       Integer before, Integer after, ProductionBatch related,
                       String operator, String detail) {
        BatchAdjustmentEvent e = new BatchAdjustmentEvent();
        e.setOrderId(orderId);
        e.setBatch(batch);
        e.setBatchNo(batchNo);
        e.setAction(action);
        e.setBeforeQty(before);
        e.setAfterQty(after);
        if (related != null) {
            e.setRelatedBatchId(related.getId());
            e.setRelatedBatchNo(related.getBatchNo());
        }
        e.setOperator(operator);
        e.setDetail(detail);
        e.setCreatedAt(LocalDateTime.now());
        eventRepository.save(e);
    }

    private int n(Integer v) {
        return v == null ? 0 : v;
    }
}
