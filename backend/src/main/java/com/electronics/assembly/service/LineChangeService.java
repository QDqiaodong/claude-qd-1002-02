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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.common.Dict;
import com.electronics.assembly.common.StaleSnapshotException;
import com.electronics.assembly.dto.LineChangeRequest;
import com.electronics.assembly.entity.LineChangeBatch;
import com.electronics.assembly.entity.LineChangeEvent;
import com.electronics.assembly.entity.OrderLineConfig;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.repository.LineChangeBatchRepository;
import com.electronics.assembly.repository.LineChangeEventRepository;
import com.electronics.assembly.repository.OrderLineConfigRepository;
import com.electronics.assembly.repository.WorkOrderRepository;

/**
 * 换线首件放行：配置版本、首件确认与 SMT 工序推进受同一状态约束。
 *
 * <p>核心规则：① 已投产工单更换设备/飞达/关键参数必须先建换线批次（配置快照+版本号），
 * 旧有效批次同时失效；② 批次按 待首件→待确认→已放行/已驳回 流转，驳回必须填原因，
 * 操作员可修正后生成新版本重新送检；③ 提交人与确认人不得同一人；
 * ④ 未放行批次阻塞贴片工序（开工与完工），不影响已完成的历史工序；
 * ⑤ 确认时配置已被再次修改则确认失败、批次失效，不能用过期快照放行。</p>
 *
 * <p>并发口径：所有写操作先按工单 id 悲观锁工单行，再锁重读批次行与配置行，
 * 「操作员改配置」与「质检员确认」被串行化；重复点击 / 刷新 / 重新进入
 * 都不会出现两个有效版本（数据库另有 active_order_id 唯一索引兜底）。</p>
 */
@Service
public class LineChangeService {

    @PersistenceContext
    private EntityManager em;

    private final LineChangeBatchRepository batchRepository;
    private final LineChangeEventRepository eventRepository;
    private final OrderLineConfigRepository configRepository;
    private final WorkOrderRepository orderRepository;

    public LineChangeService(LineChangeBatchRepository batchRepository,
                             LineChangeEventRepository eventRepository,
                             OrderLineConfigRepository configRepository,
                             WorkOrderRepository orderRepository) {
        this.batchRepository = batchRepository;
        this.eventRepository = eventRepository;
        this.configRepository = configRepository;
        this.orderRepository = orderRepository;
    }

    // ---------------- 查询 ----------------

    public List<LineChangeBatch> list(Long orderId, String status) {
        if (orderId != null) {
            return batchRepository.findByOrderIdOrderByVersionNoDesc(orderId);
        }
        if (status != null && !status.isBlank()) {
            return batchRepository.findByStatus(status);
        }
        return batchRepository.findAll();
    }

    public LineChangeBatch get(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new BizException("换线批次不存在：" + id));
    }

    /**
     * 工单换线上下文：当前配置、当前批次、阻塞原因、处理人、下一步动作、
     * 配置差异、全部批次与完整处理记录——工单页与换线页共用。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> context(Long orderId) {
        WorkOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException("工单不存在：" + orderId));
        OrderLineConfig config = configRepository.findById(orderId).orElse(null);
        List<LineChangeBatch> batches = batchRepository.findByOrderIdOrderByVersionNoDesc(orderId);
        LineChangeBatch current = batches.isEmpty() ? null : batches.get(0);
        List<LineChangeEvent> events = eventRepository.findByOrderId(orderId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", order.getId());
        out.put("orderNo", order.getOrderNo());
        out.put("orderStatus", order.getStatus());
        out.put("config", config);
        out.put("current", current);
        out.put("blocked", isBlocked(current));
        out.put("handler", current == null ? null : handlerOf(current));
        out.put("nextAction", current == null ? null : nextActionOf(current));
        out.put("stale", current != null && config != null
                && !config.getConfigHash().equals(current.getConfigHash()));
        out.put("diff", diffOf(batches));
        out.put("batches", batches);
        out.put("events", events);
        return out;
    }

    /** 工单换线阻塞信息（SMT 工序页横幅用） */
    @Transactional(readOnly = true)
    public Map<String, Object> blocking(Long orderId) {
        Optional<LineChangeBatch> latest = batchRepository.findTopByOrderIdOrderByVersionNoDesc(orderId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("blocked", latest.map(this::isBlocked).orElse(false));
        out.put("batch", latest.orElse(null));
        out.put("handler", latest.map(this::handlerOf).orElse(null));
        out.put("nextAction", latest.map(this::nextActionOf).orElse(null));
        return out;
    }

    /** 各工单最新换线批次一览（工单列表页角标用） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> summary() {
        Map<Long, LineChangeBatch> latest = new LinkedHashMap<>();
        for (LineChangeBatch b : batchRepository.findAll()) {
            LineChangeBatch cur = latest.get(b.getOrder().getId());
            if (cur == null || b.getVersionNo() > cur.getVersionNo()) {
                latest.put(b.getOrder().getId(), b);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, LineChangeBatch> e : latest.entrySet()) {
            LineChangeBatch b = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", e.getKey());
            row.put("versionNo", b.getVersionNo());
            row.put("status", b.getStatus());
            row.put("blocked", isBlocked(b));
            row.put("handler", handlerOf(b));
            row.put("nextAction", nextActionOf(b));
            out.add(row);
        }
        return out;
    }

    // ---------------- 写操作：统一先锁工单行串行化 ----------------

    /**
     * 建立换线批次（更换设备/飞达/关键参数触发）。
     * 配置与当前生效配置一致时幂等返回在途批次，防止重复点击刷出多个版本。
     */
    @Transactional
    public LineChangeBatch create(LineChangeRequest req) {
        if (req.getOrderId() == null) {
            throw new BizException("必须指定工单");
        }
        WorkOrder order = lockOrder(req.getOrderId());
        if (!"贴片中".equals(order.getStatus())) {
            throw new BizException("只有「贴片中」的已投产工单能建立换线批次，当前状态：" + order.getStatus());
        }
        String equipment = requireText(req.getEquipmentNo(), "生产设备");
        String feeder = requireText(req.getFeederConfig(), "飞达配置");
        String params = requireText(req.getKeyParams(), "关键工艺参数");
        String operator = requireText(req.getOperator(), "操作员");
        String reason = requireText(req.getReason(), "换线原因");

        OrderLineConfig config = configRepository.findByIdForUpdate(order.getId()).orElse(null);
        String newHash = Dict.configHash(equipment, feeder, params);
        if (config != null && newHash.equals(config.getConfigHash())) {
            // 配置没变：已有在途批次则幂等返回（重复点击/刷新重提），否则无需换线
            List<LineChangeBatch> active = batchRepository
                    .findByOrderIdAndStatusInOrderByVersionNoDesc(order.getId(), Dict.LINE_CHANGE_ACTIVE);
            if (!active.isEmpty()) {
                return active.get(0);
            }
            throw new BizException("配置与当前生效配置一致，无需建立换线批次");
        }
        return createBatchLocked(order, config, equipment, feeder, params, reason, operator, "建批", reason);
    }

    /** 操作员提交首件检测结果：待首件 → 待确认，之后只能等质检员确认 */
    @Transactional(noRollbackFor = StaleSnapshotException.class)
    public LineChangeBatch submitFirstPiece(Long batchId, LineChangeRequest req) {
        LineChangeBatch probe = get(batchId);
        lockOrder(probe.getOrder().getId());
        LineChangeBatch batch = reloadLocked(batchId);
        if (!"待首件".equals(batch.getStatus())) {
            throw new BizException("批次当前状态为「" + batch.getStatus() + "」，不能提交首件");
        }
        assertSnapshotFresh(batch);
        if (!Dict.isValidFirstResult(req.getFirstResult())) {
            throw new BizException("首件检测结果只能是：合格 / 不合格");
        }
        String operator = requireText(req.getOperator(), "操作员");

        batch.setFirstResult(req.getFirstResult());
        batch.setFirstNote(req.getFirstNote());
        batch.setSubmittedBy(operator);
        batch.setSubmittedAt(LocalDateTime.now());
        batch.setStatus("待确认");
        batch = batchRepository.save(batch);
        log(batch, "提交首件", operator, "首件检测「" + req.getFirstResult() + "」"
                + (req.getFirstNote() == null || req.getFirstNote().isBlank() ? "" : "：" + req.getFirstNote()));
        return batch;
    }

    /**
     * 质检员确认放行：待确认 → 已放行。
     * 确认时配置已被再次修改的，确认失败并把批次标为已失效——不允许用过期快照放行。
     * （StaleSnapshotException 不回滚：失效标记必须落库。）
     */
    @Transactional(noRollbackFor = StaleSnapshotException.class)
    public LineChangeBatch confirm(Long batchId, LineChangeRequest req) {
        LineChangeBatch probe = get(batchId);
        lockOrder(probe.getOrder().getId());
        LineChangeBatch batch = reloadLocked(batchId);
        if (!"待确认".equals(batch.getStatus())) {
            throw new BizException("批次当前状态为「" + batch.getStatus() + "」，不能确认放行");
        }
        // 规则⑤：快照过期即失效，确认失败
        assertSnapshotFresh(batch);
        String inspector = requireText(req.getInspector(), "质检员");
        // 规则③：提交人与确认人不得同一人
        if (inspector.equals(batch.getSubmittedBy())) {
            throw new BizException("提交人与确认人不得是同一人：" + inspector);
        }
        batch.setStatus("已放行");
        batch.setConfirmedBy(inspector);
        batch.setConfirmedAt(LocalDateTime.now());
        batch = batchRepository.save(batch);
        log(batch, "确认放行", inspector, "首件确认通过，恢复批量贴片");
        return batch;
    }

    /** 质检员驳回：待确认 → 已驳回，必须填写原因 */
    @Transactional(noRollbackFor = StaleSnapshotException.class)
    public LineChangeBatch reject(Long batchId, LineChangeRequest req) {
        LineChangeBatch probe = get(batchId);
        lockOrder(probe.getOrder().getId());
        LineChangeBatch batch = reloadLocked(batchId);
        if (!"待确认".equals(batch.getStatus())) {
            throw new BizException("批次当前状态为「" + batch.getStatus() + "」，不能驳回");
        }
        assertSnapshotFresh(batch);
        String inspector = requireText(req.getInspector(), "质检员");
        if (inspector.equals(batch.getSubmittedBy())) {
            throw new BizException("提交人与确认人不得是同一人：" + inspector);
        }
        String reason = requireText(req.getReason(), "驳回原因");
        batch.setStatus("已驳回");
        batch.setConfirmedBy(inspector);
        batch.setConfirmedAt(LocalDateTime.now());
        batch.setRejectReason(reason);
        batch = batchRepository.save(batch);
        log(batch, "驳回", inspector, reason);
        return batch;
    }

    /** 驳回后修正重提：以修正后的配置生成新版本批次（版本号 +1），重新走首件流程 */
    @Transactional
    public LineChangeBatch revise(Long batchId, LineChangeRequest req) {
        LineChangeBatch probe = get(batchId);
        WorkOrder order = lockOrder(probe.getOrder().getId());
        LineChangeBatch batch = reloadLocked(batchId);
        if (!"已驳回".equals(batch.getStatus())) {
            throw new BizException("只有「已驳回」的批次能修正重提，当前状态：" + batch.getStatus());
        }
        String equipment = requireText(req.getEquipmentNo(), "生产设备");
        String feeder = requireText(req.getFeederConfig(), "飞达配置");
        String params = requireText(req.getKeyParams(), "关键工艺参数");
        String operator = requireText(req.getOperator(), "操作员");
        String note = requireText(req.getNote(), "修正说明");

        OrderLineConfig config = configRepository.findByIdForUpdate(order.getId()).orElse(null);
        return createBatchLocked(order, config, equipment, feeder, params, note, operator,
                "修正重提", "源自 V" + batch.getVersionNo() + " 驳回：" + batch.getRejectReason() + "；修正：" + note);
    }

    // ---------------- 贴片阻塞断言（SmtStepService 调用） ----------------

    /**
     * 贴片工序开工/完工前断言：工单存在换线批次且最新批次未放行时，
     * 阻止工单从上料推进到贴片；已完成的历史工序不受影响（它们本就是终态）。
     */
    public void assertStepReleased(Long orderId, String stepName, String action) {
        if (!"贴片".equals(stepName)) {
            return;
        }
        Optional<LineChangeBatch> latest = batchRepository.findTopByOrderIdOrderByVersionNoDesc(orderId);
        if (latest.isEmpty() || "已放行".equals(latest.get().getStatus())) {
            return;
        }
        LineChangeBatch b = latest.get();
        throw new BizException("换线批次 V" + b.getVersionNo() + "（" + b.getStatus() + "）未放行，贴片"
                + action + "被阻止：" + nextActionOf(b) + "；处理人：" + handlerOf(b));
    }

    // ---------------- 内部 ----------------

    /** 锁工单行：同一工单的所有换线写操作在此串行化 */
    private WorkOrder lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("工单不存在：" + orderId));
    }

    /** 拿到工单锁之后加锁重读批次：避免拿锁前的快照是旧状态（重复点击/并发确认） */
    private LineChangeBatch reloadLocked(Long batchId) {
        LineChangeBatch batch = get(batchId);
        em.refresh(batch, LockModeType.PESSIMISTIC_WRITE);
        return batch;
    }

    /**
     * 建批核心（调用方已持有工单锁）：旧有效批次全部失效 → 插入新版本 → 更新当前配置。
     * 先 flush 旧批次的失效再插新行，避开 active_order_id 唯一索引冲突。
     */
    private LineChangeBatch createBatchLocked(WorkOrder order, OrderLineConfig config,
                                              String equipment, String feeder, String params,
                                              String reason, String operator, String action, String detail) {
        Long orderId = order.getId();
        int nextVersion = batchRepository.maxVersionNo(orderId) + 1;

        List<LineChangeBatch> activeList = batchRepository
                .findByOrderIdAndStatusInOrderByVersionNoDesc(orderId, Dict.LINE_CHANGE_ACTIVE);
        for (LineChangeBatch old : activeList) {
            old.setStatus("已失效");
            batchRepository.saveAndFlush(old);
            log(old, "批次失效", operator, "配置变更，被 V" + nextVersion + " 取代");
        }

        LineChangeBatch batch = new LineChangeBatch();
        batch.setOrder(order);
        batch.setVersionNo(nextVersion);
        batch.setStatus("待首件");
        batch.setEquipmentNo(equipment);
        batch.setFeederConfig(feeder);
        batch.setKeyParams(params);
        batch.setConfigHash(Dict.configHash(equipment, feeder, params));
        batch.setChangeReason(reason);
        batch.setCreatedBy(operator);
        batch.setCreatedAt(LocalDateTime.now());
        batch = batchRepository.save(batch);

        if (config == null) {
            config = new OrderLineConfig();
            config.setOrderId(orderId);
        }
        config.setEquipmentNo(equipment);
        config.setFeederConfig(feeder);
        config.setKeyParams(params);
        config.setConfigHash(batch.getConfigHash());
        config.setUpdatedBy(operator);
        config.setUpdatedAt(LocalDateTime.now());
        configRepository.save(config);

        log(batch, action, operator, detail);
        return batch;
    }

    /** 快照新鲜度断言：批次快照与工单当前配置不一致 → 批次标已失效并失败（异常不回滚，失效落库） */
    private void assertSnapshotFresh(LineChangeBatch batch) {
        OrderLineConfig config = configRepository.findByIdForUpdate(batch.getOrder().getId()).orElse(null);
        if (config != null && !config.getConfigHash().equals(batch.getConfigHash())) {
            batch.setStatus("已失效");
            batchRepository.save(batch);
            log(batch, "批次失效", "系统", "配置已再次变更，快照过期");
            throw new StaleSnapshotException("设备/飞达/关键参数已再次修改，批次 V" + batch.getVersionNo()
                    + " 已失效，请按最新配置重新送检");
        }
    }

    private void log(LineChangeBatch batch, String action, String actor, String detail) {
        LineChangeEvent e = new LineChangeEvent();
        e.setBatch(batch);
        e.setAction(action);
        e.setActor(actor);
        e.setDetail(detail);
        e.setCreatedAt(LocalDateTime.now());
        eventRepository.save(e);
    }

    private boolean isBlocked(LineChangeBatch latest) {
        return latest != null && !"已放行".equals(latest.getStatus());
    }

    /** 当前由谁处理 */
    private String handlerOf(LineChangeBatch b) {
        switch (b.getStatus()) {
            case "待首件":
                return "操作员 " + b.getCreatedBy();
            case "待确认":
                return "质检员（提交人 " + b.getSubmittedBy() + " 不能自审）";
            case "已驳回":
                return "操作员 " + b.getCreatedBy();
            default:
                return "—";
        }
    }

    /** 下一步可执行动作 */
    private String nextActionOf(LineChangeBatch b) {
        switch (b.getStatus()) {
            case "待首件":
                return "提交首件检测结果";
            case "待确认":
                return "质检员确认放行或驳回";
            case "已驳回":
                return "修正后生成新版本重新送检";
            case "已放行":
                return "已放行，可恢复批量贴片";
            default:
                return "已被新版本取代";
        }
    }

    /** 当前批次与上一版批次的配置差异 */
    private List<Map<String, Object>> diffOf(List<LineChangeBatch> batchesDesc) {
        List<Map<String, Object>> diff = new ArrayList<>();
        if (batchesDesc.isEmpty()) {
            return diff;
        }
        LineChangeBatch cur = batchesDesc.get(0);
        LineChangeBatch prev = batchesDesc.size() > 1 ? batchesDesc.get(1) : null;
        diff.add(diffRow("生产设备", prev == null ? null : prev.getEquipmentNo(), cur.getEquipmentNo()));
        diff.add(diffRow("飞达配置", prev == null ? null : prev.getFeederConfig(), cur.getFeederConfig()));
        diff.add(diffRow("关键参数", prev == null ? null : prev.getKeyParams(), cur.getKeyParams()));
        return diff;
    }

    private Map<String, Object> diffRow(String label, String from, String to) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("from", from);
        row.put("to", to);
        row.put("changed", from == null ? to != null : !from.equals(to));
        return row;
    }

    private String requireText(String v, String label) {
        if (v == null || v.isBlank()) {
            throw new BizException(label + "不能为空");
        }
        return v.trim();
    }
}
