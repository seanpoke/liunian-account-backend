-- =============================================================================
-- 流年小账 · 2026-10-19 结构调整迁移脚本（存量库专用）
-- 全新库请直接执行 ci/schema.sql，不要执行本脚本。
--
-- 变更内容：
--   1) transaction：新增 status/voided_* 作废状态，默认有效
--   2) category：code 增加全局唯一约束
--   3) family：移除冗余 member_count
--   4) family_member：移除 status/source，只保留真实成员
--   5) invitation 拆分：invitation(邀请) + join_request(申请/审批，1:N)
--
-- 执行前务必备份数据库；本脚本按顺序执行，勿跳步。
-- =============================================================================

-- ⚠️ 仅对【开发库 liunian_account_dev】执行；liunian_account 是已有真实用户的生产库，禁止执行。
USE `liunian_account_dev`;

-- -----------------------------------------------------------------------------
-- 0. 执行前检查
-- -----------------------------------------------------------------------------
-- 0.1 category.code 重复检查：若下方查询有结果，必须先人工合并/清空重复项，
--     否则第 2 步添加唯一索引会失败。
SELECT `code`, COUNT(*) AS dup_count
  FROM `category`
 WHERE `code` IS NOT NULL
 GROUP BY `code`
HAVING COUNT(*) > 1;

-- 0.2 备份旧邀请表（含 used/applicant_openid/status 等即将删除的字段）
CREATE TABLE IF NOT EXISTS `invitation_bak_20261019` AS SELECT * FROM `invitation`;

-- -----------------------------------------------------------------------------
-- 1. 新表：join_request（申请/审批）
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- 2. 旧数据迁移：invitation 中有申请人的行 → join_request
--    必须在删除 invitation 旧字段、删除 family_member.source 之前执行。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `join_request`
  (`family_id`,`invitation_id`,`applicant_openid`,`nickname`,`avatar`,`channel`,
   `status`,`reviewed_by`,`reviewed_at`,`created_at`,`updated_at`)
SELECT i.`family_id`,
       i.`id`,
       i.`applicant_openid`,
       COALESCE(NULLIF(u.`nickname`, ''), NULLIF(m.`nickname`, ''), '新成员'),
       u.`avatar`,
       COALESCE(NULLIF(m.`source`, ''), 'invite'),
       CASE i.`status`
            WHEN 'approved' THEN 'approved'
            WHEN 'rejected' THEN 'rejected'
            WHEN 'expired'  THEN 'expired'
            ELSE 'pending'
       END,
       i.`reviewed_by`,
       i.`reviewed_at`,
       i.`created_at`,
       COALESCE(i.`reviewed_at`, i.`created_at`)
  FROM `invitation` i
  LEFT JOIN `user` u ON u.`openid` = i.`applicant_openid`
  LEFT JOIN `family_member` m
         ON m.`family_id` = i.`family_id` AND m.`openid` = i.`applicant_openid`
 WHERE i.`applicant_openid` IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 3. invitation 瘦身：先补新列 → 用旧 used 回填 revoked_at → 再删旧列
-- -----------------------------------------------------------------------------
ALTER TABLE `invitation`
  ADD COLUMN `revoked_at` DATETIME DEFAULT NULL COMMENT '撤销时间，NULL=未撤销；替代原 used 字段' AFTER `expire_at`,
  ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER `created_at`;

-- 已使用/已撤销的旧邀请视为失效：置 revoked_at，避免拆分后仍然可用
UPDATE `invitation`
   SET `revoked_at` = COALESCE(`reviewed_at`, `created_at`)
 WHERE `used` = 1;

ALTER TABLE `invitation`
  DROP COLUMN `used`,
  DROP COLUMN `applicant_openid`,
  DROP COLUMN `reviewed_by`,
  DROP COLUMN `reviewed_at`,
  DROP COLUMN `status`,
  MODIFY COLUMN `family_id`      BIGINT      NOT NULL COMMENT '对应家庭ID',
  MODIFY COLUMN `inviter_openid` VARCHAR(64) NOT NULL COMMENT '邀请人 openid',
  MODIFY COLUMN `token`          VARCHAR(64) NOT NULL COMMENT '邀请令牌（分享链接/二维码载体），全局唯一，未过期即可复用',
  MODIFY COLUMN `type`           VARCHAR(16) NOT NULL DEFAULT 'share' COMMENT '类型：share=分享链接，qr=小程序码',
  MODIFY COLUMN `expire_at`      DATETIME    NOT NULL COMMENT '过期时间，创建时=now+invite.expire-hours（默认24小时）',
  MODIFY COLUMN `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  ADD KEY `idx_family_type_expire` (`family_id`,`type`,`expire_at`);

-- 可选：把旧的 7 天有效期统一压到 24 小时（不想缩短存量邀请可注释掉）
-- UPDATE `invitation` SET `expire_at` = DATE_ADD(`created_at`, INTERVAL 24 HOUR)
--  WHERE `expire_at` > DATE_ADD(`created_at`, INTERVAL 24 HOUR);

-- -----------------------------------------------------------------------------
-- 4. transaction：新增作废状态（默认有效）
-- -----------------------------------------------------------------------------
ALTER TABLE `transaction`
  ADD COLUMN `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '账目状态：1=有效（默认），0=已作废（成员退出/被移除时置0，不参与查询与统计）' AFTER `images`,
  ADD COLUMN `voided_at`   DATETIME    DEFAULT NULL COMMENT '作废时间，status=0 时写入' AFTER `status`,
  ADD COLUMN `void_reason` VARCHAR(32) DEFAULT NULL COMMENT '作废原因：member_quit=成员退出，member_removed=成员被移除' AFTER `voided_at`,
  ADD COLUMN `voided_by`   VARCHAR(64) DEFAULT NULL COMMENT '执行作废的人 openid（本人退出=本人，被移除=创建者）' AFTER `void_reason`,
  MODIFY COLUMN `images` JSON DEFAULT NULL COMMENT '凭证/小票图片地址数组（相对路径 /api/files/...）',
  DROP KEY `idx_family_date`,
  ADD KEY `idx_family_status_date` (`family_id`,`status`,`date`);

-- -----------------------------------------------------------------------------
-- 5. category：code 全局唯一（自定义分类留空即可，NULL 互不冲突）
-- -----------------------------------------------------------------------------
UPDATE `category` SET `code` = NULL WHERE `code` = '';
ALTER TABLE `category`
  MODIFY COLUMN `code`  VARCHAR(32) DEFAULT NULL COMMENT '分类语义编码，全局唯一，如 food；家庭自定义分类建议不填（NULL 互不冲突）',
  MODIFY COLUMN `color` VARCHAR(16) DEFAULT NULL COMMENT '分类颜色（十六进制，如 #FF7043），用于账单列表着色并快照进 transaction.category_color';
ALTER TABLE `category` ADD UNIQUE KEY `uk_code` (`code`);

-- -----------------------------------------------------------------------------
-- 6. family：移除冗余成员数（改为实时统计 family_member）
-- -----------------------------------------------------------------------------
ALTER TABLE `family`
  MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT '状态：active=正常（预留 archived 等扩展值）',
  DROP COLUMN `member_count`;

-- 表注释同步说明（可选）
ALTER TABLE `family` COMMENT = '家庭表（成员数由 family_member 实时统计，不再冗余存储）';

-- -----------------------------------------------------------------------------
-- 7. family_member：只保留真实成员
--    注意：必须在第 2 步数据迁移之后执行（迁移用到 source / pending 行）
-- -----------------------------------------------------------------------------
DELETE FROM `family_member` WHERE `status` IS NULL OR `status` <> 'active';
UPDATE `family_member` SET `joined_at` = NOW() WHERE `joined_at` IS NULL;

ALTER TABLE `family_member`
  DROP COLUMN `status`,
  DROP COLUMN `source`,
  MODIFY COLUMN `nickname`  VARCHAR(64)  NOT NULL COMMENT '成员在家庭内的显示昵称（加入时取自 user/申请单，可单独修改）',
  MODIFY COLUMN `avatar`    VARCHAR(512) DEFAULT NULL COMMENT '成员头像 URL（加入时取自 user/申请单）',
  MODIFY COLUMN `role`      VARCHAR(16)  NOT NULL DEFAULT 'member' COMMENT '角色：owner=创建者/管理员，member=普通成员',
  MODIFY COLUMN `joined_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '实际加入时间';

ALTER TABLE `family_member` COMMENT = '家庭成员表（仅保留真实成员；退出/被移除即物理删除，申请见 join_request）';

-- -----------------------------------------------------------------------------
-- 8. 迁移结果自检
-- -----------------------------------------------------------------------------
-- 8.1 每个家庭的实际成员数
SELECT `family_id`, COUNT(*) AS member_count FROM `family_member` GROUP BY `family_id`;

-- 8.2 邀请与申请数量
SELECT (SELECT COUNT(*) FROM `invitation`) AS invitation_count,
       (SELECT COUNT(*) FROM `join_request`) AS join_request_count;

-- 8.3 结构确认
SHOW CREATE TABLE `invitation`;
SHOW CREATE TABLE `join_request`;
SHOW CREATE TABLE `family_member`;
SHOW CREATE TABLE `transaction`;
