package com.electronics.assembly.dto;

import lombok.Getter;
import lombok.Setter;

/** 未投产制造批次的数量或交付顺序调整。 */
@Getter
@Setter
public class BatchAdjustRequest {

    private Integer planQty;
    private Integer deliverySeq;
    private String operator;
    private String reason;
}
