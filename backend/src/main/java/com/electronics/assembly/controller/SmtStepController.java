package com.electronics.assembly.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.electronics.assembly.common.ApiResponse;
import com.electronics.assembly.entity.SmtStep;
import com.electronics.assembly.service.SmtStepService;

/** SMT 工序流转接口 */
@RestController
@RequestMapping("/api/smt-steps")
public class SmtStepController {

    private final SmtStepService stepService;

    public SmtStepController(SmtStepService stepService) {
        this.stepService = stepService;
    }

    @GetMapping
    public ApiResponse<List<SmtStep>> list(@RequestParam(required = false) Long orderId,
                                           @RequestParam(required = false) String status) {
        return ApiResponse.success(stepService.list(orderId, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<SmtStep> get(@PathVariable Long id) {
        return ApiResponse.success(stepService.get(id));
    }

    /** 开工：前序工序必须已完成 */
    @PostMapping("/{id}/start")
    public ApiResponse<SmtStep> start(@PathVariable Long id,
                                      @RequestParam(required = false) String operator) {
        return ApiResponse.success("工序已开工", stepService.start(id, operator));
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<SmtStep> finish(@PathVariable Long id) {
        return ApiResponse.success("工序已完工", stepService.finish(id));
    }

    @PostMapping("/{id}/reset")
    public ApiResponse<SmtStep> reset(@PathVariable Long id) {
        return ApiResponse.success("工序已退回待执行", stepService.reset(id));
    }
}
