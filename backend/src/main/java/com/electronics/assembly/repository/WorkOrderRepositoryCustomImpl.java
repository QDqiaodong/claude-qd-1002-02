package com.electronics.assembly.repository;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.electronics.assembly.dto.ShortageLine;
import com.electronics.assembly.entity.WorkOrder;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link WorkOrderRepositoryCustom} 的手写实现。
 *
 * <p>类名必须是「fragment 接口名 + Impl」，Spring Data 才会把它当作
 * fragment 实现自动装配进 {@code WorkOrderRepository}。</p>
 *
 * <p>齐套要跨 work_order / bom_item / component 三张表算，派生查询写不出来，
 * 这里手写 JPQL：需求 = 单板用量 × 计划板数，库存不足或物料不可用即为缺料。</p>
 */
@Repository
public class WorkOrderRepositoryCustomImpl implements WorkOrderRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<WorkOrder> findShortageOrders() {
        return em.createQuery(
                "select distinct w from WorkOrder w "
                        + "where exists (select b.id from BomItem b, Component c "
                        + "              where b.productModel = w.productModel "
                        + "                and c.partNo = b.partNo "
                        + "                and (c.stockQty < b.unitQty * w.planQty "
                        + "                     or c.status <> '正常')) "
                        + "order by w.dueDate asc",
                WorkOrder.class).getResultList();
    }

    @Override
    public List<ShortageLine> findShortageLines(Long orderId) {
        WorkOrder order = em.find(WorkOrder.class, orderId);
        if (order == null) {
            return List.of();
        }
        List<Object[]> rows = em.createQuery(
                "select b.partNo, c.partName, b.unitQty, b.unitQty * :planQty, c.stockQty, c.status "
                        + "from BomItem b, Component c "
                        + "where b.productModel = :model "
                        + "  and c.partNo = b.partNo "
                        + "  and (c.stockQty < b.unitQty * :planQty or c.status <> '正常') "
                        + "order by b.partNo",
                Object[].class)
                .setParameter("planQty", order.getPlanQty())
                .setParameter("model", order.getProductModel())
                .getResultList();

        List<ShortageLine> out = new ArrayList<>();
        for (Object[] r : rows) {
            int required = ((Number) r[3]).intValue();
            int stock = ((Number) r[4]).intValue();
            out.add(new ShortageLine(
                    (String) r[0],
                    (String) r[1],
                    ((Number) r[2]).intValue(),
                    required,
                    stock,
                    Math.max(0, required - stock),
                    (String) r[5]));
        }
        return out;
    }
}
