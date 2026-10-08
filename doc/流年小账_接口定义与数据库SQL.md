# 流年小账 · 接口定义与数据库 SQL

> 范围：仅含**功能板块、接口定义、SQL 脚本**三类内容；不含具体实现代码与项目包结构（实现层另行交付）。
> 配套：产品交互与流程见《家庭记账小程序设计方案.md》；部署输入见《部署输入与内容核对清单.md》。
> 约定：金额一律以**整数「分」**存储；身份以微信 `openid` 为主键；时间统一服务端 UTC+8。

---

## 一、功能板块（Functional Modules）

| 板块 | 说明 | 关键约束 |
|------|------|----------|
| 账本创建 | 首个用户创建「家庭账本」，自动成为创建者 | 家庭名称自定义；币种默认 CNY |
| 邀请加入 | 创建者生成邀请（分享卡片 / 面对面小程序码），被邀请人提交申请 → 创建者审批 | 全程不出现账目信息；token 一次性 + 7 天有效 + ≤10 人；拒绝后 24h 拉黑防骚扰 |
| 日常记账 | 成员记一笔（收/支）：金额、分类、日期、备注、可选小票图 | 记账人自动归属当前用户；金额以元输入、分存储 |
| 成员管理 | 成员列表、移除、退出、转让创建者、修改昵称/头像 | 仅创建者可邀请/移除/转让；创建者须先转让才能退出 |
| 汇总统计 | 总览（本月收/支/结余 + 迷你趋势）、分类占比、趋势、成员贡献、明细筛选（日期/类型/分类/成员） | 统计走后端聚合，不拉全量 |
| 记账提醒 | 成员开启并选时间，到时未记账则订阅消息提醒 | 需用户主动订阅；一次性订阅，用尽需重授权 |
| 隐私合规 | 首次进入隐私授权弹窗 + 独立隐私政策页 | 未同意前拦截进入；仅收集昵称/头像 |
| 登录与身份 | `wx.login` 取 code → 后端 `code2Session` 换 openid → 签发会话 token | AppSecret 仅服务端持有；openid 作唯一身份 |

---

## 二、接口定义（REST API）

**通用约定**
- Base：`https://api.<你的域名>/api`（开发期可直连 `http://<公网IP>:<端口>/api`）
- 鉴权：除 `POST /login` 外，所有接口须在请求头带 `Authorization: Bearer <token>`；服务端解析 token 得到 `openid`，并校验其为目标 `family` 的 `active` 成员。
- 响应统一结构：`{ "code": 0, "msg": "", "data": ... }`（`code=0` 成功，非 0 为错误码）。
- 错误码示例：`401` 未登录/鉴权失败；`403` 越权（非家庭成员/非创建者）；`409` 重复邀请/重复申请；`410` 邀请已失效。

### 2.1 登录与身份
| 方法 | 路径 | 鉴权 | 请求 | 响应（data） |
|------|------|------|------|--------------|
| POST | `/login` | 否 | `{ "code": "<wx.login 临时code>" }` | `{ "token", "openid", "isMember": bool, "familyId?": "<已有家庭则返回>" }` |
| GET | `/user/privacy` | 是 | — | `{ "agreed": bool, "agreedAt": "<时间>" }` |
| POST | `/user/privacy` | 是 | `{ "agreed": true }` | `{ "agreed": true }`（隐私授权落库，设计方案 §12） |

### 2.2 账本
| 方法 | 路径 | 鉴权 | 请求 | 响应（data） |
|------|------|------|------|--------------|
| POST | `/family` | 是 | `{ "name": "流年小账" }` | `{ "familyId" }`（创建者自动 owner、自增一条 self 成员） |
| GET | `/family/:familyId` | 是 | — | 家庭信息 + `memberCount` |
| PATCH | `/family/:familyId` | 创建者 | `{ "name?" , "currency?", "avatar?" }` | 更新后家庭信息 |

### 2.3 邀请（含申请 + 审批）
| 方法 | 路径 | 鉴权 | 请求 | 响应（data） |
|------|------|------|------|--------------|
| POST | `/invitation` | 创建者 | `{ "type": "share" \| "qr", "familyId" }` | `{ "token", "expireAt", "qrImageUrl?" }`（qr 类型返回小程序码地址） |
| GET | `/invitation/:token` | 否 | — | `{ "familyName", "inviterNickname", "inviterAvatar", "status" }`（**不含任何账目信息**） |
| POST | `/invitation/:token/apply` | 否（需先 login 拿 openid） | `{ "nickname", "avatar?" }` | `{ "status": "pending" }`（写入 `family_member(pending)` + `invitation.used=true`） |
| POST | `/invitation/revoke` | 创建者 | `{ "token" }` | 作废该邀请 |
| GET | `/family/:familyId/pending` | 创建者 | — | `[ { "memberId", "nickname", "avatar", "applicantOpenid", "applyAt", "source" } ]` |
| POST | `/family/:familyId/member/:memberId/approve` | 创建者 | — | 该成员 `status=active`、写入 `joinedAt` |
| POST | `/family/:familyId/member/:memberId/reject` | 创建者 | — | 该成员 `status=removed`、邀请 `rejected` |

### 2.4 记账
| 方法 | 路径 | 鉴权 | 请求 | 响应（data） |
|------|------|------|------|--------------|
| POST | `/transaction` | 是 | `{ "type":"income\|expense", "amount":<分>, "categoryId", "categoryName", "date":"YYYY-MM-DD", "note?", "images?":[] }` | `{ "id" }` |
| PUT | `/transaction/:id` | 是（本人或创建者） | 同上可改字段 | 更新结果 |
| DELETE | `/transaction/:id` | 是（本人或创建者） | — | 删除结果 |
| GET | `/transaction/:id` | 是（本人或创建者） | — | 单笔详情 `{ "id","family_id","openid","type","amount","category_id","category_name","category_icon","category_color","date","note","images","created_at","updated_at","nickname" }` |
| GET | `/transactions` | 是 | `?start&end&member&type&category&page&size` | `{ "list":[...], "total" }`（按日期倒序；v1 单家庭，familyId 由当前用户推导） |
| GET | `/transactions/by-day` | 是 | `?start&end` | `{ "days":[{ "date","income","expense","balance","list":[...] }], "total" }`（按日分组 + 每日小计，首页最近 N 天流水用） |

### 2.5 统计
| 方法 | 路径 | 鉴权 | 请求 | 响应（data） |
|------|------|------|------|--------------|
| GET | `/stats` | 是 | `?period=month\|year\|custom&month?&year?&start?&end?&member?` | `{ "income", "expense", "balance", "byCategory":[{name,amount}], "byDay":[{date,income,expense}], "byMember":[{openid,nickname,amount}] }`（v1 单家庭，familyId 由当前用户推导；byMember 含昵称，省前端二次加工） |

### 2.6 成员
| 方法 | 路径 | 鉴权 | 请求 | 响应（data） |
|------|------|------|------|--------------|
| GET | `/family/:familyId/members` | 是 | — | `[ { "memberId","openid","nickname","avatar","role","status","joinedAt","monthCount" } ]` |
| DELETE | `/family/:familyId/member/:memberId` | 创建者 | — | 移除（`status=removed`，保留历史记录） |
| POST | `/family/:familyId/transfer` | 创建者 | `{ "toOpenid" }` | 转让创建者 |
| POST | `/family/:familyId/quit` | 成员 | — | 本人退出（`status=removed`） |
| PATCH | `/family/:familyId/member/me` | 是 | `{ "nickname?", "avatar?" }` | 修改自己在家庭内的展示信息 |

### 2.7 提醒
| 方法 | 路径 | 鉴权 | 请求 | 响应（data） |
|------|------|------|------|--------------|
| GET | `/reminder` | 是 | `?familyId` | `{ "enabled", "time", "tmplId" }` |
| PUT | `/reminder` | 是 | `{ "enabled", "time", "tmplId?" }` | 更新提醒设置（保存前需已获取订阅授权） |

> 服务端定时任务（非接口）：每日扫描 `reminder_setting(enabled=1)` 且到点的成员，当日该家庭已有交易则跳过，否则推送订阅消息。

### 2.8 小程序码
| 方法 | 路径 | 鉴权 | 请求 | 响应（data） |
|------|------|------|------|--------------|
| GET | `/wxacode` | 创建者 | `?familyId` | `{ "qrImageUrl" }`（后端调 `getwxacodeunlimit`，`scene=inviteCode`） |

### 2.9 分类管理
| 方法 | 路径 | 鉴权 | 请求 | 响应（data） |
|------|------|------|------|--------------|
| GET | `/categories` | 是 | `?familyId&type=income\|expense` | `[ { "id","family_id","name","type","icon","color","sort","is_system" } ]`（系统预置 `global` + 本家庭自定义合并，按 `sort` 升序） |
| POST | `/category` | 是 | `{ "name", "type":"income\|expense", "icon?", "color?" }` | 新建的自定义分类 `{ "id","name","type","icon","color","is_system":0 }` |

> 说明：`category` 表记录系统预置（`family_id='global'`）与各家庭自定义副本；成员可在预置之外新增自定义分类，新增仅写入本家庭（`family_id` = 当前家庭），不可改删系统预置（`is_system=1`）。记账时 `category_id` 既可取预置也可取自家庭成员自定义。

### 2.10 家庭预算
| 方法 | 路径 | 鉴权 | 请求 | 响应（data） |
|------|------|------|------|--------------|
| GET | `/budget` | 是 | `?familyId` | `{ "amount" }`（家庭当月预算，单位分；`0` 表示未设置） |
| PUT | `/budget` | 创建者 | `{ "amount":<分> }` | 更新后 `{ "amount" }` |

> 说明：预算为家庭级月度上限，仅创建者可设置；成员端用于「本月已支出 / 预算」对比与超支提示（超支以红色标记）。`0` 表示未启用预算，前端不展示预算进度。

---

## 三、SQL 脚本（MySQL 8）

```sql
-- 流年小账 数据库脚本 (MySQL 8) —— 金额单位：分(整数)
-- 说明：family_member.member_count 由应用层/触发器与 family_member 计数保持一致。

CREATE TABLE `user` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `openid`          VARCHAR(64)  NOT NULL,
  `unionid`         VARCHAR(64)  DEFAULT NULL,
  `nickname`        VARCHAR(64)  DEFAULT NULL,
  `avatar`          VARCHAR(512) DEFAULT NULL,
  `privacy_agreed_at` DATETIME    DEFAULT NULL,    -- 隐私授权落库时间（设计方案 §12）
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `family` (
  `id`            VARCHAR(32)  NOT NULL,
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
  `id`         VARCHAR(32) NOT NULL,
  `family_id`  VARCHAR(32) NOT NULL,
  `openid`     VARCHAR(64) NOT NULL,
  `nickname`   VARCHAR(64) NOT NULL,
  `avatar`     VARCHAR(512) DEFAULT NULL,
  `role`       VARCHAR(16) NOT NULL DEFAULT 'member',  -- owner / member
  `status`     VARCHAR(16) NOT NULL DEFAULT 'pending', -- pending / active / removed
  `source`     VARCHAR(16) DEFAULT 'invite',           -- invite / self
  `joined_at`  DATETIME    DEFAULT NULL,
  `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family_openid` (`family_id`,`openid`),
  KEY `idx_family` (`family_id`),
  KEY `idx_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `invitation` (
  `id`               VARCHAR(32) NOT NULL,
  `family_id`        VARCHAR(32) NOT NULL,
  `inviter_openid`   VARCHAR(64) NOT NULL,
  `token`            VARCHAR(64) NOT NULL,
  `type`             VARCHAR(16) NOT NULL DEFAULT 'share', -- share / qr
  `used`             TINYINT(1)  NOT NULL DEFAULT 0,
  `applicant_openid` VARCHAR(64) DEFAULT NULL,
  `reviewed_by`      VARCHAR(64) DEFAULT NULL,
  `reviewed_at`      DATETIME    DEFAULT NULL,
  `expire_at`        DATETIME    NOT NULL,                -- 创建时 = now + 7天
  `status`           VARCHAR(16) NOT NULL DEFAULT 'pending', -- pending / approved / rejected / expired
  `created_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token` (`token`),
  KEY `idx_family` (`family_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `transaction` (
  `id`             VARCHAR(32) NOT NULL,
  `family_id`      VARCHAR(32) NOT NULL,
  `openid`         VARCHAR(64) NOT NULL,
  `type`           VARCHAR(16) NOT NULL,                 -- income / expense
  `amount`         INT         NOT NULL,                 -- 分，正整数
  `category_id`    VARCHAR(32) DEFAULT NULL,
  `category_name`  VARCHAR(32) DEFAULT NULL,              -- 冗余，防分类改名失真
  `date`           DATE        NOT NULL,
  `note`           VARCHAR(200) DEFAULT NULL,
  `images`         JSON        DEFAULT NULL,             -- 小票/凭证图地址数组
  `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_family_date` (`family_id`,`date`),
  KEY `idx_family_openid` (`family_id`,`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `category` (
  `id`        VARCHAR(32) NOT NULL,
  `family_id` VARCHAR(32) NOT NULL DEFAULT 'global',     -- global=系统预置
  `name`      VARCHAR(32) NOT NULL,
  `type`      VARCHAR(16) NOT NULL,                      -- income / expense
  `icon`      VARCHAR(32) DEFAULT NULL,
  `color`     VARCHAR(16) DEFAULT NULL,             -- 分类颜色（接口返回 category_color）
  `sort`      INT         DEFAULT 0,
  `is_system` TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_family_type` (`family_id`,`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `reminder_setting` (
  `id`               VARCHAR(32) NOT NULL,
  `family_id`        VARCHAR(32) NOT NULL,
  `openid`           VARCHAR(64) NOT NULL,
  `enabled`          TINYINT(1)  NOT NULL DEFAULT 0,
  `time`             TIME        DEFAULT NULL,
  `tmpl_id`          VARCHAR(64) DEFAULT NULL,
  `subscribe_count`  INT         NOT NULL DEFAULT 0, -- 一次性订阅剩余次数（用尽后推送会失败）
  `subscribe_expire_at` DATETIME DEFAULT NULL,       -- 订阅授权过期时间
  `last_sent_at`     DATETIME    DEFAULT NULL,
  `updated_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family_openid` (`family_id`,`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `budget` (
  `id`           VARCHAR(32) NOT NULL,
  `family_id`    VARCHAR(32) NOT NULL,
  `amount`       INT         NOT NULL DEFAULT 0,   -- 家庭当月预算（分），0=未设置
  `updated_by`   VARCHAR(64) DEFAULT NULL,
  `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family` (`family_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**索引/一致性说明**
- `family_member(family_id, openid)` 唯一：防止同一 openid 重复加入同一家庭。
- `transaction(family_id, date)`：统计按日期聚合主索引；`(family_id, openid)` 用于按成员筛选。
- `invitation.token` 唯一：邀请令牌；`used` + `expire_at` + `status` 共同实现「一次性 / 有效期 / 失效」校验。
- `family.member_count` 为冗余计数，新建/审批/移除成员时同步增减（应用事务保证一致）。
- `category.family_id='global'` 表示系统预置分类，各家庭首次使用可继承后写入本家庭副本。
