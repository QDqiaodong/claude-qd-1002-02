package com.electronics.assembly.entity;

import java.time.LocalDateTime;

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

/** 制造批次数量与状态调整审计记录。 */
@Entity
@Table(name = "batch_adjustment_event")
@Getter
@Setter
public class BatchAdjustmentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 保留 order_id：批次被删除后仍可在母工单下查看调整记录。 */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private ProductionBatch batch;

    @Column(name = "batch_no", length = 36)
    private String batchNo;

    /** 拆分 / 调整 / 删除 / 取消 / 回收 / 减产 / 历史迁移 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "before_qty")
    private Integer beforeQty;

    @Column(name = "after_qty")
    private Integer afterQty;

    @Column(name = "related_batch_id")
    private Long relatedBatchId;

    @Column(name = "related_batch_no", length = 36)
    private String relatedBatchNo;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "detail", length = 255)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Transient
    public Long getBatchId() {
        return batch == null ? null : batch.getId();
    }
}
