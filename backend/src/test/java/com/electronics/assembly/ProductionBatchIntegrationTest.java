package com.electronics.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.dto.BatchAdjustRequest;
import com.electronics.assembly.dto.BatchCancelRequest;
import com.electronics.assembly.dto.BatchSplitRequest;
import com.electronics.assembly.dto.RepairTaskRequest;
import com.electronics.assembly.entity.BatchAdjustmentEvent;
import com.electronics.assembly.entity.BomItem;
import com.electronics.assembly.entity.Component;
import com.electronics.assembly.entity.Inspection;
import com.electronics.assembly.entity.ProductionBatch;
import com.electronics.assembly.entity.RepairTask;
import com.electronics.assembly.entity.SmtStep;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.repository.BatchAdjustmentEventRepository;
import com.electronics.assembly.repository.BomItemRepository;
import com.electronics.assembly.repository.ComponentRepository;
import com.electronics.assembly.repository.ProductionBatchRepository;
import com.electronics.assembly.service.InspectionService;
import com.electronics.assembly.service.ProductionBatchService;
import com.electronics.assembly.service.RepairTaskService;
import com.electronics.assembly.service.SmtStepService;

/**
 * 工单分批生产集成测试：数量守恒、生产后调整限制、母子状态汇总、
 * 取消回收/计划员确认减产、首次分批的历史迁移。
 */
class ProductionBatchIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ProductionBatchService batchService;
    @Autowired private ProductionBatchRepository batchRepository;
    @Autowired private BatchAdjustmentEventRepository eventRepository;
    @Autowired private BomItemRepository bomRepository;
    @Autowired private ComponentRepository componentRepository;
    @Autowired private SmtStepService stepService;
    @Autowired private InspectionService inspectionService;
    @Autowired private RepairTaskService repairTaskService;

    private String model;

    @BeforeEach
    void setUp() {
        model = "MB-" + UUID.randomUUID().substring(0, 8);
        Component c = new Component();
        c.setPartNo("P" + UUID.randomUUID().substring(0, 8));
        c.setPartName("测试物料");
        c.setPackageType("0402");
        c.setStockQty(100000);
        c.setStatus("正常");
        componentRepository.save(c);

        BomItem b = new BomItem();
        b.setProductModel(model);
        b.setPartNo(c.getPartNo());
        b.setUnitQty(1);
        bomRepository.save(b);
    }

    @Test
    void split_rejects_short_over_and_empty_batches_but_saves_when_quantities_conserve() {
        WorkOrder order = newOrder(model, 100, 0, "待投产");

        BatchSplitRequest shortPlan = splitReq(List.of(line(30), line(60)));
        assertThatThrownBy(() -> batchService.split(order.getId(), shortPlan))
                .isInstanceOf(BizException.class).hasMessageContaining("数量不守恒");
        BatchSplitRequest overPlan = splitReq(List.of(line(60), line(50)));
        assertThatThrownBy(() -> batchService.split(order.getId(), overPlan))
                .isInstanceOf(BizException.class).hasMessageContaining("数量不守恒");
        BatchSplitRequest empty = splitReq(List.of(line(0), line(100)));
        assertThatThrownBy(() -> batchService.split(order.getId(), empty))
                .isInstanceOf(BizException.class).hasMessageContaining("空批次");
        assertThat(batchRepository.findByOrderIdOrderByDeliverySeqAscIdAsc(order.getId())).isEmpty();

        List<ProductionBatch> batches = batchService.split(order.getId(),
                splitReq(List.of(line(60), line(40))));
        assertThat(batches).hasSize(2);
        assertThat(batches).extracting(ProductionBatch::getPlanQty).containsExactly(60, 40);
        assertThat(batches).extracting(ProductionBatch::getDeliverySeq).containsExactly(1, 2);
        assertThat(batches).allMatch(b -> b.getKitRate() == 100.0 && b.getKitOk());
        assertThat(eventRepository.findByOrderIdOrderByIdAsc(order.getId()))
                .extracting(BatchAdjustmentEvent::getAction).containsOnly("拆分");
    }

    @Test
    void after_production_started_only_unreleased_batches_can_change_and_produced_quantity_is_locked() {
        WorkOrder order = newOrder(model, 100, 0, "待投产");
        List<ProductionBatch> batches = batchService.split(order.getId(), splitReq(List.of(line(60), line(40))));
        ProductionBatch first = batches.get(0);
        ProductionBatch second = batches.get(1);

        batchService.releaseBatch(first.getId());
        runStepFlow(first);
        batchService.report(first.getId(), 60);
        batchService.transfer(first.getId(), "待检验");

        BatchAdjustRequest adjustStarted = new BatchAdjustRequest();
        adjustStarted.setPlanQty(50);
        adjustStarted.setDeliverySeq(1);
        adjustStarted.setOperator("计划员");
        assertThatThrownBy(() -> batchService.adjust(first.getId(), adjustStarted))
                .isInstanceOf(BizException.class).hasMessageContaining("已开始生产");
        assertThatThrownBy(() -> batchService.cancel(first.getId(), cancel(null, false, "取消在制")))
                .isInstanceOf(BizException.class).hasMessageContaining("已开始生产");

        BatchAdjustRequest adjustPending = new BatchAdjustRequest();
        adjustPending.setPlanQty(40);
        adjustPending.setDeliverySeq(2);
        adjustPending.setOperator("计划员");
        ProductionBatch changed = batchService.adjust(second.getId(), adjustPending);
        assertThat(changed.getPlanQty()).isEqualTo(40);
        assertThat(batchRepository.findById(first.getId()).orElseThrow().getReportedQty()).isEqualTo(60);
    }

    @Test
    void batch_processes_independent_steps_inspection_repair_and_rolls_up_to_mother_order() {
        WorkOrder order = newOrder(model, 100, 0, "待投产");
        List<ProductionBatch> batches = batchService.split(order.getId(), splitReq(List.of(line(60), line(40))));
        ProductionBatch first = batches.get(0);
        ProductionBatch second = batches.get(1);

        batchService.releaseBatch(first.getId());
        runStepFlow(first);
        batchService.report(first.getId(), 60);
        batchService.transfer(first.getId(), "待检验");
        registerPassInspection(first);
        batchService.transfer(first.getId(), "已完成");

        WorkOrder mother = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(mother.getDoneQty()).isEqualTo(60);
        assertThat(mother.getProgressRate()).isEqualTo(60.0);
        assertThat(mother.getStatus()).isEqualTo("贴片中");
        assertThatThrownBy(() -> batchService.transfer(second.getId(), "已完成"))
                .isInstanceOf(BizException.class).hasMessageContaining("逐级推进");

        batchService.releaseBatch(second.getId());
        runStepFlow(second);

        // 第二批次 AOI 出现返修，不影响第一批次已完成状态和第二批次其他工序归属
        Inspection input = inspectionInput(order.getId(), "AOI", 5, "孙悦", UUID.randomUUID().toString());
        input.setBatchId(second.getId());
        Inspection insp = inspectionService.create(input);
        RepairTask task = repairTaskService.findByInspection(insp.getId());
        assertThat(task.getBatchId()).isEqualTo(second.getId());
        assertThat(repairTaskService.countOpenByOrder(order.getId())).isEqualTo(1);
        assertThat(batchService.countOpenRepairs(first.getId())).isZero();
        assertThat(stepRepository.findByBatchIdAndStepName(second.getId(), "AOI").orElseThrow().getStatus())
                .isEqualTo("返修中");
        assertThat(stepRepository.findByBatchIdAndStepName(first.getId(), "AOI").orElseThrow().getStatus())
                .isEqualTo("已完成");

        RepairTaskRequest repairRequest = new RepairTaskRequest();
        repairRequest.setRepairBy("李返修");
        repairTaskService.complete(task.getId(), repairRequest);
        RepairTaskRequest recheck = new RepairTaskRequest();
        recheck.setInspector("孙悦");
        recheck.setResult("合格");
        repairTaskService.recheck(task.getId(), recheck);

        batchService.report(second.getId(), 40);
        batchService.transfer(second.getId(), "待检验");
        registerPassInspection(second);
        batchService.transfer(second.getId(), "已完成");
        mother = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(mother.getDoneQty()).isEqualTo(100);
        assertThat(mother.getStatus()).isEqualTo("已完成");
    }

    @Test
    void cancel_recycles_remaining_quantity_or_requires_planner_reduction_confirmation() {
        WorkOrder order = newOrder(model, 100, 0, "待投产");
        List<ProductionBatch> batches = batchService.split(order.getId(), splitReq(List.of(line(60), line(40))));
        ProductionBatch canceled = batches.get(0);
        ProductionBatch target = batches.get(1);

        assertThatThrownBy(() -> batchService.cancel(canceled.getId(), cancel(null, false, "未选择去向")))
                .isInstanceOf(BizException.class).hasMessageContaining("回收");
        List<ProductionBatch> afterRecycle = batchService.cancel(canceled.getId(),
                cancel(target.getId(), false, "客户减量，先回收"));
        assertThat(afterRecycle).extracting(ProductionBatch::getStatus).contains("已取消", "待投产");
        assertThat(batchRepository.findById(target.getId()).orElseThrow().getPlanQty()).isEqualTo(100);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPlanQty()).isEqualTo(100);

        List<ProductionBatch> reduced = batchService.cancel(target.getId(),
                cancel(null, true, "计划员确认剩余批次减产"));
        assertThat(reduced).extracting(ProductionBatch::getStatus).containsExactly("已取消", "已取消");
        WorkOrder reducedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reducedOrder.getPlanQty()).isZero();
        assertThat(reducedOrder.getStatus()).isEqualTo("已取消");
        assertThat(eventRepository.findByOrderIdOrderByIdAsc(order.getId()))
                .extracting(BatchAdjustmentEvent::getAction).contains("取消", "回收", "减产");
    }

    @Test
    void first_split_migrates_existing_done_quantity_into_readonly_history_without_double_counting() {
        WorkOrder legacy = newOrder(model, 100, 30, "待投产");
        for (String name : com.electronics.assembly.common.Dict.STEPS) {
            SmtStep s = new SmtStep();
            s.setOrder(legacy);
            s.setStepName(name);
            s.setStatus("已完成");
            stepRepository.save(s);
        }
        Inspection legacyInsp = inspectionInput(legacy.getId(), "AOI", 0, "孙悦", null);
        legacyInsp.setVerdict("合格");
        legacyInsp.setRepairDone(true);
        legacyInsp = inspectionService.create(legacyInsp);

        List<ProductionBatch> batches = batchService.split(legacy.getId(), splitReq(List.of(line(70))));
        ProductionBatch history = batchRepository.findFirstByOrderIdAndReadonlyHistoryTrue(legacy.getId()).orElseThrow();
        ProductionBatch fresh = batches.stream().filter(b -> !b.getReadonlyHistory()).findFirst().orElseThrow();

        assertThat(history.getBatchNo()).isEqualTo(legacy.getOrderNo() + "-H");
        assertThat(history.getPlanQty()).isEqualTo(30);
        assertThat(history.getCompletedQty()).isEqualTo(30);
        assertThat(history.getStatus()).isEqualTo("历史");
        assertThat(fresh.getPlanQty()).isEqualTo(70);
        WorkOrder mother = orderRepository.findById(legacy.getId()).orElseThrow();
        assertThat(mother.getDoneQty()).isEqualTo(30);
        assertThat(mother.getPlanQty()).isEqualTo(100);
        assertThat(stepRepository.findByBatchIdAndStepName(history.getId(), "AOI")).isPresent();
        assertThat(inspectionService.get(legacyInsp.getId()).getBatchId()).isEqualTo(history.getId());
        assertThatThrownBy(() -> batchService.releaseBatch(history.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("只读历史");
    }

    private WorkOrder newOrder(String productModel, int plan, int done, String status) {
        WorkOrder o = new WorkOrder();
        o.setOrderNo("WOB" + UUID.randomUUID().substring(0, 8));
        o.setProductModel(productModel);
        o.setPlanQty(plan);
        o.setDoneQty(done);
        o.setPriority("中");
        o.setStatus(status);
        return orderRepository.save(o);
    }

    private BatchSplitRequest.Line line(int qty) {
        BatchSplitRequest.Line l = new BatchSplitRequest.Line();
        l.setPlanQty(qty);
        return l;
    }

    private BatchSplitRequest splitReq(List<BatchSplitRequest.Line> lines) {
        BatchSplitRequest req = new BatchSplitRequest();
        req.setOperator("计划员");
        req.setBatches(lines);
        return req;
    }

    private BatchCancelRequest cancel(Long targetId, boolean reduction, String reason) {
        BatchCancelRequest req = new BatchCancelRequest();
        req.setRecycleToBatchId(targetId);
        req.setConfirmReduction(reduction);
        req.setOperator("计划员");
        req.setReason(reason);
        return req;
    }

    private void registerPassInspection(ProductionBatch batch) {
        Inspection input = inspectionInput(batch.getOrderId(), "目检", 0, "孙悦", UUID.randomUUID().toString());
        input.setBatchId(batch.getId());
        Inspection saved = inspectionService.create(input);
        assertThat(saved.getVerdict()).isEqualTo("合格");
        assertThat(saved.getBatchId()).isEqualTo(batch.getId());
    }

    private void runStepFlow(ProductionBatch batch) {
        for (String name : com.electronics.assembly.common.Dict.STEPS) {
            SmtStep s = stepRepository.findByBatchIdAndStepName(batch.getId(), name).orElseThrow();
            stepService.start(s.getId(), "王强");
            stepService.finish(s.getId());
        }
    }

    private void finishAllSteps(ProductionBatch batch) {
        for (String name : com.electronics.assembly.common.Dict.STEPS) {
            SmtStep s = stepRepository.findByBatchIdAndStepName(batch.getId(), name).orElseThrow();
            s.setStatus("已完成");
            stepRepository.save(s);
        }
    }
}
