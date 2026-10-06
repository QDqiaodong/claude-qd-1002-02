package com.electronics.assembly.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.electronics.assembly.entity.BatchAdjustmentEvent;

public interface BatchAdjustmentEventRepository extends JpaRepository<BatchAdjustmentEvent, Long> {

    List<BatchAdjustmentEvent> findByOrderIdOrderByIdAsc(Long orderId);

    List<BatchAdjustmentEvent> findByBatchIdOrderByIdAsc(Long batchId);
}
