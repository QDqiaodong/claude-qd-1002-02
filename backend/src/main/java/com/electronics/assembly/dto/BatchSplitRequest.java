package com.electronics.assembly.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 分批方案：首次启用分批 / 调整未投产批次方案时整份提交，整份守恒才保存，
 * 拆分数量不足、超额或出现空批次都会被整份拒绝。
 *
 * <p>行里带 id 的表示更新既有未投产批次；不带 id 的表示新增批次。
 * 未出现在方案里的未投产批次视为删除（已开工 / 历史批次不允许出现在删除集合里）。</p>
 */
@Getter
@Setter
public class BatchSplitRequest {

    /** 操作人（计划员） */
    private String operator;

    /** 批次行：交付顺序由行顺序决定，服务端按 1..n 重新编号 */
    private List<Line> batches;

    @Getter
    @Setter
    public static class Line {
        /** 既有批次 id；null = 新增行 */
        private Long id;
        /** 计划数量，必须为正整数，空批次（0 / null）整份不保存 */
        private Integer planQty;
    }
}
