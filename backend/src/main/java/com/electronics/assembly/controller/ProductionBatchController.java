package com.electronics.assembly.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.electronics.assembly.common.ApiResponse;
import com.electronics.assembly.dto.BatchSplitRequest;
import com.electronics.assembly.entity.ProductionBatch;
import com.electronics.assembly.service.ProductionBatchService;

/**
 * 工单分批生产接口：分批方案（首次启用 / 调整）、批次流转（投产 / 报工 / 送检 / 完成 /
 * 状态推进 / 取消回收或减产）、批次树上下文与单批齐套。
 */
@RestController
@RequestMapping("/api/production-batches")
public class ProductionBatchController {

    private final ProductionBatchService batchService;

    public ProductionBatchController(ProductionBatchService batchService) {
        this.batchService = batchService;
    }

    /** 某工单全部批次（交付顺序） */
    @GetMapping("/order/{orderId}")
    public ApiResponse<List<ProductionBatch>> listByOrder(@PathVariable Long orderId) {
        return ApiResponse.success(batchService.listByOrder(orderId));
    }

    /** 工单批次树：每批齐套率 / 工序进度 / 数量差异 / 调整记录 + 母子汇总口径 */
    @GetMapping("/order/{orderId}/context")
    public ApiResponse<Map<String, Object>> context(@PathVariable Long orderId) {
        return ApiResponse.success(batchService.context(orderId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductionBatch> get(@PathVariable Long id) {
        return ApiResponse.success(batchService.get(id));
    }

    /** 单批齐套校验 */
    @GetMapping("/{id}/kit-check")
    public ApiResponse<Map<String, Object>> kitCheck(@PathVariable Long id) {
        return ApiResponse.success(batchService.kitCheck(id));
    }

    /**
     * 保存分批方案：首次启用时把母工单原完成数量迁到只读历史批次并建新批次；
     * 之后为调整（只能改 / 增 / 删未投产批次）。整份数量不守恒、有空批次、
     * 或动到已开工批次时整份不保存。
     */
    @PostMapping("/order/{orderId}/plan")
    public ApiResponse<List<ProductionBatch>> savePlan(@PathVariable Long orderId,
                                                       @RequestBody BatchSplitRequest req) {
        return ApiResponse.success("分批方案已保存（数量守恒校验通过）", batchService.savePlan(orderId, req));
    }

    /** 批次投产：齐套通过后铺开五道 SMT 工序 */
    @PostMapping("/{id}/release")
    public ApiResponse<ProductionBatch> release(@PathVariable Long id) {
        return ApiResponse.success("批次已投产", batchService.release(id));
    }

    /** 批次报工 */
    @PostMapping("/{id}/report")
    public ApiResponse<ProductionBatch> report(@PathVariable Long id,
                                               @RequestParam Integer qty,
                                               @RequestParam(required = false) String operator) {
        return ApiResponse.success("批次报工已登记", batchService.report(id, qty, operator));
    }

    /** 批次送检（送检量 ≤ 已报工量） */
    @PostMapping("/{id}/inspect")
    public ApiResponse<ProductionBatch> inspect(@PathVariable Long id,
                                                @RequestParam Integer qty,
                                                @RequestParam(required = false) String operator) {
        return ApiResponse.success("批次送检已登记", batchService.inspect(id, qty, operator));
    }

    /** 批次合格入库（完成量 ≤ 已送检量） */
    @PostMapping("/{id}/complete-qty")
    public ApiResponse<ProductionBatch> completeQty(@PathVariable Long id,
                                                    @RequestParam Integer qty,
                                                    @RequestParam(required = false) String operator) {
        return ApiResponse.success("批次完成数量已登记", batchService.completeQty(id, qty, operator));
    }

    /** 批次状态逐级推进（待投产→贴片中→待检验→已完成），不可倒退 */
    @PostMapping("/{id}/transfer")
    public ApiResponse<ProductionBatch> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("批次状态已更新", batchService.transfer(id, target));
    }

    /**
     * 取消批次：未产数量必须回收到某未投产批次（reclaimToBatchId）
     * 或由计划员确认减产（confirmReduce=true），二选一。
     */
    @PostMapping("/{id}/cancel")
    public ApiResponse<ProductionBatch> cancel(@PathVariable Long id,
                                               @RequestParam(required = false) Long reclaimToBatchId,
                                               @RequestParam(required = false, defaultValue = "false")
                                               boolean confirmReduce,
                                               @RequestParam(required = false) String operator) {
        return ApiResponse.success("批次已取消",
                batchService.cancel(id, reclaimToBatchId, confirmReduce, operator));
    }
}
