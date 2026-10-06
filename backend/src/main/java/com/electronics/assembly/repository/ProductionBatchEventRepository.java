package com.electronics.assembly.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.electronics.assembly.entity.ProductionBatchEvent;

public interface ProductionBatchEventRepository extends JpaRepository<ProductionBatchEvent, Long> {

    List<ProductionBatchEvent> findByBatchIdOrderByIdAsc(Long batchId);

    /**
     * 某工单的全部批次调整记录（工单卡片的调整时间线用）。
     * 走批次关联，按事件 id（即发生先后）排列。
     */
    List<ProductionBatchEvent> findByBatchOrderIdOrderByIdAsc(Long orderId);
}
