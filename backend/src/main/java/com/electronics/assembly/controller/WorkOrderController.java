package com.electronics.assembly.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.electronics.assembly.common.ApiResponse;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.service.WorkOrderService;

/** 生产工单接口（齐套校验走自定义 Repository fragment） */
@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

    private final WorkOrderService orderService;

    public WorkOrderController(WorkOrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ApiResponse<List<WorkOrder>> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String productModel) {
        return ApiResponse.success(orderService.list(status, productModel));
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkOrder> get(@PathVariable Long id) {
        return ApiResponse.success(orderService.get(id));
    }

    /** 齐套校验：缺料明细 + 齐套率 */
    @GetMapping("/{id}/kit-check")
    public ApiResponse<Map<String, Object>> kitCheck(@PathVariable Long id) {
        return ApiResponse.success(orderService.kitCheck(id));
    }

    /** 齐套不通过的工单一览（fragment 手写查询） */
    @GetMapping("/shortage")
    public ApiResponse<List<WorkOrder>> shortage() {
        return ApiResponse.success(orderService.shortageOrders());
    }

    @PostMapping
    public ApiResponse<WorkOrder> create(@RequestBody WorkOrder input) {
        return ApiResponse.success("工单已创建", orderService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<WorkOrder> update(@PathVariable Long id, @RequestBody WorkOrder input) {
        return ApiResponse.success("已更新", orderService.update(id, input));
    }

    /** 投产：过齐套校验并铺开五道工序 */
    @PostMapping("/{id}/release")
    public ApiResponse<WorkOrder> release(@PathVariable Long id) {
        return ApiResponse.success("已投产", orderService.release(id));
    }

    /** 报工 */
    @PostMapping("/{id}/report")
    public ApiResponse<WorkOrder> report(@PathVariable Long id, @RequestParam Integer qty) {
        return ApiResponse.success("报工已登记", orderService.report(id, qty));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<WorkOrder> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", orderService.transfer(id, target));
    }
}
