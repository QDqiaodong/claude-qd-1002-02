package com.electronics.assembly;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.electronics.assembly.entity.Inspection;
import com.electronics.assembly.entity.SmtStep;
import com.electronics.assembly.entity.WorkOrder;
import com.electronics.assembly.repository.SmtStepRepository;
import com.electronics.assembly.repository.WorkOrderRepository;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 集成测试基类：H2(MySQL 模式) 内存库 + 真实事务/唯一约束，不起 MySQL / Redis。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Autowired
    protected WorkOrderRepository orderRepository;

    @Autowired
    protected SmtStepRepository stepRepository;

    /** 建一张「贴片中」工单并铺开五道工序（默认全部待执行） */
    protected WorkOrder newOrderWithSteps(String orderNo) {
        WorkOrder o = new WorkOrder();
        o.setOrderNo(orderNo);
        o.setProductModel("MODEL-TEST");
        o.setPlanQty(100);
        o.setDoneQty(0);
        o.setPriority("中");
        o.setStatus("贴片中");
        o = orderRepository.save(o);
        for (String step : com.electronics.assembly.common.Dict.STEPS) {
            SmtStep s = new SmtStep();
            s.setOrder(o);
            s.setStepName(step);
            s.setStatus("待执行");
            stepRepository.save(s);
        }
        return o;
    }

    /** 把某工单某工序直接置为指定状态（测试造数用） */
    protected void forceStep(Long orderId, String stepName, String status) {
        SmtStep s = stepRepository.findByOrderIdAndStepName(orderId, stepName).orElseThrow();
        s.setStatus(status);
        stepRepository.save(s);
    }

    protected SmtStep step(Long orderId, String stepName) {
        return stepRepository.findByOrderIdAndStepName(orderId, stepName).orElseThrow();
    }

    /** 构造一条质检入参（verdict 由点数自动判定，调用方不用传） */
    protected Inspection inspectionInput(Long orderId, String stepName, int points,
                                         String inspector, String requestId) {
        Inspection in = new Inspection();
        WorkOrder o = new WorkOrder();
        o.setId(orderId);
        in.setOrder(o);
        in.setStepName(stepName);
        in.setDefectType("偏移");
        in.setDefectPoints(points);
        in.setSampleQty(40);
        in.setInspector(inspector);
        in.setRequestId(requestId);
        return in;
    }
}
