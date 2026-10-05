package com.electronics.assembly.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 工单当前产线配置：生产设备 + 飞达配置 + 关键工艺参数。
 *
 * <p>这是换线批次的比对基准——批次快照哈希与这里不一致即视为配置已再变更；
 * 也是换线写操作的并发锁定点（所有改配置 / 首件确认都先锁对应工单行再串行处理）。</p>
 */
@Entity
@Table(name = "order_line_config")
@Getter
@Setter
public class OrderLineConfig {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    /** 生产设备编号 */
    @Column(name = "equipment_no", nullable = false, length = 32)
    private String equipmentNo;

    /** 飞达配置 */
    @Column(name = "feeder_config", nullable = false)
    private String feederConfig;

    /** 关键工艺参数 */
    @Column(name = "key_params", nullable = false)
    private String keyParams;

    /** 配置快照哈希：SHA-256(设备|飞达|参数) */
    @Column(name = "config_hash", nullable = false, length = 64)
    private String configHash;

    @Column(name = "updated_by", length = 32)
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
