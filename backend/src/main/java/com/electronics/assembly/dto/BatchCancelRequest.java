package com.electronics.assembly.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 取消未投产批次。
 * recycleToBatchId 不为空时把剩余数量并入该未投产批次；
 * 为空时必须 confirmReduction=true，由计划员确认母工单减产。
 */
@Getter
@Setter
public class BatchCancelRequest {

    private Long recycleToBatchId;
    private Boolean confirmReduction;
    private String operator;
    private String reason;
}
