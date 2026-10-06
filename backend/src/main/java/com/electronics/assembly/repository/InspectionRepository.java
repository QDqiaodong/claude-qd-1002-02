package com.electronics.assembly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.electronics.assembly.entity.Inspection;

public interface InspectionRepository extends JpaRepository<Inspection, Long> {

    List<Inspection> findByOrderId(Long orderId);

    List<Inspection> findByVerdict(String verdict);

    /** 未闭环的返修单 */
    List<Inspection> findByVerdictAndRepairDone(String verdict, Boolean repairDone);

    List<Inspection> findByOrderIdAndStepName(Long orderId, String stepName);

    /** 分批生产：某批次的全部质检记录 */
    List<Inspection> findByBatchId(Long batchId);

    /** 提交幂等号：同号重复提交（双击 / 网络重试）返回首次登记的质检记录 */
    Optional<Inspection> findByRequestId(String requestId);
}
