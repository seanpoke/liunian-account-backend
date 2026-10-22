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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭成员表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家庭邀请表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加入申请审批表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账目流水表';

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
  `deleted`   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0=有效，1=已删除（不物理删行，历史账单靠 transaction 的分类快照展示）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_family_type` (`family_id`,`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分类表';

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
-- 清单取自参考产品截图：支出 39 项、收入 6 项。
-- 说明：截图中支出里的「储蓄」已排除——本产品有独立储蓄模块，存钱不是支出（见 doc/储蓄功能设计.md）；
--       截图最后一项「设置」是产品入口，不是分类。列表外的分类由家庭自定义（每类型上限 20 个）。
INSERT IGNORE INTO `category` (`family_id`,`name`,`type`,`code`,`icon`,`color`,`sort`,`is_system`) VALUES
  (NULL,'餐饮','expense','food',            '🍜','#FF7043', 1,1),
  (NULL,'住房','expense','housing',         '🏠','#8D6E63', 2,1),
  (NULL,'医疗','expense','medical',         '💊','#EF5350', 3,1),
  (NULL,'汽车','expense','car',             '🚗','#42A5F5', 4,1),
  (NULL,'电动车','expense','e_bike',        '🛵','#7E57C2', 5,1),
  (NULL,'饮品','expense','drink',           '🥤','#FFA726', 6,1),
  (NULL,'花呗','expense','huabei',          '💳','#5C6BC0', 7,1),
  (NULL,'购物','expense','shopping',        '🛒','#AB47BC', 8,1),
  (NULL,'装修','expense','decoration',      '🔨','#A1887F', 9,1),
  (NULL,'日用','expense','daily',           '🧴','#26A69A',10,1),
  (NULL,'的士','expense','taxi',            '🚕','#4FC3F7',11,1),
  (NULL,'交通','expense','transport',       '🚌','#29B6F6',12,1),
  (NULL,'蔬菜','expense','vegetable',       '🥬','#66BB6A',13,1),
  (NULL,'水果','expense','fruit',           '🍎','#EF6C00',14,1),
  (NULL,'零食','expense','snack',           '🍿','#FFCA28',15,1),
  (NULL,'运动','expense','sport',           '⚽','#43A047',16,1),
  (NULL,'娱乐','expense','fun',             '🎮','#FB8C00',17,1),
  (NULL,'通讯','expense','telecom',         '📱','#00ACC1',18,1),
  (NULL,'服饰','expense','clothes',         '👕','#EC407A',19,1),
  (NULL,'美容','expense','beauty',          '💅','#F06292',20,1),
  (NULL,'居家','expense','home',            '🛋️','#00897B',21,1),
  (NULL,'孩子','expense','child',           '🧒','#FFB74D',22,1),
  (NULL,'长辈','expense','elder',           '👴','#BCAAA4',23,1),
  (NULL,'社交','expense','social',          '🍻','#FF8A65',24,1),
  (NULL,'旅行','expense','travel',          '✈️','#039BE5',25,1),
  (NULL,'烟酒','expense','tobacco_alcohol', '🍷','#8E24AA',26,1),
  (NULL,'数码','expense','digital',         '💻','#546E7A',27,1),
  (NULL,'书籍','expense','book',            '📚','#5E35B1',28,1),
  (NULL,'学习','expense','study',           '🎓','#3949AB',29,1),
  (NULL,'宠物','expense','pet',             '🐶','#FFB300',30,1),
  (NULL,'礼金','expense','gift_out',        '🧧','#E53935',31,1),
  (NULL,'礼物','expense','gift',            '🎁','#D81B60',32,1),
  (NULL,'办公','expense','office',          '💼','#455A64',33,1),
  (NULL,'维修','expense','repair',          '🔧','#78909C',34,1),
  (NULL,'捐赠','expense','donate',          '❤️','#E91E63',35,1),
  (NULL,'彩票','expense','lottery',         '🎫','#F4511E',36,1),
  (NULL,'亲友','expense','relatives',       '👨‍👩‍👧','#FF8A80',37,1),
  (NULL,'快递','expense','express',         '📦','#6D4C41',38,1),
  (NULL,'投资','expense','invest',          '📈','#009688',39,1),
  (NULL,'工资','income', 'salary',          '💰','#66BB6A', 1,1),
  (NULL,'兼职','income', 'part_time',       '💼','#9CCC65', 2,1),
  (NULL,'理财','income', 'finance',         '📈','#29B6F6', 3,1),
  (NULL,'礼金','income', 'gift_in',         '🧧','#FFA726', 4,1),
  (NULL,'其它','income', 'other_income',    '📦','#78909C', 5,1),
  (NULL,'资产','income', 'asset',           '🏦','#42A5F5', 6,1);

-- ============================================================================
-- 储蓄模块（2026-10-22）
--   两本账：transaction 是收支账本；saving_record 是储蓄账本，两者互不校验。
--   余额永远派生（Σ存入+转入 − Σ取出−转出），不落 balance 字段。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `saving_account` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '储蓄账户ID，自增主键',
  `family_id`  BIGINT       DEFAULT NULL COMMENT '归属家庭ID；NULL = 系统预置（全局 5 条，与 category.family_id 同套路；完全只读，不可改名/停用/删除）',
  `name`       VARCHAR(32)  NOT NULL COMMENT '账户名，如「活期」「国债」（重名校验在应用层，不加唯一索引：逻辑删除行会占键、且 NULL 家庭不生效）',
  `icon`       VARCHAR(32)  DEFAULT NULL COMMENT '账户图标（emoji，如 💵）',
  `color`      VARCHAR(16)  DEFAULT NULL COMMENT '配色（十六进制，如 #07C160）',
  `sort`       INT          NOT NULL DEFAULT 0 COMMENT '排序权重，越小越靠前',
  `is_system`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0=家庭自定义，1=系统预置（全局只读，不可改名/停用/删除）',
  `status`     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用（仅自定义账户可停用；停用后不进新增存取/支付/转账选择列表，余额仍计入储蓄余额总额）',
  `deleted`    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0=有效，1=已删除（仅余额为 0 的自定义账户可删，历史流水仍靠 account_id 展示账户名）',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_family` (`family_id`),
  KEY `idx_family_sort` (`family_id`,`deleted`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='储蓄账户表';

CREATE TABLE IF NOT EXISTS `saving_record` (
  `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '储蓄流水ID，自增主键',
  `family_id`             BIGINT       NOT NULL COMMENT '所属家庭ID',
  `account_id`            BIGINT       NOT NULL COMMENT '所属储蓄账户ID（必填；新增存取未选择时默认「活期」）',
  `direction`             VARCHAR(16)  NOT NULL COMMENT '方向：in=存入，out=取出，transfer_in=转入，transfer_out=转出',
  `source`                VARCHAR(16)  NOT NULL DEFAULT 'normal' COMMENT '来源：normal=真实资金进出，adjust=「更新余额」生成的校准流水（标「余额调整」）',
  `amount`                INT          NOT NULL COMMENT '金额，单位分（整数），恒为正，方向由 direction 表达',
  `date`                  DATE         NOT NULL COMMENT '业务日期（按 Asia/Shanghai；「本月存入/取出」按此字段统计，不用 created_at）',
  `note`                  VARCHAR(200) DEFAULT NULL COMMENT '备注（用途或来源，如「买入国债」「春节旅行」）',
  `openid`                VARCHAR(64)  NOT NULL COMMENT '操作人 openid（owner 代改他人记录时不改变）',
  `operator_name`         VARCHAR(32)  DEFAULT NULL COMMENT '操作人昵称快照（family_member 退出即物理删行，历史流水靠本字段展示「谁记的」）',
  `link_id`               VARCHAR(40)  DEFAULT NULL COMMENT '同表组合记录关联键：账户转账的两条记录共享同一值',
  `linked_transaction_id` BIGINT       DEFAULT NULL COMMENT '跨表组合关联的账单 id（仅「从储蓄支付」「到账即存」两处使用）',
  `created_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_family_date` (`family_id`,`date`),
  KEY `idx_family_account` (`family_id`,`account_id`),
  KEY `idx_link` (`link_id`),
  KEY `idx_linked_tx` (`linked_transaction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='储蓄流水表';

-- 系统预置储蓄账户（全局 5 条）：family_id=NULL、is_system=1。
--   活期（默认，sort=1）/ 定期 / 国债 / 基金 / 保险。
--   完全只读：不可改名、不可停用、不可删除；家庭只能新增自定义账户（≤20）。
INSERT INTO `saving_account` (`family_id`,`name`,`icon`,`color`,`sort`,`is_system`,`status`)
SELECT CAST(NULL AS SIGNED),'活期','💵','#07C160',1,1,1 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `saving_account` WHERE `family_id` IS NULL AND `name` = '活期');
INSERT INTO `saving_account` (`family_id`,`name`,`icon`,`color`,`sort`,`is_system`,`status`)
SELECT CAST(NULL AS SIGNED),'定期','🏦','#42A5F5',2,1,1 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `saving_account` WHERE `family_id` IS NULL AND `name` = '定期');
INSERT INTO `saving_account` (`family_id`,`name`,`icon`,`color`,`sort`,`is_system`,`status`)
SELECT CAST(NULL AS SIGNED),'国债','📜','#26A69A',3,1,1 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `saving_account` WHERE `family_id` IS NULL AND `name` = '国债');
INSERT INTO `saving_account` (`family_id`,`name`,`icon`,`color`,`sort`,`is_system`,`status`)
SELECT CAST(NULL AS SIGNED),'基金','📈','#AB47BC',4,1,1 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `saving_account` WHERE `family_id` IS NULL AND `name` = '基金');
INSERT INTO `saving_account` (`family_id`,`name`,`icon`,`color`,`sort`,`is_system`,`status`)
SELECT CAST(NULL AS SIGNED),'保险','🛡️','#FFA726',5,1,1 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `saving_account` WHERE `family_id` IS NULL AND `name` = '保险');
