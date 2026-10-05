SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 电子装配厂 · SMT 工单与齐套装配管理
-- 库名 electronics_assembly；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注意：mysql 容器是 UTC，种子数据里的时间一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR。

CREATE DATABASE IF NOT EXISTS electronics_assembly
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE electronics_assembly;

SET FOREIGN_KEY_CHECKS = 0;
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
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 生产工单 ----------------
CREATE TABLE work_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32) NOT NULL UNIQUE,
    product_model VARCHAR(48) NOT NULL,
    plan_qty      INT         NOT NULL COMMENT '计划投产板数',
    done_qty      INT         NOT NULL DEFAULT 0,
    priority      VARCHAR(8)  NOT NULL COMMENT '高/中/低',
    status        VARCHAR(16) NOT NULL COMMENT '待投产/贴片中/待检验/已完成/已取消',
    due_date      DATE,
    created_at    DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 物料台账 ----------------
CREATE TABLE component (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    part_no      VARCHAR(32) NOT NULL UNIQUE,
    part_name    VARCHAR(64) NOT NULL,
    package_type VARCHAR(16) NOT NULL COMMENT '0201/0402/0603/0805/SOT/QFP/BGA',
    stock_qty    INT         NOT NULL DEFAULT 0 COMMENT '现有库存（颗）',
    feed_slot    VARCHAR(16) COMMENT '飞达位',
    status       VARCHAR(16) NOT NULL COMMENT '正常/缺料/停用'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- BOM：某机型单板用料 ----------------
CREATE TABLE bom_item (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_model VARCHAR(48) NOT NULL,
    part_no       VARCHAR(32) NOT NULL,
    unit_qty      INT         NOT NULL COMMENT '单板用量（颗）',
    UNIQUE KEY uk_bom (product_model, part_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- SMT 工序流转 ----------------
CREATE TABLE smt_step (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT      NOT NULL,
    step_name   VARCHAR(16) NOT NULL COMMENT '上料/贴片/回流焊/AOI/目检',
    status      VARCHAR(16) NOT NULL COMMENT '待执行/进行中/已完成/返修中',
    operator    VARCHAR(32),
    started_at  DATETIME,
    finished_at DATETIME,
    CONSTRAINT fk_step_order FOREIGN KEY (order_id) REFERENCES work_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 质检与返修 ----------------
CREATE TABLE inspection (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id      BIGINT      NOT NULL,
    step_name     VARCHAR(16) NOT NULL,
    defect_type   VARCHAR(16) NOT NULL COMMENT '少件/偏移/虚焊/连锡/反向',
    defect_points INT         NOT NULL DEFAULT 0 COMMENT '不良点数，超过 12 判废',
    sample_qty    INT         NOT NULL DEFAULT 0 COMMENT '抽检板数',
    verdict       VARCHAR(16) NOT NULL COMMENT '合格/返修/判废',
    inspector     VARCHAR(32),
    request_id    VARCHAR(64)          COMMENT '提交幂等号：同号重放返回首条，历史数据为 NULL',
    repair_done   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '返修是否闭环（复验通过置 1）',
    checked_at    DATETIME,
    CONSTRAINT fk_insp_order FOREIGN KEY (order_id) REFERENCES work_order (id),
    UNIQUE KEY uk_insp_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 返修任务（质检不合格 → 返修 → 复验闭环） ----------------
CREATE TABLE repair_task (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id            BIGINT      NOT NULL,
    inspection_id       BIGINT      NOT NULL COMMENT '触发任务的质检记录，一条质检最多一个返修任务',
    step_name           VARCHAR(16) NOT NULL COMMENT '返修发生的工序',
    round_no            INT         NOT NULL DEFAULT 1 COMMENT '返修轮次：复验不通过 +1',
    status              VARCHAR(16) NOT NULL COMMENT '待返修/待复验/已复验通过',
    repair_by           VARCHAR(32) COMMENT '最近一轮返修人',
    repair_note         VARCHAR(255),
    repaired_at         DATETIME,
    rechecked_by        VARCHAR(32) COMMENT '复验质检员（闭环只能由质检员推进）',
    last_recheck_result VARCHAR(16) COMMENT '最近一次复验结论：合格/不合格',
    rechecked_at        DATETIME,
    created_at          DATETIME,
    -- 未闭环占位列：任务未复验通过时等于 order_id / step_name，否则 NULL。
    -- 配合唯一索引，数据库层面保证「同工单同工序同时只有一个未闭环返修任务」，
    -- 不同工单的 order_id 不同，天然不可能串单；重复点击/并发提交由它兜底。
    active_order_id     BIGINT GENERATED ALWAYS AS (IF(status <> '已复验通过', order_id, NULL)) STORED
                        COMMENT '未闭环占位：同工单同工序唯一',
    active_step_name    VARCHAR(16) GENERATED ALWAYS AS (IF(status <> '已复验通过', step_name, NULL)) STORED
                        COMMENT '未闭环工序占位：同工单同工序唯一',
    CONSTRAINT fk_rt_order FOREIGN KEY (order_id) REFERENCES work_order (id),
    CONSTRAINT fk_rt_inspection FOREIGN KEY (inspection_id) REFERENCES inspection (id),
    UNIQUE KEY uk_rt_inspection (inspection_id),
    UNIQUE KEY uk_rt_open (active_order_id, active_step_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 返修处理记录（按轮次完整审计链） ----------------
CREATE TABLE repair_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id    BIGINT      NOT NULL,
    action     VARCHAR(16) NOT NULL COMMENT '建任务/返修完成/复验通过/复验不通过',
    round_no   INT         NOT NULL COMMENT '记录产生时的返修轮次',
    actor      VARCHAR(32) NOT NULL,
    detail     VARCHAR(255),
    created_at DATETIME,
    CONSTRAINT fk_re_task FOREIGN KEY (task_id) REFERENCES repair_task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 工单当前产线配置（换线比对基准 + 并发锁定点） ----------------
CREATE TABLE order_line_config (
    order_id      BIGINT PRIMARY KEY,
    equipment_no  VARCHAR(32)  NOT NULL COMMENT '生产设备编号',
    feeder_config VARCHAR(255) NOT NULL COMMENT '飞达配置',
    key_params    VARCHAR(255) NOT NULL COMMENT '关键工艺参数',
    config_hash   VARCHAR(64)  NOT NULL COMMENT '配置快照哈希：SHA-256(设备|飞达|参数)',
    updated_by    VARCHAR(32),
    updated_at    DATETIME,
    CONSTRAINT fk_olc_order FOREIGN KEY (order_id) REFERENCES work_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 换线批次（配置快照 + 版本号 + 首件放行流转） ----------------
CREATE TABLE line_change_batch (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id       BIGINT       NOT NULL,
    version_no     INT          NOT NULL COMMENT '批次版本号，每工单递增',
    status         VARCHAR(16)  NOT NULL COMMENT '待首件/待确认/已放行/已驳回/已失效',
    equipment_no   VARCHAR(32)  NOT NULL COMMENT '生产设备快照',
    feeder_config  VARCHAR(255) NOT NULL COMMENT '飞达配置快照',
    key_params     VARCHAR(255) NOT NULL COMMENT '关键工艺参数快照',
    config_hash    VARCHAR(64)  NOT NULL COMMENT '配置快照哈希，确认时与当前配置比对',
    change_reason  VARCHAR(255) COMMENT '换线/修正原因',
    created_by     VARCHAR(32)  NOT NULL COMMENT '建批操作员',
    created_at     DATETIME,
    first_result   VARCHAR(16)  COMMENT '首件检测结果：合格/不合格',
    first_note     VARCHAR(255) COMMENT '首件检测数据',
    submitted_by   VARCHAR(32)  COMMENT '首件提交人（操作员）',
    submitted_at   DATETIME,
    confirmed_by   VARCHAR(32)  COMMENT '确认质检员，不得与提交人同人',
    confirmed_at   DATETIME,
    reject_reason  VARCHAR(255) COMMENT '驳回原因（驳回必填）',
    -- 有效批次占位列：待首件/待确认/已放行时等于 order_id，否则为 NULL。
    -- 配合唯一索引，数据库层面保证「一张工单最多一个有效版本」，并发下也不会出现两个。
    active_order_id BIGINT GENERATED ALWAYS AS (IF(status IN ('待首件','待确认','已放行'), order_id, NULL)) STORED COMMENT '有效批次占位：一单一有效版本',
    CONSTRAINT fk_lcb_order FOREIGN KEY (order_id) REFERENCES work_order (id),
    UNIQUE KEY uk_lcb_version (order_id, version_no),
    UNIQUE KEY uk_lcb_active (active_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 换线处理记录（完整审计链） ----------------
CREATE TABLE line_change_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id   BIGINT      NOT NULL,
    action     VARCHAR(16) NOT NULL COMMENT '建批/提交首件/确认放行/驳回/批次失效/修正重提',
    actor      VARCHAR(32) NOT NULL,
    detail     VARCHAR(255),
    created_at DATETIME,
    CONSTRAINT fk_lce_batch FOREIGN KEY (batch_id) REFERENCES line_change_batch (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT INTO work_order (order_no, product_model, plan_qty, done_qty, priority, status, due_date, created_at) VALUES
('WO-2026001', 'MODEL-A', 500,  0, '高', '待投产', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL  2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
('WO-2026002', 'MODEL-A', 300, 300, '中', '已完成', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL  5 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY),
('WO-2026003', 'MODEL-B', 800, 260, '高', '贴片中', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL  1 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
('WO-2026004', 'MODEL-B', 200,   0, '低', '待投产', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 12 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
('WO-2026005', 'MODEL-C', 400, 400, '中', '待检验', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL  3 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY);

-- 物料：RC-0402-10K 库存偏紧，MODEL-A 500 板 × 8 颗 = 4000 > 库存 3200 → 齐套不通过
INSERT INTO component (part_no, part_name, package_type, stock_qty, feed_slot, status) VALUES
('RC-0402-10K', '10K 电阻',      '0402', 3200,  'F01', '正常'),
('CC-0402-100N', '100nF 电容',   '0402', 9000,  'F02', '正常'),
('IC-SOT-23-AMS', 'AMS1117 稳压', 'SOT',  1200,  'F03', '正常'),
('IC-QFP-MCU',  'MCU 主控',      'QFP',   850,  'F04', '正常'),
('IC-BGA-FPGA', 'FPGA 芯片',     'BGA',    90,  'F05', '缺料'),
('RC-0603-1K',  '1K 电阻',       '0603', 6000,  'F06', '正常'),
('LED-0805-R',  '红色指示灯',    '0805', 1500,  'F07', '正常'),
('CON-0201-4P', '4P 排针',       '0201',   40,  'F08', '停用');

-- BOM：MODEL-A 用前四种；MODEL-B 用 MCU + 电阻 + LED；MODEL-C 用 FPGA 等
INSERT INTO bom_item (product_model, part_no, unit_qty) VALUES
('MODEL-A', 'RC-0402-10K',   8),
('MODEL-A', 'CC-0402-100N', 12),
('MODEL-A', 'IC-SOT-23-AMS', 1),
('MODEL-A', 'IC-QFP-MCU',    1),
('MODEL-B', 'RC-0603-1K',    6),
('MODEL-B', 'IC-QFP-MCU',    1),
('MODEL-B', 'LED-0805-R',    2),
('MODEL-C', 'IC-BGA-FPGA',   1),
('MODEL-C', 'CC-0402-100N',  9),
('MODEL-C', 'RC-0402-10K',   4);

-- 工序：WO-2026003（贴片中）五道工序都在推进；WO-2026001 尚未开工
INSERT INTO smt_step (order_id, step_name, status, operator, started_at, finished_at) VALUES
(3, '上料',   '已完成', '王强', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 HOUR),
(3, '贴片',   '进行中', '李敏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 18 HOUR, NULL),
(3, '回流焊', '待执行', NULL,   NULL, NULL),
(3, 'AOI',    '待执行', NULL,   NULL, NULL),
(3, '目检',   '待执行', NULL,   NULL, NULL),
(5, '上料',   '已完成', '王强', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 29 HOUR),
(5, '贴片',   '已完成', '李敏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 28 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 26 HOUR),
(5, '回流焊', '已完成', '赵磊', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 25 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 24 HOUR),
(5, 'AOI',    '已完成', '孙悦', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 23 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 22 HOUR),
(5, '目检',   '待执行', NULL,   NULL, NULL);

-- 质检：第 2 条不良 15 点已判废；第 4 条不良 5 点判返修且未闭环
INSERT INTO inspection (order_id, step_name, defect_type, defect_points, sample_qty, verdict, inspector, repair_done, checked_at) VALUES
(2, 'AOI',  '偏移',  3,  50, '返修', '孙悦', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(2, 'AOI',  '连锡', 15,  50, '判废', '孙悦', 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(5, 'AOI',  '虚焊',  4,  40, '返修', '孙悦', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(5, '目检', '少件',  5,  40, '返修', '周涛', 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
(3, 'AOI',  '反向',  2,  30, '合格', '孙悦', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR);

-- 工单当前产线配置：config_hash = SHA2('设备|飞达|参数', 256)，与后端 Dict.configHash 口径一致
INSERT INTO order_line_config (order_id, equipment_no, feeder_config, key_params, config_hash, updated_by, updated_at) VALUES
(3, 'SMT-02', 'F01:RC-0603-1K;F04:IC-QFP-MCU;F07:LED-0805-R', '峰值温度245℃；链速80cm/min',
 SHA2('SMT-02|F01:RC-0603-1K;F04:IC-QFP-MCU;F07:LED-0805-R|峰值温度245℃；链速80cm/min', 256),
 '王强', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
(5, 'SMT-03', 'F05:IC-BGA-FPGA;F02:CC-0402-100N;F01:RC-0402-10K', '峰值温度242℃；链速78cm/min',
 SHA2('SMT-03|F05:IC-BGA-FPGA;F02:CC-0402-100N;F01:RC-0402-10K|峰值温度242℃；链速78cm/min', 256),
 '王强', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 26 HOUR);

-- 换线批次：WO-2026003 的 V1 已失效（配置再变更）、V2 已驳回（首件偏移）、V3 待确认（阻塞贴片中）；
-- WO-2026005 的 V1 已放行（历史有效放行样例）
INSERT INTO line_change_batch
(id, order_id, version_no, status, equipment_no, feeder_config, key_params, config_hash, change_reason, created_by, created_at,
 first_result, first_note, submitted_by, submitted_at, confirmed_by, confirmed_at, reject_reason) VALUES
(1, 3, 1, '已失效', 'SMT-01', 'F01:RC-0603-1K;F04:IC-QFP-MCU', '峰值温度240℃；链速75cm/min',
 SHA2('SMT-01|F01:RC-0603-1K;F04:IC-QFP-MCU|峰值温度240℃；链速75cm/min', 256),
 'SMT-01 贴片机上线切换', '王强', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR,
 '合格', '首件 AOI 检测合格', '李敏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR, NULL, NULL, NULL),
(2, 3, 2, '已驳回', 'SMT-02', 'F01:RC-0603-1K;F04:IC-QFP-MCU;F07:LED-0805-R', '峰值温度250℃；链速85cm/min',
 SHA2('SMT-02|F01:RC-0603-1K;F04:IC-QFP-MCU;F07:LED-0805-R|峰值温度250℃；链速85cm/min', 256),
 '换 SMT-02 并调整回流焊曲线', '王强', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR,
 '不合格', '首件偏移 0.3mm，焊盘错位', '李敏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR,
 '孙悦', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, '首件偏移超差，需重新调机并修正温度曲线'),
(3, 3, 3, '待确认', 'SMT-02', 'F01:RC-0603-1K;F04:IC-QFP-MCU;F07:LED-0805-R', '峰值温度245℃；链速80cm/min',
 SHA2('SMT-02|F01:RC-0603-1K;F04:IC-QFP-MCU;F07:LED-0805-R|峰值温度245℃；链速80cm/min', 256),
 '修正吸嘴与温度曲线后重新送检', '王强', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR,
 '合格', '首件 AOI 检测合格，焊点良好', '李敏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR, NULL, NULL, NULL),
(4, 5, 1, '已放行', 'SMT-03', 'F05:IC-BGA-FPGA;F02:CC-0402-100N;F01:RC-0402-10K', '峰值温度242℃；链速78cm/min',
 SHA2('SMT-03|F05:IC-BGA-FPGA;F02:CC-0402-100N;F01:RC-0402-10K|峰值温度242℃；链速78cm/min', 256),
 'FPGA 产线切换', '王强', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 27 HOUR,
 '合格', '首件检测合格', '李敏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 26 HOUR,
 '孙悦', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 25 HOUR, NULL);

-- 换线处理记录：与上面四个批次的流转一一对应
INSERT INTO line_change_event (batch_id, action, actor, detail, created_at) VALUES
(1, '建批',     '王强', 'SMT-01 贴片机上线切换', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR),
(1, '提交首件', '李敏', '首件检测「合格」：首件 AOI 检测合格', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR),
(1, '批次失效', '王强', '配置再次变更，被 V2 取代', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(2, '建批',     '王强', '换 SMT-02 并调整回流焊曲线', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(2, '提交首件', '李敏', '首件检测「不合格」：首件偏移 0.3mm，焊盘错位', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR),
(2, '驳回',     '孙悦', '首件偏移超差，需重新调机并修正温度曲线', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR),
(3, '修正重提', '王强', '源自 V2 驳回：首件偏移超差；修正：修正吸嘴与温度曲线后重新送检', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
(3, '提交首件', '李敏', '首件检测「合格」：首件 AOI 检测合格，焊点良好', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
(4, '建批',     '王强', 'FPGA 产线切换', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 27 HOUR),
(4, '提交首件', '李敏', '首件检测「合格」：首件检测合格', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 26 HOUR),
(4, '确认放行', '孙悦', '首件确认通过，恢复批量贴片', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 25 HOUR);
