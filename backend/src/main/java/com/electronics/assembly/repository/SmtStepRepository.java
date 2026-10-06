package com.electronics.assembly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.electronics.assembly.entity.ProductionBatch;
import com.electronics.assembly.entity.SmtStep;

public interface SmtStepRepository extends JpaRepository<SmtStep, Long> {

    List<SmtStep> findByOrderIdOrderById(Long orderId);

    Optional<SmtStep> findByOrderIdAndStepName(Long orderId, String stepName);

    Optional<SmtStep> findByBatchIdAndStepName(Long batchId, String stepName);

    List<SmtStep> findByBatchIdOrderById(Long batchId);

    List<SmtStep> findByBatchIdAndStepNameIn(Long batchId, List<String> stepNames);

    List<SmtStep> findByBatchInAndStepNameIn(List<ProductionBatch> batches, List<String> stepNames);

    List<SmtStep> findByStatus(String status);
}
