package com.electronics.assembly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.electronics.assembly.entity.SmtStep;

public interface SmtStepRepository extends JpaRepository<SmtStep, Long> {

    List<SmtStep> findByOrderIdOrderById(Long orderId);

    Optional<SmtStep> findByOrderIdAndStepName(Long orderId, String stepName);

    List<SmtStep> findByStatus(String status);

    // ---------------- 分批生产：每批独立铺开五道工序 ----------------

    List<SmtStep> findByBatchIdOrderById(Long batchId);

    Optional<SmtStep> findByBatchIdAndStepName(Long batchId, String stepName);

    /** 批次内某工序是否存在（uk_step_batch 唯一约束的服务层判存） */
    boolean existsByBatchIdAndStepName(Long batchId, String stepName);
}
