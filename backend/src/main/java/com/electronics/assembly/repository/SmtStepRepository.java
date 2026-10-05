package com.electronics.assembly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.electronics.assembly.entity.SmtStep;

public interface SmtStepRepository extends JpaRepository<SmtStep, Long> {

    List<SmtStep> findByOrderIdOrderById(Long orderId);

    Optional<SmtStep> findByOrderIdAndStepName(Long orderId, String stepName);

    List<SmtStep> findByStatus(String status);
}
