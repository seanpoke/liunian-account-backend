-- 流年小账 数据库脚本 (MySQL 8) —— 金额单位：分(整数)
-- ⚠️ 本脚本仅用于【开发环境】：数据库名 liunian_account_dev。
--    liunian_account 是已有真实用户的生产库，禁止在本项目中对其执行任何建表/改表/清库操作。
-- 数据库需先创建：CREATE DATABASE IF NOT EXISTS liunian_account_dev DEFAULT CHARSET utf8mb4;
--
-- 本脚本为「全新建库」的最终形态（2026-10-19 结构调整后）：
--   1) 时区：业务时间统一由应用侧 Asia/Shanghai 产生，不使用数据库 NOW()；
--   2) family 移除 member_count，成员数改为实时统计 family_member；
--   3) family_member 只保留真实成员（无 status/source），申请审批迁移至 join_request；
--   4) invitation 拆分为 invitation(1) + join_request(N)，取消 used，未过期即可用；
--   5) transaction 增加作废状态（成员退出/被移除时置 0，不参与查询统计）；
--   6) category.code 增加全局唯一约束（自定义分类不填 code 即可，NULL 互不冲突）。
--
-- 存量库升级请勿执行本脚本，改执行：ci/migration/20261019_refactor.sql

-- 防御护栏：显式切到【开发库】，避免误在共享实例的 root 默认 schema（如 checkin）
-- 或生产库 liunian_account 上建表。
USE `liunian_account_dev`;

CREATE TABLE IF NOT EXISTS `user` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID，自增主键',
  `openid`            VARCHAR(64)  NOT NULL COMMENT '微信 openid，全局唯一',
  `unionid`           VARCHAR(64)  DEFAULT NULL COMMENT '微信 unionid，同一开放平台下跨应用统一标识',
  `nickname`          VARCHAR(64)  DEFAULT NULL COMMENT '微信昵称，登录/资料更新时写入',
  `avatar`            VARCHAR(512) DEFAULT NULL COMMENT '头像 URL，登录/资料更新时写入',
  `privacy_agreed_at` DATETIME     DEFAULT NULL COMMENT '隐私协议同意时间，NULL 表示尚未同意（合规留痕，保留）',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `family` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '家庭ID，自增主键',
  `name`           VARCHAR(64)  NOT NULL COMMENT '家庭名称',
  `creator_openid` VARCHAR(64)  NOT NULL COMMENT '创建者 openid',
  `currency`       VARCHAR(8)   NOT NULL DEFAULT 'CNY' COMMENT '币种，默认 CNY',
  `avatar`         VARCHAR(512) DEFAULT NULL COMMENT '家庭头像 URL',
  `status`         VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT '状态：active=正常（预留 archived 等扩展值）',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_creator` (`creator_openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭表（成员数由 family_member 实时统计，不再冗余存储）';

CREATE TABLE IF NOT EXISTS `family_member` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '成员记录ID，自增主键',
  `family_id`  BIGINT       NOT NULL COMMENT '所属家庭ID',
  `openid`     VARCHAR(64)  NOT NULL COMMENT '成员 openid',
  `nickname`   VARCHAR(64)  NOT NULL COMMENT '成员在家庭内的显示昵称（加入时取自 user/申请单，可单独修改）',
  `avatar`     VARCHAR(512) DEFAULT NULL COMMENT '成员头像 URL（加入时取自 user/申请单）',
  `role`       VARCHAR(16)  NOT NULL DEFAULT 'member' COMMENT '角色：owner=创建者/管理员，member=普通成员',
  `joined_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '实际加入时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family_openid` (`family_id`,`openid`),
  KEY `idx_family` (`family_id`),
  KEY `idx_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭成员表（仅保留真实成员；退出/被移除即物理删除，申请见 join_request）';

CREATE TABLE IF NOT EXISTS `invitation` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '邀请记录ID，自增主键',
  `family_id`      BIGINT       NOT NULL COMMENT '对应家庭ID',
  `inviter_openid` VARCHAR(64)  NOT NULL COMMENT '邀请人 openid',
  `token`          VARCHAR(64)  NOT NULL COMMENT '邀请令牌（分享链接/二维码载体），全局唯一，未过期即可复用',
  `type`           VARCHAR(16)  NOT NULL DEFAULT 'share' COMMENT '类型：share=分享链接，qr=小程序码',
  `expire_at`      DATETIME     NOT NULL COMMENT '过期时间，创建时=now+invite.expire-hours（默认24小时）',
  `revoked_at`     DATETIME     DEFAULT NULL COMMENT '撤销时间，NULL=未撤销；替代原 used 字段',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token` (`token`),
  KEY `idx_family_type_expire` (`family_id`,`type`,`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭邀请表（一条邀请可被多人使用，1:N）';

CREATE TABLE IF NOT EXISTS `join_request` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申请记录ID，自增主键',
  `family_id`        BIGINT       NOT NULL COMMENT '申请加入的家庭ID',
  `invitation_id`    BIGINT       DEFAULT NULL COMMENT '来源邀请ID（invitation.id），主动申请时可为 NULL',
  `applicant_openid` VARCHAR(64)  NOT NULL COMMENT '申请人 openid',
  `nickname`         VARCHAR(64)  NOT NULL COMMENT '申请人昵称（申请时提交，审批通过后写入 family_member）',
  `avatar`           VARCHAR(512) DEFAULT NULL COMMENT '申请人头像 URL（审批通过后写入 family_member）',
  `channel`          VARCHAR(16)  NOT NULL DEFAULT 'invite' COMMENT '申请渠道：invite=邀请进入，self=主动申请（承接原 source）',
  `status`           VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '状态：pending=待审批，approved=已通过，rejected=已拒绝，expired=已过期/邀请被撤销，canceled=已取消',
  `reviewed_by`      VARCHAR(64)  DEFAULT NULL COMMENT '审核人 openid',
  `reviewed_at`      DATETIME     DEFAULT NULL COMMENT '审核操作时间',
  `remark`           VARCHAR(200) DEFAULT NULL COMMENT '审批备注/拒绝原因',
  `pending_flag`     TINYINT      GENERATED ALWAYS AS (IF(`status` = 'pending', 1, NULL)) STORED COMMENT '派生列：仅待审批时为1，其余为NULL，用于唯一键约束「同一家庭同一人只能有一条待审批」',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family_applicant_pending` (`family_id`,`applicant_openid`,`pending_flag`),
  KEY `idx_family_status_created` (`family_id`,`status`,`created_at`),
  KEY `idx_invitation` (`invitation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加入家庭申请/审批表（审批通过后写入 family_member，本表保留审批留痕）';

CREATE TABLE IF NOT EXISTS `transaction` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '账目ID，自增主键',
  `family_id`      BIGINT       NOT NULL COMMENT '所属家庭ID',
  `openid`         VARCHAR(64)  NOT NULL COMMENT '记账人 openid',
  `type`           VARCHAR(16)  NOT NULL COMMENT '类型：income=收入，expense=支出',
  `amount`         INT          NOT NULL COMMENT '金额，单位分（整数），恒为正整数',
  `category_id`    BIGINT       DEFAULT NULL COMMENT '分类ID（关联 category.id），可为空',
  `category_name`  VARCHAR(32)  DEFAULT NULL COMMENT '分类名称（冗余存储，防止分类改名后历史失真）',
  `category_icon`  VARCHAR(32)  DEFAULT NULL COMMENT '分类图标（冗余存储，防止图标变更后历史失真）',
  `category_color` VARCHAR(16)  DEFAULT NULL COMMENT '分类颜色（冗余存储，防止分类改色后历史失真）',
  `date`           DATE         NOT NULL COMMENT '记账日期（业务日期，按 Asia/Shanghai）',
  `occur_time`     CHAR(5)      DEFAULT NULL COMMENT '业务时间 HH:mm（精确到分钟）；历史数据为 NULL，展示时回退 created_at',
  `note`           VARCHAR(200) DEFAULT NULL COMMENT '备注',
  `images`         JSON         DEFAULT NULL COMMENT '凭证/小票图片地址数组（相对路径 /api/files/...）',
  `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '账目状态：1=有效（默认），0=已作废（成员退出/被移除时置0，不参与查询与统计）',
  `voided_at`      DATETIME     DEFAULT NULL COMMENT '作废时间，status=0 时写入',
  `void_reason`    VARCHAR(32)  DEFAULT NULL COMMENT '作废原因：member_quit=成员退出，member_removed=成员被移除',
  `voided_by`      VARCHAR(64)  DEFAULT NULL COMMENT '执行作废的人 openid（本人退出=本人，被移除=创建者）',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_family_status_date` (`family_id`,`status`,`date`),
  KEY `idx_family_openid` (`family_id`,`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账目流水表（查询与统计默认只取 status=1 的有效流水）';

CREATE TABLE IF NOT EXISTS `category` (
  `id`        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分类ID，自增主键',
  `family_id` BIGINT       DEFAULT NULL COMMENT '归属家庭ID，NULL 表示系统预置（global）',
  `name`      VARCHAR(32)  NOT NULL COMMENT '分类名称',
  `type`      VARCHAR(16)  NOT NULL COMMENT '类型：income=收入，expense=支出',
  `code`      VARCHAR(32)  DEFAULT NULL COMMENT '分类语义编码，全局唯一，如 food；家庭自定义分类建议不填（NULL 互不冲突）',
  `icon`      VARCHAR(32)  DEFAULT NULL COMMENT '分类图标（emoji，如 🍜）',
  `color`     VARCHAR(16)  DEFAULT NULL COMMENT '分类颜色（十六进制，如 #FF7043），用于账单列表着色并快照进 transaction.category_color',
  `sort`      INT          DEFAULT 0 COMMENT '排序权重，越小越靠前',
  `is_system` TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否系统预置：0=自定义，1=系统',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_family_type` (`family_id`,`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分类表（系统预置 family_id=NULL；自定义分类 code 留空）';

CREATE TABLE IF NOT EXISTS `reminder_setting` (
  `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '提醒设置ID，自增主键',
  `family_id`           BIGINT       NOT NULL COMMENT '所属家庭ID',
  `openid`              VARCHAR(64)  NOT NULL COMMENT '关联用户 openid',
  `enabled`             TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否启用记账提醒：0=关闭，1=开启',
  `time`                TIME         DEFAULT NULL COMMENT '每日提醒时间（Asia/Shanghai）',
  `tmpl_id`             VARCHAR(64)  DEFAULT NULL COMMENT '微信订阅消息模板ID',
  `subscribe_count`     INT          NOT NULL DEFAULT 0 COMMENT '一次性订阅剩余可用次数',
  `subscribe_expire_at` DATETIME     DEFAULT NULL COMMENT '订阅授权过期时间',
  `last_sent_at`        DATETIME     DEFAULT NULL COMMENT '上次发送提醒时间',
  `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family_openid` (`family_id`,`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记账提醒设置表';

CREATE TABLE IF NOT EXISTS `budget` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '预算记录ID，自增主键',
  `family_id`  BIGINT       NOT NULL COMMENT '所属家庭ID',
  `amount`     INT          NOT NULL DEFAULT 0 COMMENT '家庭当月预算（单位分），0 表示未设置',
  `updated_by` VARCHAR(64)  DEFAULT NULL COMMENT '最近更新人 openid',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family` (`family_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭预算表';

-- 系统预置分类（family_id=NULL 表示 global）；code=语义编码，icon=emoji，color=账单列表配色
INSERT IGNORE INTO `category` (`family_id`,`name`,`type`,`code`,`icon`,`color`,`sort`,`is_system`) VALUES
  (NULL,'餐饮','expense','food',        '🍜','#FF7043',1,1),
  (NULL,'交通','expense','transport',   '🚌','#42A5F5',2,1),
  (NULL,'购物','expense','shopping',    '🛒','#AB47BC',3,1),
  (NULL,'居家','expense','home',        '🏠','#26A69A',4,1),
  (NULL,'娱乐','expense','fun',         '🎮','#FFA726',5,1),
  (NULL,'医疗','expense','medical',     '💊','#EF5350',6,1),
  (NULL,'其他','expense','other',       '📦','#78909C',7,1),
  (NULL,'工资','income', 'salary',      '💰','#66BB6A',1,1),
  (NULL,'奖金','income', 'bonus',       '🎁','#9CCC65',2,1),
  (NULL,'理财','income', 'finance',     '📈','#29B6F6',3,1),
  (NULL,'其他','income', 'other_income', '📦','#78909C',4,1);
