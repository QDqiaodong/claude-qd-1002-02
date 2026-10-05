package com.electronics.assembly.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.electronics.assembly.entity.RepairTask;

public interface RepairTaskRepository extends JpaRepository<RepairTask, Long> {

    /** 幂等键：同一质检记录最多一个返修任务（数据库另有唯一索引兜底） */
    Optional<RepairTask> findByInspectionId(Long inspectionId);

    List<RepairTask> findByOrderIdOrderByIdDesc(Long orderId);

    List<RepairTask> findByOrderIdAndStepNameOrderByIdDesc(Long orderId, String stepName);

    List<RepairTask> findByStatusIn(Collection<String> statuses);

    /** 某工单是否存在未闭环返修任务（完工 / 工单推进前断言用） */
    @Query("select t from RepairTask t where t.order.id = :orderId and t.status <> '已复验通过' order by t.id desc")
    List<RepairTask> findOpenByOrderId(@Param("orderId") Long orderId);

    /** 某工单某道工序是否存在未闭环返修任务（开工 / 完工断言用，防止未返修完跳过该工序） */
    @Query("select t from RepairTask t where t.order.id = :orderId and t.stepName = :stepName "
            + "and t.status <> '已复验通过' order by t.id desc")
    List<RepairTask> findOpenByOrderIdAndStepName(@Param("orderId") Long orderId,
                                                  @Param("stepName") String stepName);
}
