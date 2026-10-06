package com.electronics.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.dto.BatchSplitRequest;
import com.electronics.assembly.entity.Inspection;
import com.electronics.assembly.entity.ProductionBatch;
import com.electronics.assembly.entity.SmtStep;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.repository.BomItemRepository;
import com.electronics.assembly.repository.ComponentRepository;
import com.electronics.assembly.repository.InspectionRepository;
import com.electronics.assembly.repository.ProductionBatchEventRepository;
import com.electronics.assembly.repository.ProductionBatchRepository;
import com.electronics.assembly.repository.RepairTaskRepository;
import com.electronics.assembly.service.InspectionService;
import com.electronics.assembly.service.ProductionBatchService;
import com.electronics.assembly.service.SmtStepService;
import com.electronics.assembly.service.WorkOrderService;

/**
 * 工单分批生产集成测试：
 * ① 数量守恒（不足 / 超额 / 空批次整份拒绝）；
 * ② 生产后调整限制（只能动未投产批次、已落账数量不挪走、状态不倒退）；
 * ③ 母子状态汇总（母工单数量 / 状态由批次汇总、全部完成才能完成母工单）；
 * ④ 取消回收与计划员确认减产；
 * ⑤ 历史迁移（首次分批把母工单原完成数量落到只读历史批次，不重复累计）；
 * 另含批次独立走五道工序与质检返修闭环。
 */
class BatchProductionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ProductionBatchService batchService;
    @Autowired private WorkOrderService workOrderService;
    @Autowired private SmtStepService stepService;
    @Autowired private InspectionService inspectionService;
    @Autowired private ProductionBatchRepository batchRepository;
    @Autowired private ProductionBatchEventRepository batchEventRepository;
    @Autowired private ComponentRepository componentRepository;
    @Autowired private BomItemRepository bomRepository;
    @Autowired private InspectionRepository inspectionRepository;
    @Autowired private RepairTaskRepository repairTaskRepository;

    private String model;

    @BeforeEach
    void setupModel() {
        // 每测一个唯一机型 + 充足库存，保证批次齐套通过（part_no 列长 32，用短码）
        String code = Integer.toHexString(System.identityHashCode(this))
                + Integer.toHexString((int) (Math.random() * 0xfffff));
        model = "MB-" + code;
        String partNo = "P-" + code;
        com.electronics.assembly.entity.BomItem bom = new com.electronics.assembly.entity.BomItem();
        bom.setProductModel(model);
        bom.setPartNo(partNo);
        bom.setUnitQty(1);
        bomRepository.save(bom);
        com.electronics.assembly.entity.Component c = new com.electronics.assembly.entity.Component();
        c.setPartNo(partNo);
        c.setPartName("批次测试料");
        c.setPackageType("0402");
        c.setStockQty(1_000_000);
        c.setFeedSlot("F99");
        c.setStatus("正常");
        componentRepository.save(c);
    }

    private WorkOrder newOrder(String no, int plan, int done, String status) {
        WorkOrder o = new WorkOrder();
        o.setOrderNo(no);
        o.setProductModel(model);
        o.setPlanQty(plan);
        o.setDoneQty(done);
        o.setPriority("中");
        o.setStatus(status);
        o.setSplitEnabled(false);
        return orderRepository.save(o);
    }

    private BatchSplitRequest split(String operator, int... qtys) {
        BatchSplitRequest req = new BatchSplitRequest();
        req.setOperator(operator);
        req.setBatches(new java.util.ArrayList<>());
        for (int q : qtys) {
            BatchSplitRequest.Line line = new BatchSplitRequest.Line();
            line.setPlanQty(q);
            req.getBatches().add(line);
        }
        return req;
    }

    private ProductionBatch byNo(List<ProductionBatch> list, String no) {
        return list.stream().filter(b -> b.getBatchNo().equals(no)).findFirst().orElseThrow();
    }

    /** 把某批次五道工序依次走到全部完成（不触发返修） */
    private void finishAllSteps(ProductionBatch b) {
        List<SmtStep> steps = stepRepository.findByBatchIdOrderById(b.getId());
        for (String name : com.electronics.assembly.common.Dict.STEPS) {
            SmtStep s = steps.stream().filter(x -> x.getStepName().equals(name)).findFirst().orElseThrow();
            stepService.start(s.getId(), "王强");
            stepService.finish(s.getId());
        }
    }

    // ================= 1. 数量守恒 =================

    @Test
    void split_plan_must_conserve_qty_shortage_overage_and_empty_batch_rejected() {
        WorkOrder o = newOrder("WO-B-1", 100, 0, "待投产");
        final Long oid = o.getId();

        // 数量不足（合计 < 计划）整份拒绝
        assertThatThrownBy(() -> batchService.savePlan(oid, split("计划员", 30, 60)))
                .isInstanceOf(BizException.class).hasMessageContaining("数量不守恒");
        // 超额（合计 > 计划）整份拒绝
        assertThatThrownBy(() -> batchService.savePlan(oid, split("计划员", 60, 60)))
                .isInstanceOf(BizException.class).hasMessageContaining("数量不守恒");
        // 空批次（0）整份拒绝
        assertThatThrownBy(() -> batchService.savePlan(oid, split("计划员", 100, 0)))
                .isInstanceOf(BizException.class).hasMessageContaining("空批次");
        // 空方案拒绝
        assertThatThrownBy(() -> batchService.savePlan(oid, new BatchSplitRequest()))
                .isInstanceOf(BizException.class).hasMessageContaining("不能为空");

        // 拒绝后不落任何数据：工单未启用分批、无批次
        o = orderRepository.findById(oid).orElseThrow();
        assertThat(o.getSplitEnabled()).isFalse();
        assertThat(batchService.listByOrder(oid)).isEmpty();

        // 守恒方案成功保存
        List<ProductionBatch> batches = batchService.savePlan(oid, split("计划员", 40, 60));
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).getBatchNo()).isEqualTo("WO-B-1-B1");
        assertThat(batches.get(0).getSeqNo()).isEqualTo(1);
        assertThat(batches.get(1).getBatchNo()).isEqualTo("WO-B-1-B2");
        assertThat(batches.get(1).getSeqNo()).isEqualTo(2);
        assertThat(batches).allSatisfy(b -> {
            assertThat(b.getStatus()).isEqualTo("待投产");
            assertThat(b.getReadOnly()).isFalse();
        });
        assertThat(orderRepository.findById(oid).orElseThrow().getSplitEnabled()).isTrue();
    }

    // ================= 2. 生产后调整限制 =================

    @Test
    void after_production_started_only_unreleased_batches_adjustable_and_qty_frozen() {
        WorkOrder o = newOrder("WO-B-2", 100, 0, "待投产");
        List<ProductionBatch> bs = batchService.savePlan(o.getId(), split("计划员", 40, 30, 30));
        ProductionBatch b1 = bs.get(0), b2 = bs.get(1), b3 = bs.get(2);

        // B1 投产 → 贴片中，报工 10
        batchService.release(b1.getId());
        batchService.report(b1.getId(), 10, "王强");

        // 调整 B1 数量被拒（已开始生产，已报工数量不能挪走）
        BatchSplitRequest changeB1 = new BatchSplitRequest();
        changeB1.setOperator("计划员");
        changeB1.setBatches(List.of(line(b1.getId(), 35), line(b2.getId(), 35), line(b3.getId(), 30)));
        assertThatThrownBy(() -> batchService.savePlan(o.getId(), changeB1))
                .isInstanceOf(BizException.class).hasMessageContaining("已开始生产");

        // 从方案里删掉 B1 也被拒
        BatchSplitRequest dropB1 = new BatchSplitRequest();
        dropB1.setOperator("计划员");
        dropB1.setBatches(List.of(line(b2.getId(), 50), line(b3.getId(), 50)));
        assertThatThrownBy(() -> batchService.savePlan(o.getId(), dropB1))
                .isInstanceOf(BizException.class).hasMessageContaining("不能删除");

        // 只能调整未投产批次：B2 30→50、B3 30→10，守恒（40+50+10=100），成功
        BatchSplitRequest ok = new BatchSplitRequest();
        ok.setOperator("计划员");
        ok.setBatches(List.of(line(b1.getId(), 40), line(b2.getId(), 50), line(b3.getId(), 10)));
        List<ProductionBatch> after = batchService.savePlan(o.getId(), ok);
        assertThat(byNo(after, "WO-B-2-B2").getPlanQty()).isEqualTo(50);
        assertThat(byNo(after, "WO-B-2-B3").getPlanQty()).isEqualTo(10);
        // B1 报工数量原样保留
        assertThat(byNo(after, "WO-B-2-B1").getReportedQty()).isEqualTo(10);

        // 删除未投产 B3：方案只留 B1(40)+B2(60)，守恒
        BatchSplitRequest delB3 = new BatchSplitRequest();
        delB3.setOperator("计划员");
        delB3.setBatches(List.of(line(b1.getId(), 40), line(b2.getId(), 60)));
        List<ProductionBatch> afterDel = batchService.savePlan(o.getId(), delB3);
        assertThat(afterDel).hasSize(2);
        assertThat(byNo(afterDel, "WO-B-2-B2").getPlanQty()).isEqualTo(60);
    }

    @Test
    void batch_status_cannot_go_backward() {
        WorkOrder o = newOrder("WO-B-2B", 100, 0, "待投产");
        ProductionBatch b = batchService.savePlan(o.getId(), split("计划员", 100)).get(0);
        batchService.release(b.getId());
        assertThat(batchService.get(b.getId()).getStatus()).isEqualTo("贴片中");
        // 状态倒退：贴片中 → 待投产 拒绝
        assertThatThrownBy(() -> batchService.transfer(b.getId(), "待投产"))
                .isInstanceOf(BizException.class).hasMessageContaining("倒退");
    }

    @Test
    void reported_cannot_exceed_plan_and_inspected_cannot_exceed_reported() {
        WorkOrder o = newOrder("WO-B-2C", 100, 0, "待投产");
        ProductionBatch b = batchService.savePlan(o.getId(), split("计划员", 100)).get(0);
        batchService.release(b.getId());
        // 报工超额拒绝
        assertThatThrownBy(() -> batchService.report(b.getId(), 101, "王强"))
                .isInstanceOf(BizException.class).hasMessageContaining("超过本批计划");
        batchService.report(b.getId(), 40, "王强");
        // 未报工部分不能送检
        assertThatThrownBy(() -> batchService.inspect(b.getId(), 41, "孙悦"))
                .isInstanceOf(BizException.class).hasMessageContaining("超过已报工");
        // 未送检不能合格入库
        assertThatThrownBy(() -> batchService.completeQty(b.getId(), 1, "孙悦"))
                .isInstanceOf(BizException.class).hasMessageContaining("超过已送检");
    }

    // ================= 3. 母子状态汇总 =================

    @Test
    void parent_qty_and_status_aggregated_from_batches_and_finish_gate() {
        WorkOrder o = newOrder("WO-B-3", 100, 0, "待投产");
        List<ProductionBatch> bs = batchService.savePlan(o.getId(), split("计划员", 60, 40));
        ProductionBatch b1 = bs.get(0), b2 = bs.get(1);

        // 全部完成前母工单不能完成（状态机逐级推进 + 批次未完成双重拦截）
        assertThatThrownBy(() -> workOrderService.transfer(o.getId(), "已完成"))
                .isInstanceOf(BizException.class);

        // B1 投产 + 报工 60 + 送检 60 + 完成 60 + 五道工序走完 → 批次完成
        runBatchThrough(b1, 60);
        // 母工单汇总完成 60，状态仍是贴片中（B2 待投产）
        WorkOrder mid = orderRepository.findById(o.getId()).orElseThrow();
        assertThat(mid.getDoneQty()).isEqualTo(60);
        assertThat(mid.getStatus()).isEqualTo("贴片中");
        // 仍不能完成母工单
        assertThatThrownBy(() -> workOrderService.transfer(o.getId(), "已完成"))
                .isInstanceOf(BizException.class);

        // B2 走完
        runBatchThrough(b2, 40);
        WorkOrder done = orderRepository.findById(o.getId()).orElseThrow();
        assertThat(done.getDoneQty()).isEqualTo(100);
        // 全部批次完成后母工单可完成
        WorkOrder finished = workOrderService.transfer(o.getId(), "已完成");
        assertThat(finished.getStatus()).isEqualTo("已完成");
        assertThat(finished.getDoneQty()).isEqualTo(100);
    }

    @Test
    void parent_reports_kit_status_while_batches_in_progress() {
        WorkOrder o = newOrder("WO-B-3B", 100, 0, "待投产");
        List<ProductionBatch> bs = batchService.savePlan(o.getId(), split("计划员", 50, 50));
        batchService.release(bs.get(0).getId()); // B1 贴片中
        WorkOrder o2 = orderRepository.findById(o.getId()).orElseThrow();
        assertThat(o2.getStatus()).isEqualTo("贴片中");
        // 批次树上下文守恒且全完成标志为 false
        var ctx = batchService.context(o.getId());
        assertThat(ctx.get("conserved")).isEqualTo(true);
        assertThat(ctx.get("allFinished")).isEqualTo(false);
        assertThat(ctx.get("planSum")).isEqualTo(100);
    }

    // ================= 4. 取消回收与减产 =================

    @Test
    void cancel_reclaims_remaining_to_unreleased_batch() {
        WorkOrder o = newOrder("WO-B-4", 100, 0, "待投产");
        List<ProductionBatch> bs = batchService.savePlan(o.getId(), split("计划员", 60, 40));
        ProductionBatch b1 = bs.get(0), b2 = bs.get(1);
        batchService.release(b1.getId());
        batchService.report(b1.getId(), 20, "王强"); // B1 已报工 20，剩 40 未产

        // 不指定回收也不确认减产 → 拒绝
        assertThatThrownBy(() -> batchService.cancel(b1.getId(), null, false, "计划员"))
                .isInstanceOf(BizException.class).hasMessageContaining("回收");
        // 不能回收到自身
        assertThatThrownBy(() -> batchService.cancel(b1.getId(), b1.getId(), false, "计划员"))
                .isInstanceOf(BizException.class).hasMessageContaining("自身");
        // 回收和减产不能同时
        assertThatThrownBy(() -> batchService.cancel(b1.getId(), b2.getId(), true, "计划员"))
                .isInstanceOf(BizException.class).hasMessageContaining("二选一");

        // 回收到未投产 B2：B2 计划 40+40=80；B1 已报工 20 冻结
        batchService.cancel(b1.getId(), b2.getId(), false, "计划员");
        ProductionBatch cancelled = batchService.get(b1.getId());
        ProductionBatch target = batchService.get(b2.getId());
        assertThat(cancelled.getStatus()).isEqualTo("已取消");
        assertThat(cancelled.getReportedQty()).isEqualTo(20); // 已报工不挪走
        assertThat(target.getPlanQty()).isEqualTo(80);
        // 守恒：有效批次计划(80) + 减产(0) = 母工单计划(100)
        assertThat(batchService.context(o.getId()).get("conserved")).isEqualTo(true);

        // 已取消批次不能再报工/调整
        assertThatThrownBy(() -> batchService.report(b1.getId(), 1, "王强"))
                .isInstanceOf(BizException.class).hasMessageContaining("已取消");
    }

    @Test
    void cancel_with_planner_confirm_reduce_cuts_parent_plan() {
        WorkOrder o = newOrder("WO-B-4B", 100, 0, "待投产");
        List<ProductionBatch> bs = batchService.savePlan(o.getId(), split("计划员", 60, 40));
        ProductionBatch b1 = bs.get(0);
        batchService.release(b1.getId());
        batchService.report(b1.getId(), 20, "王强");

        // 计划员确认减产 40：母工单原始计划仍为 100（统一锚定原计划，交付进度不失真）
        batchService.cancel(b1.getId(), null, true, "李计划");
        WorkOrder o2 = orderRepository.findById(o.getId()).orElseThrow();
        assertThat(o2.getPlanQty()).isEqualTo(100);
        assertThat(batchService.get(b1.getId()).getReduceQty()).isEqualTo(40);
        // 守恒：有效 B2(40) + 减产 40 + 取消批次冻结在制 20 = 母工单原计划 100
        var ctx = batchService.context(o.getId());
        assertThat(ctx.get("planSum")).isEqualTo(40);
        assertThat(ctx.get("reduceSum")).isEqualTo(40);
        assertThat(ctx.get("frozenWip")).isEqualTo(20);
        assertThat(ctx.get("conserved")).isEqualTo(true);
        // B2 走完（40 板）；取消批次冻结的 20 板报工已产出，随完成节点计入母工单完成
        runBatchThrough(bs.get(1), 40);
        WorkOrder finished = workOrderService.transfer(o.getId(), "已完成");
        assertThat(finished.getStatus()).isEqualTo("已完成");
        // 完成数量只计各批 doneQty：B2 合格入库 40；B1 取消时冻结的 20 是已报工未完成在制，不计完成
        assertThat(finished.getDoneQty()).isEqualTo(40);
    }

    // ================= 5. 历史迁移 =================

    @Test
    void first_split_freezes_legacy_done_into_readonly_history_batch() {
        // 已有完成数量 260 的在制工单（模拟历史工单）
        WorkOrder o = newOrder("WO-B-5", 800, 260, "贴片中");
        // 可拆数量 = 800 - 260 = 540
        List<ProductionBatch> bs = batchService.savePlan(o.getId(), split("计划员", 300, 240));

        ProductionBatch history = bs.stream().filter(ProductionBatch::getReadOnly).findFirst().orElseThrow();
        assertThat(history.getBatchNo()).isEqualTo("WO-B-5-H");
        assertThat(history.getSeqNo()).isZero();
        assertThat(history.getPlanQty()).isEqualTo(260);
        assertThat(history.getDoneQty()).isEqualTo(260);
        assertThat(history.getReportedQty()).isEqualTo(260);
        assertThat(history.getStatus()).isEqualTo("已完成");

        // 新批次不重复累计历史数量
        List<ProductionBatch> fresh = bs.stream().filter(b -> !b.getReadOnly()).toList();
        assertThat(fresh).hasSize(2);
        assertThat(fresh).allSatisfy(b -> assertThat(b.getDoneQty()).isZero());

        // 母工单完成数量仍为 260（历史 260 + 新批 0），未翻倍
        assertThat(orderRepository.findById(o.getId()).orElseThrow().getDoneQty()).isEqualTo(260);

        // 历史批次只读：不能投产 / 报工 / 送检 / 取消
        assertThatThrownBy(() -> batchService.release(history.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("只读");
        assertThatThrownBy(() -> batchService.report(history.getId(), 1, "王强"))
                .isInstanceOf(BizException.class).hasMessageContaining("只读");
        assertThatThrownBy(() -> batchService.cancel(history.getId(), null, true, "计划员"))
                .isInstanceOf(BizException.class).hasMessageContaining("只读");

        // 历史迁移事件已落审计链
        var events = batchEventRepository.findByBatchIdOrderByIdAsc(history.getId());
        assertThat(events).extracting(e -> e.getAction()).contains("历史迁移");
    }

    @Test
    void legacy_unsplit_order_keeps_original_flow() {
        // 未启用分批的工单：母工单直接投产/报工/推进照旧
        WorkOrder o = newOrder("WO-B-5B", 100, 0, "待投产");
        WorkOrder rel = workOrderService.release(o.getId());
        assertThat(rel.getStatus()).isEqualTo("贴片中");
        // 铺开的是工单级工序（batch_id 为空）
        assertThat(stepRepository.findByOrderIdOrderById(o.getId())).hasSize(5);
        assertThat(stepRepository.findByOrderIdOrderById(o.getId()))
                .allSatisfy(s -> assertThat(s.getBatch()).isNull());
        WorkOrder reported = workOrderService.report(o.getId(), 50);
        assertThat(reported.getDoneQty()).isEqualTo(50);
        // 没有任何批次
        assertThat(batchService.listByOrder(o.getId())).isEmpty();
    }

    // ================= 6. 批次独立走五道工序 + 质检返修 =================

    @Test
    void each_batch_runs_own_five_steps_and_inspection_repair_loop() {
        WorkOrder o = newOrder("WO-B-6", 100, 0, "待投产");
        List<ProductionBatch> bs = batchService.savePlan(o.getId(), split("计划员", 50, 50));
        ProductionBatch b1 = bs.get(0), b2 = bs.get(1);
        batchService.release(b1.getId());
        batchService.release(b2.getId());

        // 每批各自五道工序，不串批
        assertThat(stepRepository.findByBatchIdOrderById(b1.getId())).hasSize(5);
        assertThat(stepRepository.findByBatchIdOrderById(b2.getId())).hasSize(5);

        // B1 上料完成、贴片进行中；B2 上料完成 → AOI 返修场景
        stepService.start(stepRepository.findByBatchIdAndStepName(b1.getId(), "上料").orElseThrow().getId(), "王强");
        stepService.finish(stepRepository.findByBatchIdAndStepName(b1.getId(), "上料").orElseThrow().getId());
        // B2 走到 AOI 已完成，登记返修 → AOI 回炉，且不影响 B1 的 AOI
        finishStepsUntil(b2, "AOI");
        // 质检受送检量约束：先报工/送检 20 板
        batchService.report(b2.getId(), 20, "王强");
        batchService.inspect(b2.getId(), 20, "孙悦");
        Inspection insp = new Inspection();
        insp.setOrder(newId(o.getId()));
        insp.setBatch(newBatchId(b2.getId()));
        insp.setStepName("AOI");
        insp.setDefectType("偏移");
        insp.setDefectPoints(5);
        insp.setSampleQty(10);
        insp.setInspector("孙悦");
        insp.setRequestId(UUID.randomUUID().toString());
        Inspection saved = inspectionService.create(insp);
        assertThat(saved.getBatch().getId()).isEqualTo(b2.getId());

        // B2 的 AOI 返修中，B1 的 AOI 仍是待执行
        assertThat(stepRepository.findByBatchIdAndStepName(b2.getId(), "AOI").orElseThrow().getStatus())
                .isEqualTo("返修中");
        assertThat(stepRepository.findByBatchIdAndStepName(b1.getId(), "AOI").orElseThrow().getStatus())
                .isEqualTo("待执行");

        // B1 的 AOI 可以正常开工（不被 B2 的返修阻塞）
        finishStepsUntil(b1, "回流焊");
        SmtStep b1Aoi = stepRepository.findByBatchIdAndStepName(b1.getId(), "AOI").orElseThrow();
        stepService.start(b1Aoi.getId(), "王强");
        stepService.finish(b1Aoi.getId());
        assertThat(stepRepository.findByBatchIdAndStepName(b1.getId(), "AOI").orElseThrow().getStatus())
                .isEqualTo("已完成");

        // 分批工单质检必须指定批次
        Inspection noBatch = new Inspection();
        noBatch.setOrder(newId(o.getId()));
        noBatch.setStepName("目检");
        noBatch.setDefectType("少件");
        noBatch.setDefectPoints(1);
        noBatch.setSampleQty(5);
        noBatch.setInspector("孙悦");
        noBatch.setRequestId(UUID.randomUUID().toString());
        assertThatThrownBy(() -> inspectionService.create(noBatch))
                .isInstanceOf(BizException.class).hasMessageContaining("必须指定具体批次");
    }

    @Test
    void batch_cannot_finish_with_unclosed_repair() {
        WorkOrder o = newOrder("WO-B-6B", 100, 0, "待投产");
        ProductionBatch b = batchService.savePlan(o.getId(), split("计划员", 100)).get(0);
        batchService.release(b.getId());
        finishStepsUntil(b, "AOI");
        batchService.report(b.getId(), 10, "王强");
        batchService.inspect(b.getId(), 10, "孙悦");
        Inspection insp = new Inspection();
        insp.setOrder(newId(o.getId()));
        insp.setBatch(newBatchId(b.getId()));
        insp.setStepName("AOI");
        insp.setDefectType("虚焊");
        insp.setDefectPoints(5);
        insp.setSampleQty(10);
        insp.setInspector("孙悦");
        insp.setRequestId(UUID.randomUUID().toString());
        inspectionService.create(insp);
        // 有未闭环返修，批次不能完成（先把数量补齐也没用，返修任务阻塞）
        assertThat(repairTaskRepository.findOpenByBatchId(b.getId())).hasSize(1);
        // 直接推进到已完成：五道工序未完成且返修未闭环，拒绝
        assertThatThrownBy(() -> batchService.transfer(b.getId(), "已完成"))
                .isInstanceOf(BizException.class);
    }

    // ---------------- 辅助 ----------------

    /** 建一个只带 id 的工单引用对象（模拟 {"id":n} 入参） */
    private WorkOrder newId(Long id) {
        WorkOrder o = new WorkOrder();
        o.setId(id);
        return o;
    }

    /** 建一个只带 id 的批次引用对象 */
    private ProductionBatch newBatchId(Long id) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        return b;
    }

    private BatchSplitRequest.Line line(Long id, int qty) {
        BatchSplitRequest.Line l = new BatchSplitRequest.Line();
        l.setId(id);
        l.setPlanQty(qty);
        return l;
    }

    /** 把批次从投产走到全部完成：投产 → 五道工序 → 报工/送检/完成全量 → 待检验 → 已完成 */
    private void runBatchThrough(ProductionBatch b, int qty) {
        if ("待投产".equals(batchService.get(b.getId()).getStatus())) {
            batchService.release(b.getId());
        }
        finishAllSteps(batchService.get(b.getId()));
        batchService.report(b.getId(), qty, "王强");
        batchService.inspect(b.getId(), qty, "孙悦");
        batchService.completeQty(b.getId(), qty, "孙悦");
        batchService.transfer(b.getId(), "待检验");
        batchService.transfer(b.getId(), "已完成");
    }

    /** 从上料依次开工/完工，直到指定工序本身也完工（指定工序须在五道内） */
    private void finishStepsUntil(ProductionBatch b, String until) {
        var dict = com.electronics.assembly.common.Dict.STEPS;
        for (String name : dict) {
            SmtStep s = stepRepository.findByBatchIdAndStepName(b.getId(), name).orElseThrow();
            if (!"已完成".equals(s.getStatus())) {
                stepService.start(s.getId(), "王强");
                stepService.finish(s.getId());
            }
            if (name.equals(until)) {
                return;
            }
        }
    }
}
