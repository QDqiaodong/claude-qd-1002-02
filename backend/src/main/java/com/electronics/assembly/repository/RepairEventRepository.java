package com.electronics.assembly.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.electronics.assembly.entity.RepairEvent;

public interface RepairEventRepository extends JpaRepository<RepairEvent, Long> {

    List<RepairEvent> findByTaskIdOrderByIdAsc(Long taskId);

    List<RepairEvent> findByTaskIdInOrderByIdAsc(List<Long> taskIds);
}
