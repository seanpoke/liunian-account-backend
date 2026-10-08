-- 流年小账 数据库脚本 (MySQL 8) —— 金额单位：分(整数)
-- 数据库需先创建：CREATE DATABASE IF NOT EXISTS liunian_account DEFAULT CHARSET utf8mb4;
-- 本脚本相对《接口定义与数据库 SQL》文档的补齐项：
--   category.color / user.privacy_agreed_at / reminder_setting.subscribe_count + subscribe_expire_at / family.updated_at / family_member.updated_at
-- 说明：本脚本在 CREATE TABLE 中各列与表级追加了 COMMENT 注释（会写入 information_schema），方便维护与逆向工具识别。

-- 防御护栏：显式切到本项目专用库，避免误在共享实例的 root 默认 schema（如 checkin）上建表。
USE `liunian_account`;

CREATE TABLE IF NOT EXISTS `user` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID，自增主键',
  `openid`          VARCHAR(64)  NOT NULL COMMENT '微信 openid，全局唯一',
  `unionid`         VARCHAR(64)  DEFAULT NULL COMMENT '微信 unionid，同一开放平台下跨应用统一标识',
  `nickname`        VARCHAR(64)  DEFAULT NULL COMMENT '微信昵称',
  `avatar`          VARCHAR(512) DEFAULT NULL COMMENT '头像 URL',
  `privacy_agreed_at` DATETIME   DEFAULT NULL COMMENT '隐私协议同意时间，NULL 表示尚未同意',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `family` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '家庭ID，自增主键',
  `name`          VARCHAR(64)  NOT NULL COMMENT '家庭名称',
  `creator_openid` VARCHAR(64) NOT NULL COMMENT '创建者 openid',
  `currency`      VARCHAR(8)   NOT NULL DEFAULT 'CNY' COMMENT '币种，默认 CNY',
  `avatar`        VARCHAR(512) DEFAULT NULL COMMENT '家庭头像 URL',
  `member_count`  INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '成员数（冗余计数，便于展示）',
  `status`        VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT '状态：active=正常（预留 archived 等扩展值）',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_creator` (`creator_openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭表';

CREATE TABLE IF NOT EXISTS `family_member` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '成员记录ID，自增主键',
  `family_id`  BIGINT       NOT NULL COMMENT '所属家庭ID',
  `openid`     VARCHAR(64) NOT NULL COMMENT '成员 openid',
  `nickname`   VARCHAR(64) NOT NULL COMMENT '成员在家庭内的显示昵称',
  `avatar`     VARCHAR(512) DEFAULT NULL COMMENT '成员头像 URL',
  `role`       VARCHAR(16) NOT NULL DEFAULT 'member'  COMMENT '角色：owner=创建者/管理员，member=普通成员',
  `status`     VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '状态：pending=待审核，active=已加入，removed=已移除（软失效，不删除行）',
  `source`     VARCHAR(16) DEFAULT 'invite'           COMMENT '来源：invite=邀请加入，self=主动申请加入',
  `joined_at`  DATETIME    DEFAULT NULL COMMENT '实际加入时间',
  `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family_openid` (`family_id`,`openid`),
  KEY `idx_family` (`family_id`),
  KEY `idx_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭成员表';

CREATE TABLE IF NOT EXISTS `invitation` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '邀请记录ID，自增主键',
  `family_id`        BIGINT       NOT NULL COMMENT '对应家庭ID',
  `inviter_openid`   VARCHAR(64) NOT NULL COMMENT '邀请人 openid',
  `token`            VARCHAR(64) NOT NULL COMMENT '邀请令牌（分享链接/二维码载体），全局唯一',
  `type`             VARCHAR(16) NOT NULL DEFAULT 'share' COMMENT '类型：share=分享链接，qr=二维码',
  `used`             TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否已使用：0=未使用，1=已使用',
  `applicant_openid` VARCHAR(64) DEFAULT NULL COMMENT '申请人 openid（主动申请加入时填写）',
  `reviewed_by`      VARCHAR(64) DEFAULT NULL COMMENT '审核人 openid',
  `reviewed_at`      DATETIME    DEFAULT NULL COMMENT '审核操作时间',
  `expire_at`        DATETIME    NOT NULL COMMENT '过期时间，创建时=now+7天',
  `status`           VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '状态：pending=待处理，approved=已通过，rejected=已拒绝，expired=已过期',
  `created_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token` (`token`),
  KEY `idx_family` (`family_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭邀请表';

CREATE TABLE IF NOT EXISTS `transaction` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '账目ID，自增主键',
  `family_id`      BIGINT       NOT NULL COMMENT '所属家庭ID',
  `openid`         VARCHAR(64) NOT NULL COMMENT '记账人 openid',
  `type`           VARCHAR(16) NOT NULL COMMENT '类型：income=收入，expense=支出',
  `amount`         INT         NOT NULL COMMENT '金额，单位分（整数），恒为正整数',
  `category_id`    BIGINT      DEFAULT NULL COMMENT '分类ID（关联 category.id），可为空',
  `category_name`  VARCHAR(32) DEFAULT NULL COMMENT '分类名称（冗余存储，防止分类改名后历史失真）',
  `category_icon`  VARCHAR(32) DEFAULT NULL COMMENT '分类图标（冗余存储，防止图标变更后历史失真）',
  `category_color` VARCHAR(16) DEFAULT NULL COMMENT '分类颜色（冗余存储，防止颜色变更后历史失真）',
  `date`           DATE        NOT NULL COMMENT '记账日期（业务日期，按 Asia/Shanghai）',
  `note`           VARCHAR(200) DEFAULT NULL COMMENT '备注',
  `images`         JSON        DEFAULT NULL COMMENT '凭证/小票图片地址数组',
  `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_family_date` (`family_id`,`date`),
  KEY `idx_family_openid` (`family_id`,`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账目流水表';

CREATE TABLE IF NOT EXISTS `category` (
  `id`        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分类ID，自增主键',
  `family_id` BIGINT       DEFAULT NULL COMMENT '归属家庭ID，NULL 表示系统预置（global）',
  `name`      VARCHAR(32) NOT NULL COMMENT '分类名称',
  `type`      VARCHAR(16) NOT NULL COMMENT '类型：income=收入，expense=支出',
  `icon`      VARCHAR(32) DEFAULT NULL COMMENT '分类图标标识',
  `color`     VARCHAR(16) DEFAULT NULL COMMENT '分类颜色（十六进制，如 #FF7043）',
  `sort`      INT         DEFAULT 0 COMMENT '排序权重，越小越靠前',
  `is_system` TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否系统预置：0=自定义，1=系统',
  PRIMARY KEY (`id`),
  KEY `idx_family_type` (`family_id`,`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分类表';

CREATE TABLE IF NOT EXISTS `reminder_setting` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '提醒设置ID，自增主键',
  `family_id`        BIGINT       NOT NULL COMMENT '所属家庭ID',
  `openid`           VARCHAR(64) NOT NULL COMMENT '关联用户 openid',
  `enabled`          TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否启用记账提醒：0=关闭，1=开启',
  `time`             TIME        DEFAULT NULL COMMENT '每日提醒时间',
  `tmpl_id`          VARCHAR(64) DEFAULT NULL COMMENT '微信订阅消息模板ID',
  `subscribe_count`  INT         NOT NULL DEFAULT 0 COMMENT '一次性订阅剩余可用次数',
  `subscribe_expire_at` DATETIME DEFAULT NULL COMMENT '订阅授权过期时间',
  `last_sent_at`     DATETIME    DEFAULT NULL COMMENT '上次发送提醒时间',
  `updated_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family_openid` (`family_id`,`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记账提醒设置表';

CREATE TABLE IF NOT EXISTS `budget` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '预算记录ID，自增主键',
  `family_id`    BIGINT       NOT NULL COMMENT '所属家庭ID',
  `amount`       INT         NOT NULL DEFAULT 0 COMMENT '家庭当月预算（单位分），0 表示未设置',
  `updated_by`   VARCHAR(64) DEFAULT NULL COMMENT '最近更新人 openid',
  `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family` (`family_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭预算表';

-- 系统预置分类（family_id=NULL 表示 global），成员自定义分类在运行时写入本家庭副本
INSERT IGNORE INTO `category` (`family_id`,`name`,`type`,`icon`,`color`,`sort`,`is_system`) VALUES
  (NULL,'餐饮','expense','food','#FF7043',1,1),
  (NULL,'交通','expense','bus','#42A5F5',2,1),
  (NULL,'购物','expense','cart','#AB47BC',3,1),
  (NULL,'居家','expense','home','#26A69A',4,1),
  (NULL,'娱乐','expense','game','#FFA726',5,1),
  (NULL,'医疗','expense','med','#EF5350',6,1),
  (NULL,'其他','expense','more','#78909C',7,1),
  (NULL,'工资','income','salary','#66BB6A',1,1),
  (NULL,'奖金','income','gift','#9CCC65',2,1),
  (NULL,'理财','income','chart','#29B6F6',3,1),
  (NULL,'其他','income','more','#78909C',4,1);
