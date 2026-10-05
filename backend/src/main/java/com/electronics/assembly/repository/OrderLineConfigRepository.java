package com.electronics.assembly.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.electronics.assembly.entity.OrderLineConfig;

import jakarta.persistence.LockModeType;

public interface OrderLineConfigRepository extends JpaRepository<OrderLineConfig, Long> {

    /** 悲观写锁读取：换线写操作在工单锁之后再锁配置行，保证读到最新提交的快照 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OrderLineConfig c where c.orderId = :orderId")
    Optional<OrderLineConfig> findByIdForUpdate(@Param("orderId") Long orderId);
}
