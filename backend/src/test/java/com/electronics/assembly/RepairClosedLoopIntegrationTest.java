package com.electronics.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.dto.RepairTaskRequest;
import com.electronics.assembly.entity.Inspection;
import com.electronics.assembly.entity.RepairEvent;
import com.electronics.assembly.entity.RepairTask;
import com.electronics.assembly.entity.SmtStep;
import com.electronics.assembly.repository.InspectionRepository;
import com.electronics.assembly.repository.RepairEventRepository;
import com.electronics.assembly.repository.RepairTaskRepository;
import com.electronics.assembly.service.InspectionService;
import com.electronics.assembly.service.RepairTaskService;
import com.electronics.assembly.service.SmtStepService;
import com.electronics.assembly.service.WorkOrderService;

/**
 * 「质检不合格 → 返修」闭环集成测试：幂等建单、并发提交、复验通过/不通过、
 * 不能串单、未闭环不能跳过工序、旧数据缺字段补建。
 */
class RepairClosedLoopIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private InspectionService inspectionService;
    @Autowired
    private RepairTaskService repairTaskService;
    @Autowired
    private SmtStepService stepService;
    @Autowired
    private WorkOrderService workOrderService;
    @Autowired
    private InspectionRepository inspectionRepository;
    @Autowired
    private RepairTaskRepository taskRepository;
    @Autowired
    private RepairEventRepository eventRepository;
    @PersistenceContext
    private EntityManager em;

    private RepairTaskRequest body(String repairBy, String inspector, String result, String note) {
        RepairTaskRequest r = new RepairTaskRequest();
        r.setRepairBy(repairBy);
        r.setInspector(inspector);
        r.setResult(result);
        r.setNote(note);
        return r;
    }

    // ---------------- 1. 基础闭环：不合格建任务 → 游标返修中 → 完工 → 复验通过 → 已完成 ----------------

    @Test
    void fail_creates_task_moves_step_repairing_and_recheck_pass_closes_loop() {
        var order = newOrderWithSteps("WO-RT-1");
        forceStep(order.getId(), "上料", "已完成");
        forceStep(order.getId(), "贴片", "已完成");
        forceStep(order.getId(), "回流焊", "已完成");
        forceStep(order.getId(), "AOI", "进行中");

        Inspection insp = inspectionService.create(
                inspectionInput(order.getId(), "AOI", 5, "孙悦", UUID.randomUUID().toString()));
        assertThat(insp.getVerdict()).isEqualTo("返修");
        assertThat(insp.getRepairDone()).isFalse();

        RepairTask task = repairTaskService.findByInspection(insp.getId());
        assertThat(task).isNotNull();
        assertThat(task.getRoundNo()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo("待返修");
        assertThat(task.getOrder().getId()).isEqualTo(order.getId());

        // 对应工序游标必须置「返修中」，列表/详情口径一致
        assertThat(step(order.getId(), "AOI").getStatus()).isEqualTo("返修中");
        assertThat(repairTaskService.get(task.getId()).getStatus()).isEqualTo("待返修");
        assertThat(repairTaskService.detail(task.getId()).get("open")).isEqualTo(true);

        // 返修中不允许直接开工/完工/退回，未闭环不能跳过该工序
        SmtStep step = step(order.getId(), "AOI");
        assertThatThrownBy(() -> stepService.start(step.getId(), "张三"))
                .isInstanceOf(BizException.class).hasMessageContaining("返修");
        assertThatThrownBy(() -> stepService.finish(step.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("返修");
        assertThatThrownBy(() -> stepService.reset(step.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("返修");
        // 下一道目检同样不能开工：前序 AOI 卡在返修中，跳不过去
        SmtStep visual = step(order.getId(), "目检");
        assertThatThrownBy(() -> stepService.start(visual.getId(), "张三"))
                .isInstanceOf(BizException.class).hasMessageContaining("前序工序");

        // 返修工完工 → 待复验；返修工不能自己闭环
        task = repairTaskService.complete(task.getId(), body("李返修", null, null, "重焊偏移点"));
        assertThat(task.getStatus()).isEqualTo("待复验");
        assertThat(task.getRepairBy()).isEqualTo("李返修");
        assertThat(step(order.getId(), "AOI").getStatus()).isEqualTo("返修中");

        // 复验参数必须完整
        final Long taskIdPending = task.getId();
        assertThatThrownBy(() -> repairTaskService.recheck(taskIdPending, body(null, null, "合格", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("质检员");
        assertThatThrownBy(() -> repairTaskService.recheck(taskIdPending, body(null, "孙悦", "随便", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("合格 / 不合格");

        // 质检员复验通过 → 闭环，工序只能由这条通道推进到「已完成」
        task = repairTaskService.recheck(task.getId(), body(null, "孙悦", "合格", null));
        assertThat(task.getStatus()).isEqualTo("已复验通过");
        assertThat(task.getRecheckedBy()).isEqualTo("孙悦");
        assertThat(step(order.getId(), "AOI").getStatus()).isEqualTo("已完成");
        assertThat(inspectionService.get(insp.getId()).getRepairDone()).isTrue();
        assertThat(repairTaskService.countOpenByOrder(order.getId())).isZero();
        assertThat(repairTaskService.detail(task.getId()).get("open")).isEqualTo(false);

        // 审计链完整：建任务 → 返修完成 → 复验通过
        var events = eventRepository.findByTaskIdOrderByIdAsc(task.getId());
        assertThat(events).extracting(RepairEvent::getAction)
                .containsExactly("建任务", "返修完成", "复验通过");
    }

    // ---------------- 2. 复验不通过：轮次递增、游标继续停返修中，再走一轮才能闭环 ----------------

    @Test
    void recheck_fail_increments_round_and_second_round_can_pass() {
        var order = newOrderWithSteps("WO-RT-2");
        forceStep(order.getId(), "AOI", "已完成");

        Inspection insp = inspectionService.create(
                inspectionInput(order.getId(), "AOI", 5, "孙悦", UUID.randomUUID().toString()));
        RepairTask task = repairTaskService.findByInspection(insp.getId());
        assertThat(task.getRoundNo()).isEqualTo(1);
        // 已完成工序出现不合格也要回炉
        assertThat(step(order.getId(), "AOI").getStatus()).isEqualTo("返修中");

        // 第 1 轮：完工 → 复验不通过
        repairTaskService.complete(task.getId(), body("李返修", null, null, null));
        task = repairTaskService.recheck(task.getId(), body(null, "孙悦", "不合格", "偏移仍在"));
        assertThat(task.getStatus()).isEqualTo("待返修");
        assertThat(task.getRoundNo()).isEqualTo(2);
        assertThat(task.getRepairedAt()).isNull();
        assertThat(step(order.getId(), "AOI").getStatus()).isEqualTo("返修中");
        assertThat(inspectionService.get(insp.getId()).getRepairDone()).isFalse();

        // 待返修状态不能复验
        final Long taskIdRound2 = task.getId();
        assertThatThrownBy(() -> repairTaskService.recheck(taskIdRound2, body(null, "孙悦", "合格", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("返修工尚未完工");

        // 第 2 轮：再完工 → 复验通过
        repairTaskService.complete(task.getId(), body("李返修", null, null, "二次重焊"));
        task = repairTaskService.recheck(task.getId(), body(null, "孙悦", "合格", null));
        assertThat(task.getStatus()).isEqualTo("已复验通过");
        assertThat(task.getRoundNo()).isEqualTo(2);
        assertThat(step(order.getId(), "AOI").getStatus()).isEqualTo("已完成");

        var events = eventRepository.findByTaskIdOrderByIdAsc(task.getId());
        assertThat(events).extracting(RepairEvent::getAction)
                .containsExactly("建任务", "返修完成", "复验不通过", "返修完成", "复验通过");
        // 每轮事件都带事件落库时的轮次：复验不通过事件随轮次递增落为第 2 轮
        assertThat(events).extracting(RepairEvent::getRoundNo)
                .containsExactly(1, 1, 2, 2, 2);
    }

    // ---------------- 3. 幂等：同质检记录 + 同 requestId 双击/重试不产生重复任务 ----------------

    @Test
    void same_request_id_replays_first_inspection_and_task() {
        var order = newOrderWithSteps("WO-RT-3");
        String rid = UUID.randomUUID().toString();

        Inspection first = inspectionService.create(
                inspectionInput(order.getId(), "AOI", 5, "孙悦", rid));
        Inspection replay = inspectionService.create(
                inspectionInput(order.getId(), "AOI", 5, "孙悦", rid));

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(inspectionRepository.findByRequestId(rid)).isPresent();
        assertThat(taskRepository.findByOrderIdOrderByIdDesc(order.getId())).hasSize(1);

        // 返修完工重复点击也幂等（不重复记账）
        RepairTask task = repairTaskService.findByInspection(first.getId());
        var once = repairTaskService.complete(task.getId(), body("李返修", null, null, null));
        var twice = repairTaskService.complete(task.getId(), body("李返修", null, null, null));
        assertThat(twice.getId()).isEqualTo(once.getId());
        assertThat(twice.getStatus()).isEqualTo("待复验");
        assertThat(eventRepository.findByTaskIdOrderByIdAsc(task.getId()))
                .extracting(RepairEvent::getAction).filteredOn(a -> a.equals("返修完成")).hasSize(1);

        // 复验通过后网络重试：幂等返回，不重复推工序
        repairTaskService.recheck(task.getId(), body(null, "孙悦", "合格", null));
        var retry = repairTaskService.recheck(task.getId(), body(null, "孙悦", "合格", null));
        assertThat(retry.getStatus()).isEqualTo("已复验通过");
        assertThat(step(order.getId(), "AOI").getStatus()).isEqualTo("已完成");
    }

    // ---------------- 4. 并发提交：同一在制返修期间并发点「不合格」，最多一个任务 ----------------

    @Test
    void concurrent_submits_never_create_duplicate_open_tasks() throws Exception {
        var order = newOrderWithSteps("WO-RT-4");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    inspectionService.create(inspectionInput(order.getId(), "AOI", 5,
                            "孙悦", UUID.randomUUID().toString()));
                    ok.incrementAndGet();
                } catch (BizException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    // 唯一索引冲突也属于「没建成第二张单」，但服务层应转成 BizException，这里记为防御
                    rejected.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        // 恰好一个成功建单，其余全部被业务规则或唯一索引拦下
        assertThat(ok.get()).isEqualTo(1);
        assertThat(ok.get() + rejected.get()).isEqualTo(threads);

        var open = taskRepository.findOpenByOrderIdAndStepName(order.getId(), "AOI");
        var all = taskRepository.findByOrderIdOrderByIdDesc(order.getId());
        assertThat(open).hasSize(1);
        assertThat(all).hasSize(1);
        assertThat(step(order.getId(), "AOI").getStatus()).isEqualTo("返修中");
    }

    // ---------------- 5. 不串单：不同工单同工序各自独立 ----------------

    @Test
    void different_orders_never_share_repair_task() {
        var o1 = newOrderWithSteps("WO-RT-5A");
        var o2 = newOrderWithSteps("WO-RT-5B");

        Inspection i1 = inspectionService.create(
                inspectionInput(o1.getId(), "AOI", 5, "孙悦", UUID.randomUUID().toString()));
        Inspection i2 = inspectionService.create(
                inspectionInput(o2.getId(), "AOI", 5, "孙悦", UUID.randomUUID().toString()));

        RepairTask t1 = repairTaskService.findByInspection(i1.getId());
        RepairTask t2 = repairTaskService.findByInspection(i2.getId());
        assertThat(t1.getId()).isNotEqualTo(t2.getId());
        assertThat(t1.getOrder().getId()).isEqualTo(o1.getId());
        assertThat(t2.getOrder().getId()).isEqualTo(o2.getId());
        assertThat(taskRepository.findOpenByOrderId(o1.getId())).hasSize(1);
        assertThat(taskRepository.findOpenByOrderId(o2.getId())).hasSize(1);

        // 只闭环工单1，工单2仍然阻塞
        repairTaskService.complete(t1.getId(), body("李返修", null, null, null));
        repairTaskService.recheck(t1.getId(), body(null, "孙悦", "合格", null));
        assertThat(taskRepository.findOpenByOrderId(o1.getId())).isEmpty();
        assertThat(taskRepository.findOpenByOrderId(o2.getId())).hasSize(1);
        assertThat(step(o1.getId(), "AOI").getStatus()).isEqualTo("已完成");
        assertThat(step(o2.getId(), "AOI").getStatus()).isEqualTo("返修中");
    }

    // ---------------- 6. 未闭环返修不能跳过工序、不能推进工单 ----------------

    @Test
    void open_repair_blocks_finish_and_order_transfer() {
        var order = newOrderWithSteps("WO-RT-6");
        // AOI 已完工，又来一条不合格 → AOI 回炉返修中
        forceStep(order.getId(), "回流焊", "已完成");
        forceStep(order.getId(), "AOI", "已完成");
        inspectionService.create(
                inspectionInput(order.getId(), "AOI", 5, "孙悦", UUID.randomUUID().toString()));

        // 即便强行把 AOI 节点置回进行中，完工也被未闭环返修拦下
        forceStep(order.getId(), "AOI", "进行中");
        assertThatThrownBy(() -> stepService.finish(step(order.getId(), "AOI").getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("复验");

        // 工单推进被拦（贴片中 → 待检验 / 待检验 → 已完成 都不行）
        assertThatThrownBy(() -> workOrderService.transfer(order.getId(), "待检验"))
                .isInstanceOf(BizException.class).hasMessageContaining("复验闭环");

        // 闭环后放行（复验前把测试造数时挪开的游标复位为返修中）
        RepairTask task = taskRepository.findOpenByOrderId(order.getId()).get(0);
        repairTaskService.complete(task.getId(), body("李返修", null, null, null));
        forceStep(order.getId(), "AOI", "返修中");
        repairTaskService.recheck(task.getId(), body(null, "孙悦", "合格", null));
        assertThat(step(order.getId(), "AOI").getStatus()).isEqualTo("已完成");
    }

    // ---------------- 7. 旧数据缺字段：没有 repair_task 的旧返修记录可补建、可走新闭环 ----------------

    @Test
    void legacy_open_repair_is_backfilled_and_old_repair_endpoint_still_works() {
        var order = newOrderWithSteps("WO-RT-7");
        forceStep(order.getId(), "AOI", "已完成");

        // 模拟历史数据：直接插一条 verdict=返修、repair_done=false、request_id=NULL 的旧质检，
        // 且没有任何 repair_task
        Inspection legacy = inspectionInput(order.getId(), "AOI", 5, "周涛", null);
        legacy.setVerdict("返修");
        legacy.setRepairDone(false);
        legacy = inspectionRepository.save(legacy);
        assertThat(repairTaskService.findByInspection(legacy.getId())).isNull();

        // 启动补建：幂等执行（并发/重复启动下其它节点可能已补，结果幂等即可）
        repairTaskService.backfillLegacyTasks();
        // 再跑一遍不重复补
        assertThat(repairTaskService.backfillLegacyTasks()).isZero();

        RepairTask task = repairTaskService.findByInspection(legacy.getId());
        assertThat(task).isNotNull();
        assertThat(task.getRoundNo()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo("待返修");
        assertThat(step(order.getId(), "AOI").getStatus()).isEqualTo("返修中");

        // 旧接口 POST /inspections/{id}/repair：空体可用，返回的仍是 Inspection
        Inspection afterRepair = inspectionService.repair(legacy.getId());
        assertThat(afterRepair.getId()).isEqualTo(legacy.getId());
        task = repairTaskService.findByInspection(legacy.getId());
        assertThat(task.getStatus()).isEqualTo("待复验");

        // 旧接口的「未闭环返修」口径仍能查到
        assertThat(inspectionService.openRepairs()).extracting(Inspection::getId)
                .contains(legacy.getId());

        // 复验通过后旧口径消失、blocking 放行（旧响应字段仍在）
        repairTaskService.recheck(task.getId(), body(null, "孙悦", "合格", null));
        var blocking = inspectionService.blocking(order.getId());
        assertThat(blocking.get("blocked")).isEqualTo(false);
        assertThat(blocking.get("openRepairs")).isEqualTo(0);
        assertThat(blocking).containsKeys("orderId", "scrapped", "openRepairs", "blocked");
    }

    // ---------------- 8. 数据库唯一索引兜底：绕过服务层也插不进第二条在制返修任务 ----------------

    @Test
    void database_unique_constraints_reject_duplicate_open_task_and_duplicate_request_id() {
        var order = newOrderWithSteps("WO-RT-8");
        Inspection insp = inspectionService.create(
                inspectionInput(order.getId(), "AOI", 5, "孙悦", UUID.randomUUID().toString()));
        RepairTask legit = repairTaskService.findByInspection(insp.getId());

        // 8a) 同一质检记录手工再插一个任务 → uk_rt_inspection 拒绝
        RepairTask dup1 = newTask(order, insp, "AOI");
        assertThatThrownBy(() -> taskRepository.saveAndFlush(dup1))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        em.clear();
        // em.clear 后把受管引用刷新成锁后的实体，后续 complete/recheck 仍用正常服务通道
        legit = taskRepository.findById(legit.getId()).orElseThrow();

        // 8b) 不同质检记录、同工单同工序再插一个在制任务 → uk_rt_open 拒绝。
        // 用另一张工单的质检，任务却挂到本工单本工序上（撞 uk_rt_open）；同时给该质检
        // 关闭它自己工单上的在制任务，避免它先撞自己工单的唯一约束。
        var order2 = newOrderWithSteps("WO-RT-8B");
        Inspection otherInsp = inspectionService.create(
                inspectionInput(order2.getId(), "AOI", 5, "孙悦", UUID.randomUUID().toString()));
        RepairTask otherTask = repairTaskService.findByInspection(otherInsp.getId());
        repairTaskService.complete(otherTask.getId(), body("李返修", null, null, null));
        repairTaskService.recheck(otherTask.getId(), body(null, "孙悦", "合格", null));
        RepairTask dup2 = newTask(order, otherInsp, "AOI");
        assertThatThrownBy(() -> taskRepository.saveAndFlush(dup2))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // 撞约束的实体留在持久化上下文里会污染后续操作，清掉
        em.clear();

        // 8c) 已闭环任务不占唯一坑位：复验通过后同工单同工序可以再开新一轮质检返修
        repairTaskService.complete(legit.getId(), body("李返修", null, null, null));
        repairTaskService.recheck(legit.getId(), body(null, "孙悦", "合格", null));
        Inspection inspNext = inspectionService.create(
                inspectionInput(order.getId(), "AOI", 3, "孙悦", UUID.randomUUID().toString()));
        assertThat(repairTaskService.findByInspection(inspNext.getId())).isNotNull();
        // 列表 / 计数口径一致
        assertThat(repairTaskService.list(order.getId(), null))
                .extracting(RepairTask::getInspection).extracting(Inspection::getId)
                .containsExactly(inspNext.getId(), insp.getId());
        assertThat(repairTaskService.countOpenByOrder(order.getId())).isEqualTo(1);
    }

    private RepairTask newTask(com.electronics.assembly.entity.WorkOrder order, Inspection insp, String step) {
        RepairTask t = new RepairTask();
        t.setOrder(order);
        t.setInspection(insp);
        t.setStepName(step);
        t.setRoundNo(1);
        t.setStatus("待返修");
        t.setCreatedAt(java.time.LocalDateTime.now());
        return t;
    }
}
