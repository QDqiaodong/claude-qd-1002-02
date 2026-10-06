package com.electronics.assembly.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 制造批次：母工单拆分出的独立生产单元，每批记录批次号、计划数量、交付顺序与状态，
 * 并独立走齐套、投产、五道 SMT 工序与质检。
 *
 * <p>状态流转与工单一致：待投产 → 贴片中 → 待检验 → 已完成（不可倒退、不可跳级），
 * 已取消为旁路终态。数量口径：{@code 0 ≤ doneQty ≤ inspectedQty ≤ reportedQty ≤ planQty}，
 * 已报工 / 已送检 / 已完成的数量在任何调整中都不得挪走。</p>
 *
 * <p>只读历史批次（{@code readOnly=true}）在首次启用分批时承接母工单原完成数量，
 * 冻结为已完成，不与新批次重复累计；母工单的完成数量、状态与交付进度由全部批次汇总。</p>
 */
@Entity
@Table(name = "production_batch")
@Getter
@Setter
public class ProductionBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 不加 @JsonIgnore：WorkOrder 没有反向集合，序列化不会成环
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    private WorkOrder order;

    /** 批次号：工单号-B序号（历史批次为 工单号-H） */
    @Column(name = "batch_no", nullable = false, unique = true, length = 32)
    private String batchNo;

    /** 交付顺序，每工单从 1 起连续（历史批次占 0 号位） */
    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    /** 本批计划数量 */
    @Column(name = "plan_qty", nullable = false)
    private Integer planQty;

    /** 已报工数量 */
    @Column(name = "reported_qty", nullable = false)
    private Integer reportedQty = 0;

    /** 已送检数量 */
    @Column(name = "inspected_qty", nullable = false)
    private Integer inspectedQty = 0;

    /** 已完成（合格入库）数量 */
    @Column(name = "done_qty", nullable = false)
    private Integer doneQty = 0;

    /** 取消时由计划员确认的减产数量（不再摊派到其它批次） */
    @Column(name = "reduce_qty", nullable = false)
    private Integer reduceQty = 0;

    /** 待投产 / 贴片中 / 待检验 / 已完成 / 已取消 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 只读历史批次：承接母工单启用分批前的完成数量 */
    @Column(name = "read_only", nullable = false)
    private Boolean readOnly = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 所属工单号，派生字段 */
    @Transient
    public String getOrderNo() {
        return order == null ? null : order.getOrderNo();
    }

    /** 尚未报工的可回收数量（取消时这部分可回收或确认减产） */
    @Transient
    public int getUnreportedQty() {
        return Math.max(0, planQty - nz(reportedQty));
    }

    /** 已报工未送检 */
    @Transient
    public int getPendingInspectionQty() {
        return Math.max(0, nz(reportedQty) - nz(inspectedQty));
    }

    /** 已送检未完成 */
    @Transient
    public int getPendingDoneQty() {
        return Math.max(0, nz(inspectedQty) - nz(doneQty));
    }

    /** 批次完成率（%），派生字段 */
    @Transient
    public Double getProgressRate() {
        if (planQty == null || planQty <= 0 || doneQty == null) {
            return 0.0;
        }
        return Math.round((doneQty * 1000.0 / planQty)) / 10.0;
    }

    /** 是否有效批次（计入数量守恒与母工单状态汇总）：历史批次也算有效，但不阻塞母工单完成 */
    @Transient
    public boolean isCancelled() {
        return "已取消".equals(status);
    }

    /** 批次是否已开工（任一数量已落账或状态已越过待投产）：开工后计划数量冻结、状态不可倒退 */
    @Transient
    public boolean isStarted() {
        return nz(reportedQty) > 0 || nz(inspectedQty) > 0 || nz(doneQty) > 0
                || !"待投产".equals(status) || Boolean.TRUE.equals(readOnly);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
