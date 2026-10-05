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
 * 换线处理记录：批次每一次状态变迁都留痕（建批 / 提交首件 / 确认放行 /
 * 驳回 / 批次失效 / 修正重提），构成工单换线的完整审计链。
 */
@Entity
@Table(name = "line_change_event")
@Getter
@Setter
public class LineChangeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 不加 @JsonIgnore：页面要按批次展开处理记录；Batch 没有反向集合，序列化不会成环
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "batch_id", nullable = false)
    private LineChangeBatch batch;

    /** 建批 / 提交首件 / 确认放行 / 驳回 / 批次失效 / 修正重提 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    /** 操作人（操作员 / 质检员 / 系统） */
    @Column(name = "actor", nullable = false, length = 32)
    private String actor;

    @Column(name = "detail")
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 所属批次版本号，派生字段 */
    @Transient
    public Integer getBatchVersionNo() {
        return batch == null ? null : batch.getVersionNo();
    }
}
