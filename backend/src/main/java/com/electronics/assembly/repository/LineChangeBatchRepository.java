package com.electronics.assembly.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.electronics.assembly.entity.LineChangeBatch;

public interface LineChangeBatchRepository extends JpaRepository<LineChangeBatch, Long> {

    List<LineChangeBatch> findByOrderIdOrderByVersionNoDesc(Long orderId);

    Optional<LineChangeBatch> findTopByOrderIdOrderByVersionNoDesc(Long orderId);

    List<LineChangeBatch> findByStatus(String status);

    List<LineChangeBatch> findByOrderIdAndStatusInOrderByVersionNoDesc(Long orderId, Collection<String> statuses);

    @Query("select coalesce(max(b.versionNo), 0) from LineChangeBatch b where b.order.id = :orderId")
    int maxVersionNo(@Param("orderId") Long orderId);
}
