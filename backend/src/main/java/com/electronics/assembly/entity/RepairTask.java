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
 * 返修任务：质检员对某道 SMT 工序提交「返修」结论时幂等创建，
 * 返修工完工后报「待复验」，只能由质检员复验：通过则闭环、工序置已完成；
 * 不通过则返修轮次 +1 并退回「待返修」。
 *
 * <p>幂等与并发：① 一条质检记录最多一个返修任务（{@code uk_rt_inspection} 唯一索引兜底）；
 * ② 同一工单同一道工序同一时刻最多一个未闭环任务（{@code active_order_id}/
 * {@code active_step_name} 生成列 + 唯一索引兜底），不同工单互不串单；
 * ③ 状态推进前悲观锁工单行串行化，重复点击 / 网络重试不会产生重复任务。</p>
 */
@Entity
@Table(name = "repair_task")
@Getter
@Setter
public class RepairTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 不加 @JsonIgnore：要能接收 {"order":{"id":n}} 入参；WorkOrder 没有反向集合，序列化不会成环
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    private WorkOrder order;

    /** 触发本任务的质检记录（一对一，唯一索引兜底） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "inspection_id", nullable = false)
    private Inspection inspection;

    /** 返修发生的工序（取自质检记录） */
    @Column(name = "step_name", nullable = false, length = 16)
    private String stepName;

    /** 返修轮次：首建为 1，每次复验不通过 +1 */
    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** 待返修 / 待复验 / 已复验通过 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 最近一轮返修人 */
    @Column(name = "repair_by", length = 32)
    private String repairBy;

    /** 最近一轮返修说明 */
    @Column(name = "repair_note", length = 255)
    private String repairNote;

    @Column(name = "repaired_at")
    private LocalDateTime repairedAt;

    /** 复验质检员（返修闭环只能由质检员推进） */
    @Column(name = "rechecked_by", length = 32)
    private String recheckedBy;

    /** 最近一次复验结论：合格 / 不合格 */
    @Column(name = "last_recheck_result", length = 16)
    private String lastRecheckResult;

    @Column(name = "rechecked_at")
    private LocalDateTime recheckedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 所属工单号，派生字段 */
    @Transient
    public String getOrderNo() {
        return order == null ? null : order.getOrderNo();
    }

    /** 是否未闭环（返修工与质检员还要继续处理），派生字段 */
    @Transient
    public boolean isOpen() {
        return !"已复验通过".equals(status);
    }
}
