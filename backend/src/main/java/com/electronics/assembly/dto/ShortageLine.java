package com.electronics.assembly.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * 缺料明细行：某工单的 BOM 里，哪颗料缺多少。
 *
 * <p>由 {@code WorkOrderRepositoryImpl} 手写 JPQL 查出来后逐行组装，
 * 不走构造器投影（避免 JPQL {@code select new} 参数个数对不上的坑）。</p>
 */
@Getter
@Setter
@AllArgsConstructor
public class ShortageLine {

    /** 物料编码 */
    private String partNo;

    /** 物料名称 */
    private String partName;

    /** 单板用量（颗） */
    private int unitQty;

    /** 本工单需求总量（颗）= 单板用量 × 计划板数 */
    private int requiredQty;

    /** 现有库存（颗） */
    private int stockQty;

    /** 缺口（颗），为 0 表示不缺 */
    private int gapQty;

    /** 物料状态 */
    private String status;
}
