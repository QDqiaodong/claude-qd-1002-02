package com.electronics.assembly.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * BOM 明细：某机型生产一块板需要哪些物料、各用多少颗。
 *
 * <p>齐套校验就是拿 BOM 逐行比对物料库存：需求 = 单板用量 × 计划板数。</p>
 */
@Entity
@Table(
        name = "bom_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_bom", columnNames = {"product_model", "part_no"})
)
@Getter
@Setter
public class BomItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 机型 */
    @Column(name = "product_model", nullable = false, length = 48)
    private String productModel;

    /** 物料编码 */
    @Column(name = "part_no", nullable = false, length = 32)
    private String partNo;

    /** 单板用量（颗） */
    @Column(name = "unit_qty", nullable = false)
    private Integer unitQty;
}
