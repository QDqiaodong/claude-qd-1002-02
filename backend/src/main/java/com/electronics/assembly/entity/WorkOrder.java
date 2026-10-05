package com.electronics.assembly.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 生产工单。
 *
 * <p>状态流转：待投产 → 贴片中 → 待检验 → 已完成（不可跳级），可旁路置为已取消。
 * 投产前必须过齐套校验——齐套率低于 {@code Dict.MIN_KIT_RATE} 不允许投产。</p>
 */
@Entity
@Table(name = "work_order")
@Getter
@Setter
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 工单号，唯一 */
    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    private String orderNo;

    /** 机型，决定用哪套 BOM */
    @Column(name = "product_model", nullable = false, length = 48)
    private String productModel;

    /** 计划投产板数 */
    @Column(name = "plan_qty", nullable = false)
    private Integer planQty;

    /** 已完成板数 */
    @Column(name = "done_qty", nullable = false)
    private Integer doneQty;

    /** 高 / 中 / 低 */
    @Column(name = "priority", nullable = false, length = 8)
    private String priority;

    /** 待投产 / 贴片中 / 待检验 / 已完成 / 已取消 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 完成率（%），派生字段 */
    @Transient
    public Double getProgressRate() {
        if (planQty == null || planQty <= 0 || doneQty == null) {
            return 0.0;
        }
        return Math.round((doneQty * 1000.0 / planQty)) / 10.0;
    }

    /** 欠产板数，派生字段 */
    @Transient
    public Integer getGapQty() {
        if (planQty == null || doneQty == null) {
            return 0;
        }
        int gap = planQty - doneQty;
        return gap > 0 ? gap : 0;
    }

    /** 距交期天数，派生字段；负数表示已逾期 */
    @Transient
    public Integer getDaysToDue() {
        if (dueDate == null) {
            return null;
        }
        return (int) LocalDate.now().until(dueDate).getDays();
    }
}
