-- ---------------------------------------------------------------------
-- 电子装配厂 · 工单分批生产增量迁移（MySQL 8）
-- 适用：已在跑旧版本、需要保留既有数据的库；全新部署不需要本文件（schema.sql 已含全部结构）。
-- 幂等：可重复执行。结构完成后由应用侧首次启用分批时迁移旧完成数量到只读历史批次。
-- ---------------------------------------------------------------------

USE electronics_assembly;

CREATE TABLE IF NOT EXISTS production_batch (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id         BIGINT      NOT NULL,
    batch_no         VARCHAR(36) NOT NULL COMMENT '母工单内唯一批次号',
    plan_qty         INT         NOT NULL COMMENT '计划板数',
    reported_qty     INT         NOT NULL DEFAULT 0 COMMENT '已报工板数',
    inspected_qty    INT         NOT NULL DEFAULT 0 COMMENT '已送检板数',
    completed_qty    INT         NOT NULL DEFAULT 0 COMMENT '已完成板数',
    delivery_seq     INT             NULL COMMENT '交付顺序；只读历史批次为 NULL，新批次从 1 开始',
    status           VARCHAR(16) NOT NULL COMMENT '待投产/贴片中/待检验/已完成/已取消/历史',
    readonly_history TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '只读历史批次',
    cancel_reason    VARCHAR(255),
    created_at       DATETIME,
    released_at      DATETIME,
    finished_at      DATETIME,
    CONSTRAINT fk_pb_order FOREIGN KEY (order_id) REFERENCES work_order (id),
    UNIQUE KEY uk_pb_order_batch_no (order_id, batch_no),
    UNIQUE KEY uk_pb_order_seq (order_id, delivery_seq),
    CONSTRAINT ck_pb_qty CHECK (
        plan_qty > 0 AND reported_qty >= 0 AND inspected_qty >= 0 AND completed_qty >= 0
        AND reported_qty <= plan_qty AND inspected_qty <= plan_qty AND completed_qty <= plan_qty
        AND reported_qty >= inspected_qty AND inspected_qty >= completed_qty
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS batch_adjustment_event (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id         BIGINT      NOT NULL,
    batch_id         BIGINT      NULL COMMENT '删除后可为空，order_id/batch_no 保留审计轨迹',
    batch_no         VARCHAR(36),
    action           VARCHAR(16) NOT NULL COMMENT '拆分/调整/删除/取消/回收/减产/历史迁移',
    before_qty       INT,
    after_qty        INT,
    related_batch_id BIGINT,
    related_batch_no VARCHAR(36),
    operator         VARCHAR(32) NOT NULL,
    detail           VARCHAR(255),
    created_at       DATETIME,
    CONSTRAINT fk_bae_order FOREIGN KEY (order_id) REFERENCES work_order (id),
    CONSTRAINT fk_bae_batch FOREIGN KEY (batch_id) REFERENCES production_batch (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'smt_step' AND COLUMN_NAME = 'batch_id') = 0,
    'ALTER TABLE smt_step ADD COLUMN batch_id BIGINT NULL COMMENT ''制造批次；NULL 为历史工单原流程'' AFTER order_id',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'smt_step' AND INDEX_NAME = 'fk_step_batch') = 0,
    'ALTER TABLE smt_step ADD CONSTRAINT fk_step_batch FOREIGN KEY (batch_id) REFERENCES production_batch (id)',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'smt_step' AND INDEX_NAME = 'uk_step_batch') = 0,
    'ALTER TABLE smt_step ADD UNIQUE KEY uk_step_batch (batch_id, step_name)',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inspection' AND COLUMN_NAME = 'batch_id') = 0,
    'ALTER TABLE inspection ADD COLUMN batch_id BIGINT NULL AFTER order_id',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inspection' AND INDEX_NAME = 'fk_insp_batch') = 0,
    'ALTER TABLE inspection ADD CONSTRAINT fk_insp_batch FOREIGN KEY (batch_id) REFERENCES production_batch (id)',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'repair_task' AND COLUMN_NAME = 'batch_id') = 0,
    'ALTER TABLE repair_task ADD COLUMN batch_id BIGINT NULL AFTER order_id',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'repair_task' AND INDEX_NAME = 'fk_rt_batch') = 0,
    'ALTER TABLE repair_task ADD CONSTRAINT fk_rt_batch FOREIGN KEY (batch_id) REFERENCES production_batch (id)',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 老版本可能已有 uk_rt_open(order_id, step_name)；分批后改为(order_id, COALESCE(batch_id,0), step_name)。
SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'repair_task' AND INDEX_NAME = 'uk_rt_open'
         AND COLUMN_NAME = 'active_batch_id') = 0,
    'ALTER TABLE repair_task DROP INDEX uk_rt_open',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'repair_task' AND COLUMN_NAME = 'active_batch_id') = 0,
    'ALTER TABLE repair_task ADD COLUMN active_batch_id BIGINT GENERATED ALWAYS AS (IF(status <> ''已复验通过'', COALESCE(batch_id, 0), NULL)) STORED COMMENT ''未闭环批次占位：0=历史工单''',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'repair_task' AND INDEX_NAME = 'uk_rt_open') = 0,
    'ALTER TABLE repair_task ADD UNIQUE KEY uk_rt_open (active_order_id, active_batch_id, active_step_name)',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
