package com.electronics.assembly.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.electronics.assembly.entity.LineChangeEvent;

public interface LineChangeEventRepository extends JpaRepository<LineChangeEvent, Long> {

    List<LineChangeEvent> findByBatchIdOrderByIdDesc(Long batchId);

    /** 某工单全部换线处理记录（跨批次，按时间倒序） */
    @Query("select e from LineChangeEvent e where e.batch.order.id = :orderId order by e.id desc")
    List<LineChangeEvent> findByOrderId(@Param("orderId") Long orderId);
}
