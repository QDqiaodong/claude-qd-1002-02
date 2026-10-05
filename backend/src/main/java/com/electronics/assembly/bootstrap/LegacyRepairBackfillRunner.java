package com.electronics.assembly.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.electronics.assembly.service.RepairTaskService;

/**
 * 历史质检数据兼容：新返修任务体系上线前，旧数据只有 inspection.repair_done
 * 而没有 repair_task。启动时为所有「返修未闭环」的旧质检幂等补建返修任务
 * （轮次 1、待返修），并把对应工序游标置「返修中」。
 *
 * <p>幂等保证：repair_task.uk_rt_inspection 唯一索引 + 按 inspectionId 判存，
 * 重复启动 / 多实例并发启动都不会补出第二份。</p>
 */
@Component
@Order(20)
public class LegacyRepairBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyRepairBackfillRunner.class);

    private final RepairTaskService repairTaskService;

    public LegacyRepairBackfillRunner(RepairTaskService repairTaskService) {
        this.repairTaskService = repairTaskService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = repairTaskService.backfillLegacyTasks();
        if (created > 0) {
            log.info("历史质检数据兼容：为 {} 条「返修未闭环」旧记录补建了返修任务", created);
        }
    }
}
