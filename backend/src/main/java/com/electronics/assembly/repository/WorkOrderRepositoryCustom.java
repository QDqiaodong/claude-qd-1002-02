package com.electronics.assembly.repository;

import java.util.List;

import com.electronics.assembly.dto.ShortageLine;
import com.electronics.assembly.entity.WorkOrder;

/**
 * 自定义 Repository fragment（本仓的后端路线）。
 *
 * <p>齐套这件事要跨 work_order / bom_item / component 三张表算，
 * 派生查询写不出来，也不该塞进 Service 里手写 EntityManager，
 * 所以单独声明一个 fragment 接口，由 {@code WorkOrderRepositoryImpl} 手写 JPQL 实现，
 * Spring Data 会把它和 {@code JpaRepository} 的常规能力合并进同一个 Repository。</p>
 */
public interface WorkOrderRepositoryCustom {

    /**
     * 找出「齐套不通过」的工单：BOM 里存在库存不足或物料不可用的行。
     */
    List<WorkOrder> findShortageOrders();

    /**
     * 某工单的缺料明细。
     *
     * @param orderId 工单
     * @return 缺料行；空列表表示齐套通过
     */
    List<ShortageLine> findShortageLines(Long orderId);
}
