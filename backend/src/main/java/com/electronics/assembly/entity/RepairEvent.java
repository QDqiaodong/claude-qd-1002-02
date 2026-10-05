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
import lombok.Getter;
import lombok.Setter;

/**
 * 返修处理记录（审计链）：建任务 / 返修完成 / 复验通过 / 复验不通过。
 * 复验不通过后返修轮次递增，每一轮都会留下成对的「返修完成 → 复验」记录。
 */
@Entity
@Table(name = "repair_event")
@Getter
@Setter
public class RepairEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 不加 @JsonIgnore：RepairTask 不持有反向集合，序列化不会成环
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "task_id", nullable = false)
    private RepairTask task;

    /** 建任务 / 返修完成 / 复验通过 / 复验不通过 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    /** 返修轮次（记录产生时的轮次，便于按轮回放） */
    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "actor", length = 32, nullable = false)
    private String actor;

    @Column(name = "detail", length = 255)
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
