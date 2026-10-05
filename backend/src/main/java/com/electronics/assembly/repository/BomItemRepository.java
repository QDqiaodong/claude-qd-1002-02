package com.electronics.assembly.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.electronics.assembly.entity.BomItem;

public interface BomItemRepository extends JpaRepository<BomItem, Long> {

    List<BomItem> findByProductModel(String productModel);

    List<BomItem> findByPartNo(String partNo);
}
