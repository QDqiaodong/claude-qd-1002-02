package com.electronics.assembly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.electronics.assembly.entity.WorkOrder;

import jakarta.persistence.LockModeType;

/**
 * 生产工单 Repository：常规 JPA 能力 + {@link WorkOrderRepositoryCustom} 手写的齐套查询。
 */
public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long>, WorkOrderRepositoryCustom {

    Optional<WorkOrder> findByOrderNo(String orderNo);

    List<WorkOrder> findByStatus(String status);

    List<WorkOrder> findByProductModel(String productModel);

    /**
     * 悲观写锁读取工单行：换线批次的建批 / 提交 / 确认 / 驳回 / 修正重提
     * 都先拿这把锁，把「操作员改配置」与「质检员确认」等并发写串行化，
     * 保证最终只有一个与最新配置一致的有效放行版本。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from WorkOrder o where o.id = :id")
    Optional<WorkOrder> findByIdForUpdate(@Param("id") Long id);
}
