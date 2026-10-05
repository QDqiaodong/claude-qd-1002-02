package com.electronics.assembly.entity;

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
 * 物料台账（电子元器件）。
 *
 * <p>物料按封装分飞达位上机；库存不足或状态非「正常」时，涉及它的工单齐套不通过。</p>
 */
@Entity
@Table(name = "component")
@Getter
@Setter
public class Component {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 物料编码，唯一 */
    @Column(name = "part_no", nullable = false, unique = true, length = 32)
    private String partNo;

    @Column(name = "part_name", nullable = false, length = 64)
    private String partName;

    /** 封装：0201 / 0402 / 0603 / 0805 / SOT / QFP / BGA */
    @Column(name = "package_type", nullable = false, length = 16)
    private String packageType;

    /** 现有库存（颗） */
    @Column(name = "stock_qty", nullable = false)
    private Integer stockQty;

    /** 飞达位 */
    @Column(name = "feed_slot", length = 16)
    private String feedSlot;

    /** 正常 / 缺料 / 停用 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 是否可用于生产：状态正常且库存大于 0 */
    @Transient
    public boolean isUsable() {
        return "正常".equals(status) && stockQty != null && stockQty > 0;
    }
}
