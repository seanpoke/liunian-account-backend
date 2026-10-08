-- 流年小账 启动初始化脚本（classpath，开发库专用）
-- 每次启动先 DROP 再 CREATE，确保表结构与代码一致（专用库 liunian_account，会清空数据，仅用于开发）
-- 生产/正式环境请勿使用本脚本自动执行，改为手动执行 doc/sql/schema.sql

-- 防御护栏：显式切到本项目专用库，避免直连到 MySQL root 默认 schema（如共享实例上的 checkin 等）
-- 而误清空/误写入其他项目的表。若 liunian_account 不存在且未开启自动建库，此处会直接报错（fail-fast）。
USE `liunian_account`;

DROP TABLE IF EXISTS `budget`;
DROP TABLE IF EXISTS `reminder_setting`;
DROP TABLE IF EXISTS `category`;
DROP TABLE IF EXISTS `transaction`;
DROP TABLE IF EXISTS `invitation`;
DROP TABLE IF EXISTS `family_member`;
DROP TABLE IF EXISTS `family`;
DROP TABLE IF EXISTS `user`;

CREATE TABLE `user` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `openid`          VARCHAR(64)  NOT NULL,
  `unionid`         VARCHAR(64)  DEFAULT NULL,
  `nickname`        VARCHAR(64)  DEFAULT NULL,
  `avatar`          VARCHAR(512) DEFAULT NULL,
  `privacy_agreed_at` DATETIME    DEFAULT NULL,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `family` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `name`          VARCHAR(64)  NOT NULL,
  `creator_openid` VARCHAR(64) NOT NULL,
  `currency`      VARCHAR(8)   NOT NULL DEFAULT 'CNY',
  `avatar`        VARCHAR(512) DEFAULT NULL,
  `member_count`  INT UNSIGNED NOT NULL DEFAULT 0,
  `status`        VARCHAR(16)  NOT NULL DEFAULT 'active',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_creator` (`creator_openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `family_member` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `family_id`  BIGINT       NOT NULL,
  `openid`     VARCHAR(64) NOT NULL,
  `nickname`   VARCHAR(64) NOT NULL,
  `avatar`     VARCHAR(512) DEFAULT NULL,
  `role`       VARCHAR(16) NOT NULL DEFAULT 'member',
  `status`     VARCHAR(16) NOT NULL DEFAULT 'pending',
  `source`     VARCHAR(16) DEFAULT 'invite',
  `joined_at`  DATETIME    DEFAULT NULL,
  `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family_openid` (`family_id`,`openid`),
  KEY `idx_family` (`family_id`),
  KEY `idx_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `invitation` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `family_id`        BIGINT       NOT NULL,
  `inviter_openid`   VARCHAR(64) NOT NULL,
  `token`            VARCHAR(64) NOT NULL,
  `type`             VARCHAR(16) NOT NULL DEFAULT 'share',
  `used`             TINYINT(1)  NOT NULL DEFAULT 0,
  `applicant_openid` VARCHAR(64) DEFAULT NULL,
  `reviewed_by`      VARCHAR(64) DEFAULT NULL,
  `reviewed_at`      DATETIME    DEFAULT NULL,
  `expire_at`        DATETIME    NOT NULL,
  `status`           VARCHAR(16) NOT NULL DEFAULT 'pending',
  `created_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token` (`token`),
  KEY `idx_family` (`family_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `transaction` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `family_id`      BIGINT       NOT NULL,
  `openid`         VARCHAR(64) NOT NULL,
  `type`           VARCHAR(16) NOT NULL,
  `amount`         INT         NOT NULL,
  `category_id`    BIGINT      DEFAULT NULL,
  `category_name`  VARCHAR(32) DEFAULT NULL,
  `category_icon`  VARCHAR(32) DEFAULT NULL,             -- 冗余，防分类图标变更失真
  `category_color` VARCHAR(16) DEFAULT NULL,             -- 冗余，防分类颜色变更失真
  `date`           DATE        NOT NULL,
  `note`           VARCHAR(200) DEFAULT NULL,
  `images`         JSON        DEFAULT NULL,
  `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_family_date` (`family_id`,`date`),
  KEY `idx_family_openid` (`family_id`,`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `category` (
  `id`        BIGINT       NOT NULL AUTO_INCREMENT,
  `family_id` BIGINT       DEFAULT NULL,             -- NULL=系统预置(global)，否则归属某家庭
  `name`      VARCHAR(32) NOT NULL,
  `type`      VARCHAR(16) NOT NULL,
  `icon`      VARCHAR(32) DEFAULT NULL,
  `color`     VARCHAR(16) DEFAULT NULL,
  `sort`      INT         DEFAULT 0,
  `is_system` TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_family_type` (`family_id`,`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `reminder_setting` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `family_id`        BIGINT       NOT NULL,
  `openid`           VARCHAR(64) NOT NULL,
  `enabled`          TINYINT(1)  NOT NULL DEFAULT 0,
  `time`             TIME        DEFAULT NULL,
  `tmpl_id`          VARCHAR(64) DEFAULT NULL,
  `subscribe_count`  INT         NOT NULL DEFAULT 0,
  `subscribe_expire_at` DATETIME DEFAULT NULL,
  `last_sent_at`     DATETIME    DEFAULT NULL,
  `updated_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family_openid` (`family_id`,`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `budget` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `family_id`    BIGINT       NOT NULL,
  `amount`       INT         NOT NULL DEFAULT 0,
  `updated_by`   VARCHAR(64) DEFAULT NULL,
  `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family` (`family_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
