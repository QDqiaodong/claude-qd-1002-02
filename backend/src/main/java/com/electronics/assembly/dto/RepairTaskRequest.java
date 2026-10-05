package com.electronics.assembly.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 返修操作入参：返修工完工上报 / 质检员复验，共用一个 DTO（字段各自取用）。
 * 不加 @NotBlank 硬校验：旧接口 {@code POST /inspections/{id}/repair} 允许空体调用。
 */
@Getter
@Setter
public class RepairTaskRequest {

    /** 返修工（完工上报时填） */
    private String repairBy;

    /** 返修说明（完工上报时可填） */
    private String repairNote;

    /** 质检员（复验时必填） */
    private String inspector;

    /** 复验结果：合格 / 不合格（复验时必填） */
    private String result;

    /** 复验备注（复验不通过时建议写明缺陷） */
    private String note;
}
