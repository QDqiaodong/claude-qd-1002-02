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
import com.electronics.assembly.dto.RepairTaskRequest;
import com.electronics.assembly.entity.RepairEvent;
import com.electronics.assembly.entity.RepairTask;
import com.electronics.assembly.service.RepairTaskService;

/**
 * 返修任务接口：任务随「返修」质检结论幂等产生，不提供手工建单；
 * 返修工完工 → 质检员复验，复验通过才闭环并把工序推进到已完成。
 */
@RestController
@RequestMapping("/api/repair-tasks")
public class RepairTaskController {

    private final RepairTaskService repairTaskService;

    public RepairTaskController(RepairTaskService repairTaskService) {
        this.repairTaskService = repairTaskService;
    }

    /** 返修任务列表：可按工单、状态过滤 */
    @GetMapping
    public ApiResponse<List<RepairTask>> list(@RequestParam(required = false) Long orderId,
                                              @RequestParam(required = false) String status) {
        return ApiResponse.success(repairTaskService.list(orderId, status));
    }

    /** 未闭环返修任务 */
    @GetMapping("/open")
    public ApiResponse<List<RepairTask>> open() {
        return ApiResponse.success(repairTaskService.openTasks());
    }

    /** 由质检记录查返修任务（没有任务返回 data=null） */
    @GetMapping("/by-inspection/{inspectionId}")
    public ApiResponse<RepairTask> byInspection(@PathVariable Long inspectionId) {
        return ApiResponse.success(repairTaskService.findByInspection(inspectionId));
    }

    /** 返修任务详情：任务 + 触发质检 + 完整处理记录 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(repairTaskService.detail(id));
    }

    /** 返修任务处理记录 */
    @GetMapping("/{id}/events")
    public ApiResponse<List<RepairEvent>> events(@PathVariable Long id) {
        return ApiResponse.success(repairTaskService.events(id));
    }

    /** 返修工完工上报：待返修 → 待复验（重复上报幂等） */
    @PostMapping("/{id}/complete")
    public ApiResponse<RepairTask> complete(@PathVariable Long id,
                                            @RequestBody(required = false) RepairTaskRequest req) {
        return ApiResponse.success("返修已完工，等待质检员复验", repairTaskService.complete(id, req));
    }

    /** 质检员复验：合格 → 闭环 + 工序已完成；不合格 → 返修轮次 +1 */
    @PostMapping("/{id}/recheck")
    public ApiResponse<RepairTask> recheck(@PathVariable Long id,
                                           @RequestBody(required = false) RepairTaskRequest req) {
        RepairTask task = repairTaskService.recheck(id, req);
        boolean passed = "合格".equals(req == null ? null : req.getResult());
        return ApiResponse.success(passed ? "复验通过，返修闭环，工序已置为已完成"
                : "复验不通过，已退回返修（轮次 +1）", task);
    }
}
