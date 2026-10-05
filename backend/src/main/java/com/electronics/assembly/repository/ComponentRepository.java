package com.electronics.assembly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.electronics.assembly.entity.Component;

public interface ComponentRepository extends JpaRepository<Component, Long> {

    Optional<Component> findByPartNo(String partNo);

    List<Component> findByStatus(String status);

    List<Component> findByPackageType(String packageType);
}
