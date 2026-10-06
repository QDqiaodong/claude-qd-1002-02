package com.electronics.assembly.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 待投产工单分批方案。 */
@Getter
@Setter
public class BatchSplitRequest {

    private String operator;
    private List<Line> batches;

    @Getter
    @Setter
    public static class Line {
        /** 不传则系统按母工单号-B01 生成 */
        private String batchNo;
        private Integer planQty;
        /** 不传则按提交顺序从 1 编号 */
        private Integer deliverySeq;
    }
}
