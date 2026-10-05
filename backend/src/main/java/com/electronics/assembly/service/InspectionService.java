package com.electronics.assembly.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.common.Dict;
import com.electronics.assembly.dto.RepairTaskRequest;
import com.electronics.assembly.entity.Inspection;
import com.electronics.assembly.entity.RepairTask;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.repository.InspectionRepository;
import com.electronics.assembly.repository.WorkOrderRepository;

/**
 * 质检与返修业务。
 *
 * <p>核心规则：① 不良点数超过 {@link Dict#MAX_DEFECT_POINTS} 自动判废，
 * 有缺陷但未超阈值判返修，无缺陷判合格；② 判返修必须走完返修闭环才能放行；
 * ③ 抽检板数必须大于 0。</p>
 *
 * <p>「质检不合格 → 返修」闭环：提交「返修」结论时在同一事务内以本质检记录幂等
 * 创建或复用返修任务（{@link RepairTaskService#createOrReuse}），并把对应工序游标
 * 置为「返修中」；返修工完工后转「待复验」，只能由质检员复验——通过才闭环并把工序
 * 推进到「已完成」，不通过则返修轮次 +1。前端提交带 requestId 幂等号，同号重放
 * （双击 / 网络重试）直接返回首条记录，不产生第二条质检与第二个返修任务。</p>
 */
@Service
public class InspectionService {

    private final InspectionRepository inspectionRepository;
    private final WorkOrderRepository orderRepository;
    private final RepairTaskService repairTaskService;

    public InspectionService(InspectionRepository inspectionRepository,
                             WorkOrderRepository orderRepository,
                             RepairTaskService repairTaskService) {
        this.inspectionRepository = inspectionRepository;
        this.orderRepository = orderRepository;
        this.repairTaskService = repairTaskService;
    }

    public List<Inspection> list(Long orderId, String verdict) {
        if (orderId != null) {
            return inspectionRepository.findByOrderId(orderId);
        }
        if (verdict != null && !verdict.isBlank()) {
            return inspectionRepository.findByVerdict(verdict);
        }
        return inspectionRepository.findAll();
    }

    public Inspection get(Long id) {
        return inspectionRepository.findById(id)
                .orElseThrow(() -> new BizException("质检记录不存在：" + id));
    }

    /**
     * 登记一次质检，结论按不良点数自动判定。
     *
     * <p>幂等：请求带 requestId 时，同号重复提交（双击 / 网络重试）返回首次登记的
     * 同一条记录，不重复建返修任务。判「返修」时同事务创建返修任务并把工序游标
     * 置「返修中」。</p>
     */
    @Transactional
    public Inspection create(Inspection input) {
        if (input.getOrder() == null || input.getOrder().getId() == null) {
            throw new BizException("必须指定所属工单");
        }
        Long orderId = input.getOrder().getId();
        String requestId = input.getRequestId() == null || input.getRequestId().isBlank()
                ? null : input.getRequestId().trim();

        // 快速路径：未加锁先判一次幂等重放，命中直接返回
        if (requestId != null) {
            var replay = inspectionRepository.findByRequestId(requestId);
            if (replay.isPresent()) {
                return replay.get();
            }
        }

        // 锁工单行串行化同一工单的质检提交：并发双击 / 网络重试在这里排队
        WorkOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("工单不存在：" + orderId));
        // 锁内再判一次幂等号：前一个持锁事务可能已带着相同 requestId 提交
        if (requestId != null) {
            var replay = inspectionRepository.findByRequestId(requestId);
            if (replay.isPresent()) {
                return replay.get();
            }
        }

        if (!Dict.isValidStep(input.getStepName())) {
            throw new BizException("工序不合法：" + input.getStepName());
        }
        if (!Dict.isValidDefectType(input.getDefectType())) {
            throw new BizException("缺陷类型不合法：" + input.getDefectType());
        }
        if (input.getSampleQty() == null || input.getSampleQty() <= 0) {
            throw new BizException("抽检板数必须大于 0");
        }
        int points = input.getDefectPoints() == null ? 0 : input.getDefectPoints();
        if (points < 0) {
            throw new BizException("不良点数不能为负数");
        }

        Inspection insp = new Inspection();
        insp.setOrder(order);
        insp.setStepName(input.getStepName());
        insp.setDefectType(input.getDefectType());
        insp.setDefectPoints(points);
        insp.setSampleQty(input.getSampleQty());
        // 规则①：按不良点数自动判定
        insp.setVerdict(Dict.decideVerdict(points));
        insp.setInspector(input.getInspector() == null ? "未署名" : input.getInspector());
        insp.setRepairDone(!"返修".equals(insp.getVerdict()));
        insp.setRequestId(requestId);
        insp.setCheckedAt(LocalDateTime.now());
        insp = inspectionRepository.saveAndFlush(insp);

        // 判返修：同事务幂等建/复用返修任务，并把对应工序游标置「返修中」。
        // 建任务内部有 (inspection_id) / (order_id, active_step) 两道唯一索引兜底，
        // 保证重复点击或网络重试不会产生重复返修任务、不同工单不串单。
        if ("返修".equals(insp.getVerdict())) {
            repairTaskService.createOrReuse(insp);
        }
        return insp;
    }

    /**
     * 旧接口「返修闭环」按钮：POST /inspections/{id}/repair。
     *
     * <p>新闭环语义下该动作是返修工上报完工（待返修 → 待复验），仍返回原 Inspection
     * 实体，响应结构与旧接口保持一致；空体可调用、重复上报幂等。最终闭环只能走
     * 质检员复验 {@code POST /repair-tasks/{id}/recheck}。</p>
     */
    @Transactional
    public Inspection repair(Long id) {
        return repair(id, new RepairTaskRequest());
    }

    /** 同上，允许带返修工署名（新前端使用） */
    @Transactional
    public Inspection repair(Long id, RepairTaskRequest req) {
        RepairTask task = repairTaskService.completeByInspection(id, req);
        // 返回质检记录本身，旧接口响应结构不变
        return get(task.getInspection().getId());
    }

    /** 未闭环的返修单（旧口径：verdict=返修 且 repair_done=false） */
    public List<Inspection> openRepairs() {
        return inspectionRepository.findByVerdictAndRepairDone("返修", false);
    }

    /**
     * 某工单是否存在放行阻塞：有判废记录，或有未闭环的返修任务。
     * 返回字段与旧接口保持一致（orderId/scrapped/openRepairs/blocked）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> blocking(Long orderId) {
        List<Inspection> all = inspectionRepository.findByOrderId(orderId);
        int scrapped = 0;
        for (Inspection i : all) {
            if ("判废".equals(i.getVerdict())) {
                scrapped++;
            }
        }
        // 以返修任务为闭环事实来源；历史数据补建后与 repair_done=false 口径一致
        int open = repairTaskService.countOpenByOrder(orderId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("scrapped", scrapped);
        out.put("openRepairs", open);
        out.put("blocked", scrapped > 0 || open > 0);
        out.put("openTasks", repairTaskService.openTasksOfOrder(orderId));
        return out;
    }
}
