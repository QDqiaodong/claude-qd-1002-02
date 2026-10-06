package com.electronics.assembly.entity;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
 * 制造批次：母工单可在投产前拆成多个交付批次独立生产。
 *
 * <p>数量口径以本实体为准：plannedQty 参与守恒；reportedQty 已报工、
 * inspectedQty 已送检、completedQty 已完成分别保护不同阶段的在制数量，
 * 生产开始后不得从批次中挪走。历史批次只读，用于承接首次分批前的旧产量。</p>
 */
@Entity
@Table(name = "production_batch")
@Getter
@Setter
public class ProductionBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private WorkOrder order;

    /** 母工单内唯一批次号 */
    @Column(name = "batch_no", nullable = false, length = 36)
    private String batchNo;

    /** 计划板数；有效批次合计必须等于母工单 planQty */
    @Column(name = "plan_qty", nullable = false)
    private Integer planQty;

    /** 已报工但未必送检的板数 */
    @Column(name = "reported_qty", nullable = false)
    private Integer reportedQty;

    /** 已送到质检环节的板数 */
    @Column(name = "inspected_qty", nullable = false)
    private Integer inspectedQty;

    /** 质检完成并计入母工单完成数量的板数 */
    @Column(name = "completed_qty", nullable = false)
    private Integer completedQty;

    /** 交付顺序，从 1 开始，同一母工单内不重复 */
    @Column(name = "delivery_seq", nullable = false)
    private Integer deliverySeq;

    /** 待投产 / 贴片中 / 待检验 / 已完成 / 已取消 / 历史 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "readonly_history", nullable = false)
    private Boolean readonlyHistory;

    /** 取消/减产原因；取消批次的处理审计 */
    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Transient
    public Long getOrderId() {
        return order == null ? null : order.getId();
    }

    @Transient
    public int protectedQty() {
        return Math.max(Math.max(n(reportedQty), n(inspectedQty)), n(completedQty));
    }

    @Transient
    public int getRemainingQty() {
        return Math.max(0, n(planQty) - protectedQty());
    }

    @Transient
    public int getQtyDifference() {
        return n(completedQty) - n(planQty);
    }

    @Transient
    public Double getProgressRate() {
        if (planQty == null || planQty <= 0) {
            return 0.0;
        }
        return Math.round(n(completedQty) * 1000.0 / planQty) / 10.0;
    }

    @Transient
    public boolean isStarted() {
        return !"待投产".equals(status) || protectedQty() > 0;
    }

    /** 查询时装配，不入库：齐套率、工序进度和调整记录由服务层统一汇总。 */
    @Transient
    private Double kitRate;
    @Transient
    private Boolean kitOk;
    @Transient
    private Integer stepDoneCount;
    @Transient
    private Integer stepTotalCount;
    @Transient
    private List<BatchAdjustmentEvent> events;

    private int n(Integer v) {
        return v == null ? 0 : v;
    }
}
