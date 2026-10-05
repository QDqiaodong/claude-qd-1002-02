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
 * 换线批次：工单更换生产设备、飞达配置或关键工艺参数时建立，
 * 携带配置快照与版本号，经首件确认放行后才能恢复批量贴片。
 *
 * <p>状态流转：待首件 → 待确认 → 已放行 / 已驳回；配置再次变更或确认时
 * 发现快照过期则置为已失效。已驳回可修正后生成新版本重新送检。
 * 同一工单同一时刻最多一个有效批次（待首件/待确认/已放行），由
 * active_order_id 生成列 + 唯一索引在数据库层面兜底。</p>
 */
@Entity
@Table(name = "line_change_batch")
@Getter
@Setter
public class LineChangeBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 不加 @JsonIgnore：要能接收 {"order":{"id":n}} 入参；WorkOrder 没有反向集合，序列化不会成环
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    private WorkOrder order;

    /** 批次版本号，每工单递增 */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** 待首件 / 待确认 / 已放行 / 已驳回 / 已失效 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 生产设备快照 */
    @Column(name = "equipment_no", nullable = false, length = 32)
    private String equipmentNo;

    /** 飞达配置快照 */
    @Column(name = "feeder_config", nullable = false)
    private String feederConfig;

    /** 关键工艺参数快照 */
    @Column(name = "key_params", nullable = false)
    private String keyParams;

    /** 配置快照哈希，确认放行时与工单当前配置比对 */
    @Column(name = "config_hash", nullable = false, length = 64)
    private String configHash;

    /** 换线 / 修正原因 */
    @Column(name = "change_reason")
    private String changeReason;

    /** 建批操作员 */
    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 首件检测结果：合格 / 不合格 */
    @Column(name = "first_result", length = 16)
    private String firstResult;

    /** 首件检测数据 */
    @Column(name = "first_note")
    private String firstNote;

    /** 首件提交人（操作员） */
    @Column(name = "submitted_by", length = 32)
    private String submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /** 确认质检员，不得与提交人同人 */
    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 驳回原因（驳回必填） */
    @Column(name = "reject_reason")
    private String rejectReason;

    /** 所属工单号，派生字段 */
    @Transient
    public String getOrderNo() {
        return order == null ? null : order.getOrderNo();
    }

    /** 是否为有效批次（在途或当前生效的放行），派生字段 */
    @Transient
    public boolean isActive() {
        return "待首件".equals(status) || "待确认".equals(status) || "已放行".equals(status);
    }
}
