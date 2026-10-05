-- ---------------------------------------------------------------------
-- 电子装配厂 · 「质检不合格 → 返修」闭环修复 · MySQL 8 增量迁移
-- 适用：已在跑旧版本（只有 inspection.repair_done、没有 repair_task 表）的库。
-- 全新部署不需要本文件（schema.sql 已包含全部结构）。
--
-- 幂等：可重复执行（MySQL 8.0 的 ALTER TABLE 不支持 IF NOT EXISTS，
-- 这里用 information_schema 判断 + 预处理语句兜底；CREATE TABLE IF NOT EXISTS 原生支持）。
-- 迁移后首次启动应用时，LegacyRepairBackfillRunner 会为历史「返修未闭环」质检
-- 幂等补建返修任务，并把对应工序游标置为「返修中」。
-- ---------------------------------------------------------------------

USE electronics_assembly;

-- 1) inspection 增加提交幂等号（历史数据为 NULL，不影响旧接口）
SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inspection' AND COLUMN_NAME = 'request_id') = 0,
    'ALTER TABLE inspection ADD COLUMN request_id VARCHAR(64) NULL COMMENT ''提交幂等号：同号重放返回首条''',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inspection' AND INDEX_NAME = 'uk_insp_request') = 0,
    'ALTER TABLE inspection ADD UNIQUE KEY uk_insp_request (request_id)',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 返修任务（生成列 + 两道唯一索引：一条质检一个任务；同工单同工序同时一个在制任务）
CREATE TABLE IF NOT EXISTS repair_task (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id            BIGINT      NOT NULL,
    inspection_id       BIGINT      NOT NULL COMMENT '触发任务的质检记录，一条质检最多一个返修任务',
    step_name           VARCHAR(16) NOT NULL,
    round_no            INT         NOT NULL DEFAULT 1 COMMENT '返修轮次：复验不通过 +1',
    status              VARCHAR(16) NOT NULL COMMENT '待返修/待复验/已复验通过',
    repair_by           VARCHAR(32),
    repair_note         VARCHAR(255),
    repaired_at         DATETIME,
    rechecked_by        VARCHAR(32),
    last_recheck_result VARCHAR(16),
    rechecked_at        DATETIME,
    created_at          DATETIME,
    active_order_id     BIGINT GENERATED ALWAYS AS (IF(status <> '已复验通过', order_id, NULL)) STORED,
    active_step_name    VARCHAR(16) GENERATED ALWAYS AS (IF(status <> '已复验通过', step_name, NULL)) STORED,
    CONSTRAINT fk_rt_order FOREIGN KEY (order_id) REFERENCES work_order (id),
    CONSTRAINT fk_rt_inspection FOREIGN KEY (inspection_id) REFERENCES inspection (id),
    UNIQUE KEY uk_rt_inspection (inspection_id),
    UNIQUE KEY uk_rt_open (active_order_id, active_step_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) 返修处理记录
CREATE TABLE IF NOT EXISTS repair_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id    BIGINT      NOT NULL,
    action     VARCHAR(16) NOT NULL,
    round_no   INT         NOT NULL,
    actor      VARCHAR(32) NOT NULL,
    detail     VARCHAR(255),
    created_at DATETIME,
    CONSTRAINT fk_re_task FOREIGN KEY (task_id) REFERENCES repair_task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4) smt_step.status 增加「返修中」取值（VARCHAR 无需改类型，仅补注释口径，可重复执行）
ALTER TABLE smt_step MODIFY COLUMN status VARCHAR(16) NOT NULL
    COMMENT '待执行/进行中/已完成/返修中';

-- 5) 旧数据兜底：历史 repair_done=1 的「返修」质检视同已闭环，不补在制任务；
--    repair_done=0 的默认由应用启动后的 LegacyRepairBackfillRunner 补建。
--    若要在 SQL 侧一次性补建（不启动应用），可手工执行文件末尾注释中的两条语句。
--
-- INSERT INTO repair_task (order_id, inspection_id, step_name, round_no, status, created_at)
-- SELECT i.order_id, i.id, i.step_name, 1, '待返修', UTC_TIMESTAMP()
-- FROM inspection i
-- WHERE i.verdict = '返修' AND i.repair_done = 0
--   AND NOT EXISTS (SELECT 1 FROM repair_task t WHERE t.inspection_id = i.id);
--
-- UPDATE smt_step s
-- JOIN inspection i ON i.order_id = s.order_id AND i.step_name = s.step_name
-- SET s.status = '返修中', s.finished_at = NULL
-- WHERE i.verdict = '返修' AND i.repair_done = 0 AND s.status <> '返修中';
