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
import com.electronics.assembly.dto.LineChangeRequest;
import com.electronics.assembly.entity.LineChangeBatch;
import com.electronics.assembly.service.LineChangeService;

/** 换线首件放行接口 */
@RestController
@RequestMapping("/api/line-changes")
public class LineChangeController {

    private final LineChangeService lineChangeService;

    public LineChangeController(LineChangeService lineChangeService) {
        this.lineChangeService = lineChangeService;
    }

    @GetMapping
    public ApiResponse<List<LineChangeBatch>> list(@RequestParam(required = false) Long orderId,
                                                   @RequestParam(required = false) String status) {
        return ApiResponse.success(lineChangeService.list(orderId, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<LineChangeBatch> get(@PathVariable Long id) {
        return ApiResponse.success(lineChangeService.get(id));
    }

    /** 工单换线上下文：当前配置、当前批次、阻塞原因、配置差异、处理记录 */
    @GetMapping("/order/{orderId}/context")
    public ApiResponse<Map<String, Object>> context(@PathVariable Long orderId) {
        return ApiResponse.success(lineChangeService.context(orderId));
    }

    /** 工单换线阻塞信息（SMT 工序页用） */
    @GetMapping("/order/{orderId}/blocking")
    public ApiResponse<Map<String, Object>> blocking(@PathVariable Long orderId) {
        return ApiResponse.success(lineChangeService.blocking(orderId));
    }

    /** 各工单最新换线批次一览（工单列表角标用） */
    @GetMapping("/summary")
    public ApiResponse<List<Map<String, Object>>> summary() {
        return ApiResponse.success(lineChangeService.summary());
    }

    /** 建立换线批次：更换设备/飞达/关键参数时调用，旧有效批次自动失效 */
    @PostMapping
    public ApiResponse<LineChangeBatch> create(@RequestBody LineChangeRequest req) {
        return ApiResponse.success("换线批次已建立，等待提交首件", lineChangeService.create(req));
    }

    /** 操作员提交首件检测结果：待首件 → 待确认 */
    @PostMapping("/{id}/first-piece")
    public ApiResponse<LineChangeBatch> submitFirstPiece(@PathVariable Long id,
                                                         @RequestBody LineChangeRequest req) {
        return ApiResponse.success("首件检测结果已提交，等待质检员确认", lineChangeService.submitFirstPiece(id, req));
    }

    /** 质检员确认放行；配置已再变更时确认失败且批次失效 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<LineChangeBatch> confirm(@PathVariable Long id,
                                                @RequestBody LineChangeRequest req) {
        return ApiResponse.success("首件已确认放行，恢复批量贴片", lineChangeService.confirm(id, req));
    }

    /** 质检员驳回：必须填原因 */
    @PostMapping("/{id}/reject")
    public ApiResponse<LineChangeBatch> reject(@PathVariable Long id,
                                               @RequestBody LineChangeRequest req) {
        return ApiResponse.success("已驳回，等待操作员修正重提", lineChangeService.reject(id, req));
    }

    /** 驳回后修正重提：生成新版本批次重新送检 */
    @PostMapping("/{id}/revise")
    public ApiResponse<LineChangeBatch> revise(@PathVariable Long id,
                                               @RequestBody LineChangeRequest req) {
        return ApiResponse.success("已生成新版本批次，等待提交首件", lineChangeService.revise(id, req));
    }
}
