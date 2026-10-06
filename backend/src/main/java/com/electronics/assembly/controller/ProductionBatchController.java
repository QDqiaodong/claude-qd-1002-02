package com.electronics.assembly.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.electronics.assembly.common.ApiResponse;
import com.electronics.assembly.dto.BatchAdjustRequest;
import com.electronics.assembly.dto.BatchCancelRequest;
import com.electronics.assembly.entity.ProductionBatch;
import com.electronics.assembly.service.ProductionBatchService;

/** 制造批次接口：分批、数量守恒校验、独立齐套/投产/报工/状态推进。 */
@RestController
@RequestMapping("/api/production-batches")
public class ProductionBatchController {

    private final ProductionBatchService batchService;

    public ProductionBatchController(ProductionBatchService batchService) {
        this.batchService = batchService;
    }

    @GetMapping
    public ApiResponse<List<ProductionBatch>> list(@RequestParam Long orderId) {
        return ApiResponse.success(batchService.listByOrder(orderId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductionBatch> get(@PathVariable Long id) {
        return ApiResponse.success(batchService.get(id));
    }

    @GetMapping("/{id}/kit-check")
    public ApiResponse<Map<String, Object>> kitCheck(@PathVariable Long id) {
        return ApiResponse.success(batchService.kitCheck(id));
    }

    @PostMapping("/{id}/release")
    public ApiResponse<ProductionBatch> release(@PathVariable Long id) {
        return ApiResponse.success("批次已投产", batchService.releaseBatch(id));
    }

    @PostMapping("/{id}/report")
    public ApiResponse<ProductionBatch> report(@PathVariable Long id, @RequestParam Integer qty) {
        return ApiResponse.success("批次报工已登记", batchService.report(id, qty));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<ProductionBatch> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("批次状态已更新", batchService.transfer(id, target));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProductionBatch> adjust(@PathVariable Long id, @RequestBody BatchAdjustRequest req) {
        return ApiResponse.success("未投产批次已调整", batchService.adjust(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<List<ProductionBatch>> delete(@PathVariable Long id,
                                                     @RequestParam Long recycleToBatchId,
                                                     @RequestParam(defaultValue = "计划员") String operator) {
        return ApiResponse.success("批次已删除，数量已回收",
                batchService.delete(id, recycleToBatchId, operator));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<List<ProductionBatch>> cancel(@PathVariable Long id,
                                                     @RequestBody(required = false) BatchCancelRequest req) {
        return ApiResponse.success("批次已取消", batchService.cancel(id, req));
    }
}
