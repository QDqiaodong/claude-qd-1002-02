package com.electronics.assembly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.electronics.assembly.entity.ProductionBatch;

import jakarta.persistence.LockModeType;

public interface ProductionBatchRepository extends JpaRepository<ProductionBatch, Long> {

    List<ProductionBatch> findByOrderIdOrderByDeliverySeqAscIdAsc(Long orderId);

    /** 历史批次 delivery_seq=NULL，统一沉到批次树末尾；新批次再按交付顺序排列。 */
    @Query("select b from ProductionBatch b where b.order.id = :orderId "
            + "order by case when b.deliverySeq is null then 1 else 0 end, b.deliverySeq asc, b.id asc")
    List<ProductionBatch> findTreeByOrderId(@org.springframework.data.repository.query.Param("orderId") Long orderId);

    Optional<ProductionBatch> findByOrderIdAndBatchNo(Long orderId, String batchNo);

    Optional<ProductionBatch> findFirstByOrderIdAndReadonlyHistoryTrue(Long orderId);

    /**
     * 已开始生产的批次：状态越过待投产或任一阶段数量大于 0。
     * 已取消/历史不作为“新批次已开始”的判断依据。
     */
    @Query("select b from ProductionBatch b where b.order.id = :orderId "
            + "and b.status in ('待投产','贴片中','待检验','已完成') "
            + "and (b.status <> '待投产' or b.reportedQty > 0 or b.inspectedQty > 0 or b.completedQty > 0)")
    List<ProductionBatch> findStartedByOrderId(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ProductionBatch b where b.id = :id")
    Optional<ProductionBatch> findByIdForUpdate(@Param("id") Long id);
}
