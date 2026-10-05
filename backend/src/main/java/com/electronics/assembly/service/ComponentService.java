package com.electronics.assembly.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.electronics.assembly.common.BizException;
import com.electronics.assembly.common.Dict;
import com.electronics.assembly.entity.Component;
import com.electronics.assembly.repository.ComponentRepository;

/**
 * 物料台账业务。
 *
 * <p>核心规则：① 物料编码唯一、封装与状态必须合法；② 单次入库量不得超过
 * {@code MAX_ISSUE_PERCENT} 对应的上限（防误填）；③ 只有「正常」状态的物料能领用；
 * ④ 领用量不得超过现有库存。</p>
 */
@Service
public class ComponentService {

    private final ComponentRepository componentRepository;

    public ComponentService(ComponentRepository componentRepository) {
        this.componentRepository = componentRepository;
    }

    public List<Component> list(String status, String packageType) {
        if (status != null && !status.isBlank()) {
            return componentRepository.findByStatus(status);
        }
        if (packageType != null && !packageType.isBlank()) {
            return componentRepository.findByPackageType(packageType);
        }
        return componentRepository.findAll();
    }

    public Component get(Long id) {
        return componentRepository.findById(id)
                .orElseThrow(() -> new BizException("物料不存在：" + id));
    }

    public Component getByPartNo(String partNo) {
        return componentRepository.findByPartNo(partNo)
                .orElseThrow(() -> new BizException("物料不存在：" + partNo));
    }

    @Transactional
    public Component create(Component input) {
        if (input.getPartNo() == null || input.getPartNo().isBlank()) {
            throw new BizException("物料编码不能为空");
        }
        componentRepository.findByPartNo(input.getPartNo().trim()).ifPresent(c -> {
            throw new BizException("物料编码已存在：" + input.getPartNo());
        });
        if (!Dict.isValidPackage(input.getPackageType())) {
            throw new BizException("封装不合法：" + input.getPackageType());
        }
        Component c = new Component();
        c.setPartNo(input.getPartNo().trim());
        c.setPartName(input.getPartName() == null ? input.getPartNo() : input.getPartName());
        c.setPackageType(input.getPackageType());
        c.setStockQty(input.getStockQty() == null ? 0 : input.getStockQty());
        if (c.getStockQty() < 0) {
            throw new BizException("库存不能为负数");
        }
        c.setFeedSlot(input.getFeedSlot());
        c.setStatus(input.getStatus() == null || input.getStatus().isBlank() ? "正常" : input.getStatus());
        if (!Dict.isValidComponentStatus(c.getStatus())) {
            throw new BizException("物料状态不合法：" + c.getStatus());
        }
        return componentRepository.save(c);
    }

    @Transactional
    public Component update(Long id, Component input) {
        Component c = get(id);
        if (input.getPartName() != null && !input.getPartName().isBlank()) {
            c.setPartName(input.getPartName());
        }
        if (input.getPackageType() != null && !input.getPackageType().isBlank()) {
            if (!Dict.isValidPackage(input.getPackageType())) {
                throw new BizException("封装不合法：" + input.getPackageType());
            }
            c.setPackageType(input.getPackageType());
        }
        if (input.getFeedSlot() != null) {
            c.setFeedSlot(input.getFeedSlot());
        }
        return componentRepository.save(c);
    }

    /** 采购入库 */
    @Transactional
    public Component restock(Long id, Integer qty) {
        Component c = get(id);
        if (qty == null || qty <= 0) {
            throw new BizException("入库数量必须大于 0");
        }
        if (qty > Dict.MAX_ISSUE_PERCENT * 100) {
            throw new BizException("单次入库不得超过 " + (Dict.MAX_ISSUE_PERCENT * 100) + " 颗");
        }
        c.setStockQty(c.getStockQty() + qty);
        if ("缺料".equals(c.getStatus()) && c.getStockQty() > 0) {
            c.setStatus("正常");
        }
        return componentRepository.save(c);
    }

    /** 生产领料 */
    @Transactional
    public Component issue(Long id, Integer qty) {
        Component c = get(id);
        if (!"正常".equals(c.getStatus())) {
            throw new BizException("物料状态为「" + c.getStatus() + "」，不能领用");
        }
        if (qty == null || qty <= 0) {
            throw new BizException("领用数量必须大于 0");
        }
        if (qty > c.getStockQty()) {
            throw new BizException("库存不足，现有 " + c.getStockQty() + " 颗");
        }
        c.setStockQty(c.getStockQty() - qty);
        return componentRepository.save(c);
    }

    @Transactional
    public Component transfer(Long id, String target) {
        Component c = get(id);
        if (!Dict.isValidComponentStatus(target)) {
            throw new BizException("物料状态不合法：" + target);
        }
        c.setStatus(target);
        return componentRepository.save(c);
    }
}
