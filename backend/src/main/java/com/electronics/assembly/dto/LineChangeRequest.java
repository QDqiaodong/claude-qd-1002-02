package com.electronics.assembly.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 换线首件相关接口的统一入参：建批 / 提交首件 / 确认 / 驳回 / 修正重提
 * 各自取需要的字段，服务端按动作校验必填项。
 */
@Getter
@Setter
public class LineChangeRequest {

    /** 工单 id（建批时必填） */
    private Long orderId;

    /** 生产设备编号 */
    private String equipmentNo;

    /** 飞达配置 */
    private String feederConfig;

    /** 关键工艺参数 */
    private String keyParams;

    /** 换线原因（建批）/ 驳回原因（驳回） */
    private String reason;

    /** 修正说明（修正重提） */
    private String note;

    /** 操作员（建批 / 提交首件 / 修正重提） */
    private String operator;

    /** 质检员（确认 / 驳回） */
    private String inspector;

    /** 首件检测结果：合格 / 不合格 */
    private String firstResult;

    /** 首件检测数据 */
    private String firstNote;
}
