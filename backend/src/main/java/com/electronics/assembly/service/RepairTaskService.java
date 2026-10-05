package com.electronics.assembly.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.common.Dict;
import com.electronics.assembly.dto.RepairTaskRequest;
import com.electronics.assembly.entity.Inspection;
import com.electronics.assembly.entity.RepairEvent;
import com.electronics.assembly.entity.RepairTask;
import com.electronics.assembly.entity.SmtStep;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.repository.InspectionRepository;
import com.electronics.assembly.repository.RepairEventRepository;
import com.electronics.assembly.repository.RepairTaskRepository;
import com.electronics.assembly.repository.SmtStepRepository;
import com.electronics.assembly.repository.WorkOrderRepository;

/**
 * 返修闭环业务：质检不合格 → 幂等建/复用返修任务 → 返修工完工 → 质检员复验，
 * 复验通过才把对应工序游标推进到「已完成」，复验不通过则返修轮次 +1 重来。
 *
 * <p>并发口径（与换线首件一致）：所有写操作先按工单 id 悲观锁工单行串行化，
 * 再锁重读返修任务行；数据库另有两道唯一索引兜底——
 * {@code uk_rt_inspection(inspection_id)} 保证一条质检记录最多一个返修任务，
 * {@code uk_rt_open(order_id, active_step_name)} 保证同工单同工序同时只有一个
 * 未闭环任务。重复点击、刷新重提、网络重试、并发提交都不会产生重复返修任务，
 * 不同工单也不可能串单。</p>
 */
@Service
public class RepairTaskService {

    @PersistenceContext
    private EntityManager em;

    private final RepairTaskRepository taskRepository;
    private final RepairEventRepository eventRepository;
    private final InspectionRepository inspectionRepository;
    private final SmtStepRepository stepRepository;
    private final WorkOrderRepository orderRepository;
    /** 自身代理：补建历史任务时按 REQUIRES_NEW 逐条开事务，必须走代理才能生效 */
    private final RepairTaskService self;

    public RepairTaskService(RepairTaskRepository taskRepository,
                             RepairEventRepository eventRepository,
                             InspectionRepository inspectionRepository,
                             SmtStepRepository stepRepository,
                             WorkOrderRepository orderRepository,
                             @Lazy RepairTaskService self) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.inspectionRepository = inspectionRepository;
        this.stepRepository = stepRepository;
        this.orderRepository = orderRepository;
        this.self = self;
    }

    // ---------------- 查询 ----------------

    public List<RepairTask> list(Long orderId, String status) {
        if (orderId != null) {
            return taskRepository.findByOrderIdOrderByIdDesc(orderId);
        }
        if (status != null && !status.isBlank()) {
            return taskRepository.findByStatusIn(List.of(status));
        }
        return taskRepository.findAll();
    }

    public RepairTask get(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new BizException("返修任务不存在：" + id));
    }

    /** 由质检记录找返修任务（可能为 null：合格 / 判废或历史旧数据尚未补建） */
    public RepairTask findByInspection(Long inspectionId) {
        return taskRepository.findByInspectionId(inspectionId).orElse(null);
    }

    /** 返修任务详情：任务本体 + 完整处理记录，列表/详情/刷新口径一致 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        RepairTask task = get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", task);
        out.put("inspection", task.getInspection());
        out.put("events", eventRepository.findByTaskIdOrderByIdAsc(id));
        out.put("open", task.isOpen());
        return out;
    }

    /** 未闭环返修任务（旧的 /inspections/open-repairs 口径同源） */
    public List<RepairTask> openTasks() {
        return taskRepository.findByStatusIn(List.of("待返修", "待复验"));
    }

    public List<RepairEvent> events(Long taskId) {
        return eventRepository.findByTaskIdOrderByIdAsc(taskId);
    }

    // ---------------- 写操作：统一先锁工单行串行化 ----------------

    /**
     * 质检不合格时幂等创建或复用返修任务，并把对应工序游标置为「返修中」。
     * 调用方（InspectionService）必须已完成参数校验，且与质检插入处于同一事务。
     *
     * @return 该质检记录对应的返修任务（首建或复用）
     */
    @Transactional
    public RepairTask createOrReuse(Inspection insp) {
        Long orderId = insp.getOrder().getId();
        // 锁工单行：同工单的质检提交 / 返修完工 / 复验在这里串行化，不同工单各走各的锁
        WorkOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("工单不存在：" + orderId));

        // 幂等：同一质检记录永远只有一个返修任务（正常路径唯一索引已兜底，这里给出友好复用）
        Optional<RepairTask> existing = taskRepository.findByInspectionId(insp.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        // 同工单同工序已有未闭环返修任务：质检员对同一在制返修重复提交不合格时，
        // 不允许再建第二个任务——先把手上这轮流完复验。防止重复点击/并发产生重复任务。
        List<RepairTask> openOnStep = taskRepository.findOpenByOrderIdAndStepName(orderId, insp.getStepName());
        if (!openOnStep.isEmpty()) {
            throw new BizException("工序「" + insp.getStepName() + "」已有未闭环返修任务（轮次 "
                    + openOnStep.get(0).getRoundNo() + "，状态：" + openOnStep.get(0).getStatus()
                    + "），请先完成返修并复验，不能重复建单");
        }

        RepairTask task = new RepairTask();
        task.setOrder(order);
        task.setInspection(insp);
        task.setStepName(insp.getStepName());
        task.setRoundNo(1);
        task.setStatus("待返修");
        task.setCreatedAt(LocalDateTime.now());
        task = taskRepository.saveAndFlush(task);
        log(task, "建任务", insp.getInspector(), "质检「返修」结论：" + insp.getDefectType()
                + " " + insp.getDefectPoints() + " 点，第 1 轮返修已派工");

        // 对应工序游标置「返修中」：无论它原来是进行中还是已完成（不合格要回炉）
        moveStepToRepairing(orderId, insp.getStepName());
        return task;
    }

    /**
     * 返修工完工上报：待返修 → 待复验。之后只能等质检员复验，返修工自己不能闭环。
     * 兼容旧接口 POST /inspections/{id}/repair：按质检 id 找任务、空体可调用、重复上报幂等。
     */
    @Transactional
    public RepairTask complete(Long taskId, RepairTaskRequest req) {
        RepairTask probe = get(taskId);
        lockOrder(probe.getOrder().getId());
        RepairTask task = reloadLocked(taskId);
        if ("已复验通过".equals(task.getStatus())) {
            throw new BizException("返修任务已复验闭环，不能再次上报返修");
        }
        if ("待复验".equals(task.getStatus())) {
            // 网络重试 / 双击：已在等复验，幂等返回当前任务，不重复记账
            return task;
        }
        String repairBy = req != null && req.getRepairBy() != null && !req.getRepairBy().isBlank()
                ? req.getRepairBy().trim() : "返修工";
        task.setStatus("待复验");
        task.setRepairBy(repairBy);
        task.setRepairNote(req != null ? trim(req.getRepairNote()) : null);
        task.setRepairedAt(LocalDateTime.now());
        task = taskRepository.save(task);
        log(task, "返修完成", repairBy, "第 " + task.getRoundNo() + " 轮返修完成，转质检员复验"
                + (task.getRepairNote() == null ? "" : "：" + task.getRepairNote()));
        return task;
    }

    /**
     * 旧接口兼容：按质检 id 上报返修完工。
     */
    @Transactional
    public RepairTask completeByInspection(Long inspectionId, RepairTaskRequest req) {
        RepairTask task = taskRepository.findByInspectionId(inspectionId).orElse(null);
        if (task == null) {
            // 历史「返修」质检在新任务体系上线前遗留：补建任务并直接落到待复验，
            // 保证旧数据上点「闭环」不报错（旧数据缺字段兜底）。
            task = backfillOne(inspectionId);
        }
        return complete(task.getId(), req);
    }

    /**
     * 质检员复验。只能由质检员推进闭环：
     * <ul>
     *   <li>合格：待复验 → 已复验通过，工序游标 → 已完成，inspection.repairDone = true；</li>
     *   <li>不合格：返修轮次 +1，退回待返修，工序继续停在「返修中」。</li>
     * </ul>
     */
    @Transactional
    public RepairTask recheck(Long taskId, RepairTaskRequest req) {
        RepairTask probe = get(taskId);
        lockOrder(probe.getOrder().getId());
        RepairTask task = reloadLocked(taskId);
        if (req == null || req.getInspector() == null || req.getInspector().isBlank()) {
            throw new BizException("复验必须署名质检员");
        }
        String inspector = req.getInspector().trim();
        String result = req.getResult();
        if (!"合格".equals(result) && !"不合格".equals(result)) {
            throw new BizException("复验结果只能是：合格 / 不合格");
        }
        if (!"待复验".equals(task.getStatus())) {
            // 复验通过后的网络重试幂等返回；其它状态给出明确提示
            if ("已复验通过".equals(task.getStatus())) {
                return task;
            }
            throw new BizException("返修任务当前为「" + task.getStatus() + "」，返修工尚未完工，不能复验");
        }

        LocalDateTime now = LocalDateTime.now();
        task.setRecheckedBy(inspector);
        task.setLastRecheckResult(result);
        task.setRecheckedAt(now);

        Inspection insp = task.getInspection();
        if ("合格".equals(result)) {
            task.setStatus("已复验通过");
            task = taskRepository.save(task);
            if (insp != null) {
                insp.setRepairDone(true);
                inspectionRepository.save(insp);
            }
            // 唯一允许把「返修中」工序推进到「已完成」的通道
            moveStepToFinished(task.getOrder().getId(), task.getStepName());
            log(task, "复验通过", inspector, "第 " + task.getRoundNo() + " 轮复验合格，工序「"
                    + task.getStepName() + "」恢复并置为已完成");
        } else {
            // 复验不通过：返修轮次递增，退回待返修，游标继续停在「返修中」
            int nextRound = task.getRoundNo() + 1;
            task.setRoundNo(nextRound);
            task.setStatus("待返修");
            task.setRepairedAt(null);
            task = taskRepository.save(task);
            log(task, "复验不通过", inspector, "复验不合格" + (trim(req.getNote()) == null ? "" : "：" + trim(req.getNote()))
                    + "，返修轮次递增到第 " + nextRound + " 轮");
            // 游标已在「返修中」，重复置位保证刷新后口径一致
            moveStepToRepairing(task.getOrder().getId(), task.getStepName());
        }
        return task;
    }

    // ---------------- 工序级断言（SmtStepService / WorkOrderService 调用） ----------------

    /**
     * 工序推进前断言：该工序存在未闭环返修时，不能开工 / 完工 / 跳过。
     */
    public void assertStepUnblocked(Long orderId, String stepName, String action) {
        List<RepairTask> open = taskRepository.findOpenByOrderIdAndStepName(orderId, stepName);
        if (open.isEmpty()) {
            return;
        }
        RepairTask t = open.get(0);
        throw new BizException("工序「" + stepName + "」存在未闭环返修（第 " + t.getRoundNo()
                + " 轮，" + t.getStatus() + "），" + action + "被阻止：必须经质检员复验通过后才能继续");
    }

    /**
     * 工单推进前断言：工单存在任何未闭环返修时不能完成 / 跳过该工序所在环节。
     * @return 未闭环任务数
     */
    public int countOpenByOrder(Long orderId) {
        return taskRepository.findOpenByOrderId(orderId).size();
    }

    // ---------------- 历史旧数据补建 ----------------

    /**
     * 兼容历史质检数据：对所有 verdict=返修、repair_done=false 且还没有返修任务的
     * 旧质检记录补建返修任务（轮次 1，待返修），工序游标一并置「返修中」。
     * 幂等：唯一索引保证重复启动不会补出第二份。
     */
    @Transactional
    public int backfillLegacyTasks() {
        List<Inspection> openInspections = inspectionRepository.findByVerdictAndRepairDone("返修", false);
        int created = 0;
        for (Inspection insp : openInspections) {
            if (taskRepository.findByInspectionId(insp.getId()).isPresent()) {
                continue;
            }
            // 每条独立事务（经自身代理走 REQUIRES_NEW）：单条撞唯一索引只回滚该条，
            // 不把整个补建事务标记为 rollback-only，多实例并发启动也安全
            try {
                if (self.createOrReuseInNewTransaction(insp)) {
                    created++;
                }
            } catch (RuntimeException ignored) {
                // 同工单同工序已有在制任务 / 另一节点并发补过：跳过
            }
        }
        return created;
    }

    /** 代理入口：以独立事务补建单条历史返修任务，返回是否新建 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean createOrReuseInNewTransaction(Inspection insp) {
        if (taskRepository.findByInspectionId(insp.getId()).isPresent()) {
            return false;
        }
        createOrReuse(insp);
        return true;
    }

    /** 单条旧质检补建（旧接口 /inspections/{id}/repair 落到没有任务的旧记录时使用） */
    private RepairTask backfillOne(Long inspectionId) {
        Inspection insp = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new BizException("质检记录不存在：" + inspectionId));
        if (!"返修".equals(insp.getVerdict())) {
            throw new BizException("只有「返修」结论需要返修处理，当前结论：" + insp.getVerdict());
        }
        if (Boolean.TRUE.equals(insp.getRepairDone())) {
            throw new BizException("该返修单已闭环");
        }
        return createOrReuse(insp);
    }

    // ---------------- 内部 ----------------

    private WorkOrder lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("工单不存在：" + orderId));
    }

    /** 拿工单锁之后加锁重读任务行：避免拿锁前的快照是旧状态（重复点击 / 并发复验） */
    private RepairTask reloadLocked(Long taskId) {
        RepairTask task = get(taskId);
        em.refresh(task, LockModeType.PESSIMISTIC_WRITE);
        return task;
    }

    /** 把对应工序游标置为「返修中」（已完成的不合格工序也要回炉停线） */
    private void moveStepToRepairing(Long orderId, String stepName) {
        SmtStep step = stepRepository.findByOrderIdAndStepName(orderId, stepName)
                .orElseThrow(() -> new BizException("工单尚未铺开工序，无法返修：" + stepName));
        if (!"返修中".equals(step.getStatus())) {
            step.setStatus("返修中");
            step.setFinishedAt(null);
            stepRepository.save(step);
        }
    }

    /** 复验通过：返修中 → 已完成（返修闭环推进工序的唯一通道） */
    private void moveStepToFinished(Long orderId, String stepName) {
        SmtStep step = stepRepository.findByOrderIdAndStepName(orderId, stepName)
                .orElseThrow(() -> new BizException("工单尚未铺开工序，无法恢复：" + stepName));
        if (!"返修中".equals(step.getStatus())) {
            // 理论上不会发生：任务在制时游标必然停在返修中；保守起见只允许从返修中恢复
            throw new BizException("工序「" + stepName + "」当前为「" + step.getStatus()
                    + "」，与返修任务状态不一致，请刷新后重试");
        }
        step.setStatus("已完成");
        step.setFinishedAt(LocalDateTime.now());
        stepRepository.save(step);
    }

    private void log(RepairTask task, String action, String actor, String detail) {
        RepairEvent e = new RepairEvent();
        e.setTask(task);
        e.setAction(action);
        e.setRoundNo(task.getRoundNo());
        e.setActor(actor);
        e.setDetail(detail);
        e.setCreatedAt(LocalDateTime.now());
        eventRepository.save(e);
    }

    private String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** 某工单返修阻塞明细（质检页 / 放行口径复用） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> openTasksOfOrder(Long orderId) {
        List<RepairTask> tasks = taskRepository.findOpenByOrderId(orderId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RepairTask t : tasks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskId", t.getId());
            row.put("orderId", t.getOrder() == null ? null : t.getOrder().getId());
            row.put("inspectionId", t.getInspection() == null ? null : t.getInspection().getId());
            row.put("stepName", t.getStepName());
            row.put("roundNo", t.getRoundNo());
            row.put("status", t.getStatus());
            row.put("repairBy", t.getRepairBy());
            out.add(row);
        }
        return out;
    }
}
