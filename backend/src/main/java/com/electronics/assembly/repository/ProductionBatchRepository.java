package com.electronics.assembly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.electronics.assembly.entity.ProductionBatch;

public interface ProductionBatchRepository extends JpaRepository<ProductionBatch, Long> {

    /** 某工单的全部批次，按交付顺序排列（历史批次 seq=0 排在最前） */
    List<ProductionBatch> findByOrderIdOrderBySeqNoAsc(Long orderId);

    Optional<ProductionBatch> findByBatchNo(String batchNo);

    /** 未投产批次：调整/取消回收时只允许动它们 */
    List<ProductionBatch> findByOrderIdAndStatus(Long orderId, String status);

    List<ProductionBatch> findByReadOnly(Boolean readOnly);
}
