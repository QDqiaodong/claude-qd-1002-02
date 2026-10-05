-- H2(MODE=MySQL) 测试库结构：与生产 MySQL schema.sql 的表/列/唯一约束一一对应。
-- 差异仅为方言：IDENTITY、BOOLEAN、生成列用 CASE WHEN（H2 不支持 IF()/STORED 语法）。
-- 唯一约束（uk_rt_inspection / uk_rt_open / uk_insp_request）必须真实存在，
-- 并发提交的兜底行为要靠它们在集成测试里验证。

DROP TABLE IF EXISTS repair_event;
DROP TABLE IF EXISTS repair_task;
DROP TABLE IF EXISTS line_change_event;
DROP TABLE IF EXISTS line_change_batch;
DROP TABLE IF EXISTS order_line_config;
DROP TABLE IF EXISTS inspection;
DROP TABLE IF EXISTS smt_step;
DROP TABLE IF EXISTS bom_item;
DROP TABLE IF EXISTS component;
DROP TABLE IF EXISTS work_order;

CREATE TABLE work_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32) NOT NULL UNIQUE,
    product_model VARCHAR(48) NOT NULL,
    plan_qty      INT         NOT NULL,
    done_qty      INT         NOT NULL DEFAULT 0,
    priority      VARCHAR(8)  NOT NULL,
    status        VARCHAR(16) NOT NULL,
    due_date      DATE,
    created_at    DATETIME
);

CREATE TABLE component (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    part_no      VARCHAR(32) NOT NULL UNIQUE,
    part_name    VARCHAR(64) NOT NULL,
    package_type VARCHAR(16) NOT NULL,
    stock_qty    INT         NOT NULL DEFAULT 0,
    feed_slot    VARCHAR(16),
    status       VARCHAR(16) NOT NULL
);

CREATE TABLE bom_item (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_model VARCHAR(48) NOT NULL,
    part_no       VARCHAR(32) NOT NULL,
    unit_qty      INT         NOT NULL,
    CONSTRAINT uk_bom UNIQUE (product_model, part_no)
);

CREATE TABLE smt_step (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT      NOT NULL,
    step_name   VARCHAR(16) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    operator    VARCHAR(32),
    started_at  DATETIME,
    finished_at DATETIME,
    CONSTRAINT fk_step_order FOREIGN KEY (order_id) REFERENCES work_order (id)
);

CREATE TABLE inspection (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id      BIGINT      NOT NULL,
    step_name     VARCHAR(16) NOT NULL,
    defect_type   VARCHAR(16) NOT NULL,
    defect_points INT         NOT NULL DEFAULT 0,
    sample_qty    INT         NOT NULL DEFAULT 0,
    verdict       VARCHAR(16) NOT NULL,
    inspector     VARCHAR(32),
    request_id    VARCHAR(64),
    repair_done   BOOLEAN     NOT NULL DEFAULT FALSE,
    checked_at    DATETIME,
    CONSTRAINT fk_insp_order FOREIGN KEY (order_id) REFERENCES work_order (id),
    CONSTRAINT uk_insp_request UNIQUE (request_id)
);

CREATE TABLE repair_task (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id            BIGINT      NOT NULL,
    inspection_id       BIGINT      NOT NULL,
    step_name           VARCHAR(16) NOT NULL,
    round_no            INT         NOT NULL DEFAULT 1,
    status              VARCHAR(16) NOT NULL,
    repair_by           VARCHAR(32),
    repair_note         VARCHAR(255),
    repaired_at         DATETIME,
    rechecked_by        VARCHAR(32),
    last_recheck_result VARCHAR(16),
    rechecked_at        DATETIME,
    created_at          DATETIME,
    -- 与生产库 IF(status <> '已复验通过', ...) STORED 口径一致的 H2 写法
    active_order_id     BIGINT GENERATED ALWAYS AS (CASE WHEN status <> '已复验通过' THEN order_id ELSE NULL END),
    active_step_name    VARCHAR(16) GENERATED ALWAYS AS (CASE WHEN status <> '已复验通过' THEN step_name ELSE NULL END),
    CONSTRAINT fk_rt_order FOREIGN KEY (order_id) REFERENCES work_order (id),
    CONSTRAINT fk_rt_inspection FOREIGN KEY (inspection_id) REFERENCES inspection (id),
    CONSTRAINT uk_rt_inspection UNIQUE (inspection_id),
    CONSTRAINT uk_rt_open UNIQUE (active_order_id, active_step_name)
);

CREATE TABLE repair_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id    BIGINT      NOT NULL,
    action     VARCHAR(16) NOT NULL,
    round_no   INT         NOT NULL,
    actor      VARCHAR(32) NOT NULL,
    detail     VARCHAR(255),
    created_at DATETIME,
    CONSTRAINT fk_re_task FOREIGN KEY (task_id) REFERENCES repair_task (id)
);

CREATE TABLE order_line_config (
    order_id      BIGINT PRIMARY KEY,
    equipment_no  VARCHAR(32)  NOT NULL,
    feeder_config VARCHAR(255) NOT NULL,
    key_params    VARCHAR(255) NOT NULL,
    config_hash   VARCHAR(64)  NOT NULL,
    updated_by    VARCHAR(32),
    updated_at    DATETIME,
    CONSTRAINT fk_olc_order FOREIGN KEY (order_id) REFERENCES work_order (id)
);

CREATE TABLE line_change_batch (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id       BIGINT       NOT NULL,
    version_no     INT          NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    equipment_no   VARCHAR(32)  NOT NULL,
    feeder_config  VARCHAR(255) NOT NULL,
    key_params     VARCHAR(255) NOT NULL,
    config_hash    VARCHAR(64)  NOT NULL,
    change_reason  VARCHAR(255),
    created_by     VARCHAR(32)  NOT NULL,
    created_at     DATETIME,
    first_result   VARCHAR(16),
    first_note     VARCHAR(255),
    submitted_by   VARCHAR(32),
    submitted_at   DATETIME,
    confirmed_by   VARCHAR(32),
    confirmed_at   DATETIME,
    reject_reason  VARCHAR(255),
    active_order_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('待首件','待确认','已放行') THEN order_id ELSE NULL END),
    CONSTRAINT fk_lcb_order FOREIGN KEY (order_id) REFERENCES work_order (id),
    CONSTRAINT uk_lcb_version UNIQUE (order_id, version_no),
    CONSTRAINT uk_lcb_active UNIQUE (active_order_id)
);

CREATE TABLE line_change_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id   BIGINT      NOT NULL,
    action     VARCHAR(16) NOT NULL,
    actor      VARCHAR(32) NOT NULL,
    detail     VARCHAR(255),
    created_at DATETIME,
    CONSTRAINT fk_lce_batch FOREIGN KEY (batch_id) REFERENCES line_change_batch (id)
);
