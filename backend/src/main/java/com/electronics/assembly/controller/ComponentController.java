package com.electronics.assembly.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.electronics.assembly.common.ApiResponse;
import com.electronics.assembly.entity.Component;
import com.electronics.assembly.service.ComponentService;

/** 物料台账接口 */
@RestController
@RequestMapping("/api/components")
public class ComponentController {

    private final ComponentService componentService;

    public ComponentController(ComponentService componentService) {
        this.componentService = componentService;
    }

    @GetMapping
    public ApiResponse<List<Component>> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String packageType) {
        return ApiResponse.success(componentService.list(status, packageType));
    }

    @GetMapping("/{id}")
    public ApiResponse<Component> get(@PathVariable Long id) {
        return ApiResponse.success(componentService.get(id));
    }

    @PostMapping
    public ApiResponse<Component> create(@RequestBody Component input) {
        return ApiResponse.success("物料已建档", componentService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<Component> update(@PathVariable Long id, @RequestBody Component input) {
        return ApiResponse.success("已更新", componentService.update(id, input));
    }

    /** 采购入库 */
    @PostMapping("/{id}/restock")
    public ApiResponse<Component> restock(@PathVariable Long id, @RequestParam Integer qty) {
        return ApiResponse.success("已入库", componentService.restock(id, qty));
    }

    /** 生产领料 */
    @PostMapping("/{id}/issue")
    public ApiResponse<Component> issue(@PathVariable Long id, @RequestParam Integer qty) {
        return ApiResponse.success("已领料", componentService.issue(id, qty));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<Component> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", componentService.transfer(id, target));
    }
}
