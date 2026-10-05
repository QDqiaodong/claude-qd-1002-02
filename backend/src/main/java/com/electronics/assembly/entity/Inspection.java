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

/**
 * 质检与返修记录。
 *
 * <p>不良点数超过阈值自动判废，有缺陷但未超阈值判返修，无缺陷判合格；
 * 判返修的必须走完返修闭环（repairDone）才能放行。</p>
 */
@Entity
@Table(name = "inspection")
@Getter
@Setter
public class Inspection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 不加 @JsonIgnore：要能接收 {"order":{"id":n}} 入参；WorkOrder 没有反向集合，序列化不会成环
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    private WorkOrder order;

    /** 质检发生的工序 */
    @Column(name = "step_name", nullable = false, length = 16)
    private String stepName;

    /** 少件 / 偏移 / 虚焊 / 连锡 / 反向 */
    @Column(name = "defect_type", nullable = false, length = 16)
    private String defectType;

    /** 不良点数，超过阈值判废 */
    @Column(name = "defect_points", nullable = false)
    private Integer defectPoints;

    /** 抽检板数 */
    @Column(name = "sample_qty", nullable = false)
    private Integer sampleQty;

    /** 合格 / 返修 / 判废 */
    @Column(name = "verdict", nullable = false, length = 16)
    private String verdict;

    @Column(name = "inspector", length = 32)
    private String inspector;

    /**
     * 前端生成的提交幂等号（UUID）。同号重复提交（双击 / 网络重试）幂等返回首条记录，
     * 不会产生第二条质检与第二个返修任务；历史数据为 NULL。唯一索引兜底。
     */
    @Column(name = "request_id", length = 64, unique = true)
    private String requestId;

    /** 返修是否闭环（旧字段：返修经质检复验通过即置 true，语义保持向后兼容） */
    @Column(name = "repair_done", nullable = false)
    private Boolean repairDone;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    /** 所属工单号，派生字段 */
    @Transient
    public String getOrderNo() {
        return order == null ? null : order.getOrderNo();
    }

    /** 缺陷率（‰），派生字段：不良点数 / 抽检板数 */
    @Transient
    public Double getDefectRate() {
        if (defectPoints == null || sampleQty == null || sampleQty <= 0) {
            return 0.0;
        }
        return Math.round((defectPoints * 10000.0 / sampleQty)) / 10.0;
    }
}
