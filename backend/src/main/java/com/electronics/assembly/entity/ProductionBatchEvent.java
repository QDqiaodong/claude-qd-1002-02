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
 * 批次调整记录：批次方案（建批 / 历史迁移 / 调整计划 / 删除 / 回收 / 减产）
 * 与批次状态（投产 / 报工 / 送检 / 完成 / 取消）的完整审计链，工单卡片的
 * 「调整记录」时间线直接读这张表。
 */
@Entity
@Table(name = "production_batch_event")
@Getter
@Setter
public class ProductionBatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private ProductionBatch batch;

    /** 建批/历史迁移/调整计划/删除/投产/报工/送检/完成/取消/回收/减产 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "actor", nullable = false, length = 32)
    private String actor;

    @Column(name = "detail")
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
