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
import com.electronics.assembly.entity.Inspection;
import com.electronics.assembly.service.InspectionService;

/** 质检与返修接口 */
@RestController
@RequestMapping("/api/inspections")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @GetMapping
    public ApiResponse<List<Inspection>> list(@RequestParam(required = false) Long orderId,
                                              @RequestParam(required = false) String verdict) {
        return ApiResponse.success(inspectionService.list(orderId, verdict));
    }

    @GetMapping("/{id}")
    public ApiResponse<Inspection> get(@PathVariable Long id) {
        return ApiResponse.success(inspectionService.get(id));
    }

    /** 登记质检，结论按不良点数自动判定 */
    @PostMapping
    public ApiResponse<Inspection> create(@RequestBody Inspection input) {
        return ApiResponse.success("质检已登记", inspectionService.create(input));
    }

    /**
     * 返修工完工上报（旧「返修闭环」接口，响应仍是 Inspection，向后兼容）。
     * 完工后状态为「待复验」，必须再由质检员 POST /api/repair-tasks/{id}/recheck 复验。
     * 空体可调用，重复点击 / 网络重试幂等。
     */
    @PostMapping("/{id}/repair")
    public ApiResponse<Inspection> repair(@PathVariable Long id,
                                          @RequestBody(required = false) RepairTaskRequest req) {
        return ApiResponse.success("返修已完工，等待质检员复验", inspectionService.repair(id, req));
    }

    /** 未闭环的返修单 */
    @GetMapping("/open-repairs")
    public ApiResponse<List<Inspection>> openRepairs() {
        return ApiResponse.success(inspectionService.openRepairs());
    }

    /** 某工单是否放行阻塞 */
    @GetMapping("/blocking/{orderId}")
    public ApiResponse<Map<String, Object>> blocking(@PathVariable Long orderId) {
        return ApiResponse.success(inspectionService.blocking(orderId));
    }
}
