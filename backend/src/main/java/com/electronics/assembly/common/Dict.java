package com.electronics.assembly.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * 电子装配厂词典与业务规则参数表：工单状态与优先级、物料封装与飞达位、
 * SMT 工序流转、缺陷类型与判定结论，以及齐套率 / 缺陷率各类阈值。
 *
 * <p>规则集中在此，便于出题时被要求调整或扩展。</p>
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 生产工单 ----------------
    /** 待投产 → 贴片中 → 待检验 → 已完成，逐级推进，不可跳级 */
    public static final List<String> ORDER_FLOW = List.of("待投产", "贴片中", "待检验", "已完成");

    public static final List<String> ORDER_STATUSES = List.of("待投产", "贴片中", "待检验", "已完成", "已取消");

    public static final List<String> PRIORITIES = List.of("高", "中", "低");

    // ---------------- 物料 ----------------
    /** 封装 */
    public static final List<String> PACKAGES = List.of("0201", "0402", "0603", "0805", "SOT", "QFP", "BGA");

    /** 物料状态：正常 / 缺料 / 停用 */
    public static final List<String> COMPONENT_STATUSES = List.of("正常", "缺料", "停用");

    // ---------------- SMT 工序 ----------------
    public static final List<String> STEPS = List.of("上料", "贴片", "回流焊", "AOI", "目检");

    /**
     * 工序状态：待执行 / 进行中 / 已完成，外加返修闭环专用的「返修中」游标。
     * 返修中由「质检不合格 → 建返修任务」自动置上，只能由质检复验推进到「已完成」。
     */
    public static final List<String> STEP_STATUSES = List.of("待执行", "进行中", "已完成", "返修中");

    private static final Map<String, List<String>> STEP_NEXT = Map.of(
            "待执行", List.of("进行中"),
            "进行中", List.of("已完成", "待执行"),
            // 返修中是闭环游标：只进不出，且只能经质检复验置为已完成（走 recheck 通道，不经常规流转）
            "返修中", List.of(),
            "已完成", List.of());

    // ---------------- 质检与返修 ----------------
    public static final List<String> DEFECT_TYPES = List.of("少件", "偏移", "虚焊", "连锡", "反向");

    /** 判定结论：合格 / 返修 / 判废 */
    public static final List<String> VERDICTS = List.of("合格", "返修", "判废");

    // ---------------- 返修任务 ----------------
    /** 返修任务状态：待返修（已建任务等返修工）→ 待复验（返修完成等质检员）→ 已复验通过（终态） */
    public static final List<String> REPAIR_STATUSES = List.of("待返修", "待复验", "已复验通过");

    public static boolean isValidRepairStatus(String status) {
        return status != null && REPAIR_STATUSES.contains(status);
    }

    // ---------------- 换线首件放行 ----------------
    /** 换线批次状态：待首件 → 待确认 → 已放行 / 已驳回；已失效为旁路终态 */
    public static final List<String> LINE_CHANGE_STATUSES = List.of("待首件", "待确认", "已放行", "已驳回", "已失效");

    /**
     * 有效批次状态（在途或当前生效的放行）：与 line_change_batch.active_order_id
     * 生成列的口径一致，同一工单同一时刻最多一个有效批次。
     */
    public static final List<String> LINE_CHANGE_ACTIVE = List.of("待首件", "待确认", "已放行");

    /** 首件检测结果：合格 / 不合格 */
    public static final List<String> FIRST_RESULTS = List.of("合格", "不合格");

    /** 换线处理记录动作 */
    public static final List<String> LINE_CHANGE_ACTIONS = List.of("建批", "提交首件", "确认放行", "驳回", "批次失效", "修正重提");

    // ---------------- 业务阈值 ----------------
    /** 齐套率低于本值不允许投产（%） */
    public static final double MIN_KIT_RATE = 90.0;

    /** 单板不良点数上限，超过判废 */
    public static final int MAX_DEFECT_POINTS = 12;

    /** 单工序缺陷率上限（‰），超过必须返修 */
    public static final double MAX_DEFECT_RATE = 30.0;

    /** 单次领料不得超过当天计划用量（%） */
    public static final int MAX_ISSUE_PERCENT = 120;

    // ---------------- 取值方法 ----------------

    public static boolean isValidOrderStatus(String status) {
        return status != null && ORDER_STATUSES.contains(status);
    }

    /** 工单状态只能逐级推进 */
    public static boolean canTransferOrder(String from, String to) {
        int i = ORDER_FLOW.indexOf(from);
        return i >= 0 && i + 1 < ORDER_FLOW.size() && ORDER_FLOW.get(i + 1).equals(to);
    }

    public static boolean isValidPriority(String p) {
        return p != null && PRIORITIES.contains(p);
    }

    public static boolean isValidPackage(String pkg) {
        return pkg != null && PACKAGES.contains(pkg);
    }

    public static boolean isValidComponentStatus(String status) {
        return status != null && COMPONENT_STATUSES.contains(status);
    }

    public static boolean isValidStep(String step) {
        return step != null && STEPS.contains(step);
    }

    public static int stepIndex(String step) {
        return STEPS.indexOf(step);
    }

    public static boolean isValidStepStatus(String status) {
        return status != null && STEP_STATUSES.contains(status);
    }

    public static boolean canTransferStep(String from, String to) {
        return STEP_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidDefectType(String type) {
        return type != null && DEFECT_TYPES.contains(type);
    }

    public static boolean isValidVerdict(String verdict) {
        return verdict != null && VERDICTS.contains(verdict);
    }

    public static boolean isValidLineChangeStatus(String status) {
        return status != null && LINE_CHANGE_STATUSES.contains(status);
    }

    public static boolean isValidFirstResult(String result) {
        return result != null && FIRST_RESULTS.contains(result);
    }

    /**
     * 配置快照哈希：SHA-256(设备|飞达|参数) 的小写十六进制。
     * 与 schema.sql 里 SHA2(CONCAT(设备,'|',飞达,'|',参数), 256) 口径一致，
     * 确认放行时用它比对批次快照与工单当前配置，防止用过期快照放行。
     */
    public static String configHash(String equipmentNo, String feederConfig, String keyParams) {
        String raw = equipmentNo + "|" + feederConfig + "|" + keyParams;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 按不良点数自动判定：超过阈值判废，有缺陷但未超判返修，无缺陷合格 */
    public static String decideVerdict(int defectPoints) {
        if (defectPoints <= 0) {
            return "合格";
        }
        if (defectPoints > MAX_DEFECT_POINTS) {
            return "判废";
        }
        return "返修";
    }
}
