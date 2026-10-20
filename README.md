# 流年小账 · 家庭共享记账微信小程序后端

Spring Boot 3.3 (JDK 17) + MyBatis-Plus + MySQL 8 + Redis。提供 REST JSON 接口，Base Path `/api`。

## 环境要求
- JDK 17（本机：`E:\JDK\jdk-17.0.19`）
- Maven 3.9.x（本机：`D:\Program Files\maven\bin\mvn.cmd`）
- MySQL 8：**开发库 `liunian_account_dev`**；`liunian_account` 是已有真实用户的生产库
- Redis（远端已部署，连接信息见 `ci/.env`，开发建议用独立 `REDIS_DB`）

## 环境隔离（重要）
- **开发/测试/冒烟一律连接 `liunian_account_dev`**，禁止连接生产库 `liunian_account`。
- 应用启动时 `DatabaseGuard` 会执行 `SELECT DATABASE()` 校验实际库名：与 `app.db.expected` 不一致直接拒绝启动；若实际库是生产库，还必须显式 `DB_ALLOW_PROD=true` 才允许启动。
- `script/start.ps1` 与 `script/smoke_family.ps1` 在启动前会校验 `MYSQL_DB`，不是 `liunian_account_dev` 直接退出。
- `ci/schema.sql` 与迁移脚本顶部均以 `USE liunian_account_dev;` 作为防护，避免误落生产库或 root 默认 schema。
- 生产部署需显式设置：`MYSQL_DB=liunian_account`、`DB_ALLOW_PROD=true`。

## 快速开始
0. 创建开发库：`CREATE DATABASE IF NOT EXISTS liunian_account_dev DEFAULT CHARSET utf8mb4;`
1. 准备数据库：全新库执行 `ci/schema.sql`（含建表 + 系统预置分类种子）；存量开发库升级执行 `ci/migration/20261019_refactor.sql`（先备份）。
2. 配置环境变量：复制 `ci/.env.example` 为 `ci/.env`，按实际填写 MySQL / Redis / 微信凭据。
3. 启动（脚本会自动把 `.env` 注入环境变量、强制 Asia/Shanghai 时区、校验开发库）：
  ```powershell
  powershell -ExecutionPolicy Bypass -File script/start.ps1
  ```
   服务监听 `http://127.0.0.1:8445/api`，健康检查 `GET /api/health`。

> 也可在 IDE 中直接 Run `AccountApplication`：程序启动时会自动读取项目根目录的 `ci/.env` 并注入环境变量（最低优先级，不覆盖系统环境变量），只要工作目录是项目根目录即可，无需再手动配 Run Configuration。若 `ci/.env` 不在默认位置，可用 `-Denv.file=路径` 指定。

## 本地联调（mock 模式）
`ci/.env` 中 `WX_MOCK=true` 时，`/login` 的 `code` 写成 `mock:<openid>` 即可直接换为该 openid 的 token，
无需真实微信 appid/secret 即可跑通全部业务接口（含创建家庭、邀请、审批、记账、统计）。
生产环境将 `WX_MOCK` 置为 `false` 即走真实 `code2Session`。

## 鉴权
除 `POST /login`、`GET /invitation/{token}`（邀请落地页预览）、`GET /health`、`GET /files/**`（图片静态资源）外，
所有接口请求头需带 `Authorization: Bearer <token>`。token 由服务端签发并写入 Redis（可主动失效）。

## 目录结构
```
src/main/java/com/liunian/account
├── common/    R(统一响应) / ErrorCode / BizException / TimeSupport / GlobalExceptionHandler / JacksonListTypeHandler
├── config/    WebMvcConfig(拦截器+静态资源) / TimeConfig(时区) / DatabaseGuard(库名校验) / WxProperties / MybatisPlusConfig
├── security/  JwtUtil / UserContext / AuthInterceptor
├── wx/        WxService / WxAccessTokenManager(access_token 缓存)
├── service/   Auth / User / Family / FamilyMember / Invitation / JoinRequest / Transaction / Stats / Category / FileStorage
├── mapper/ + resources/mapper/*.xml   统计聚合 SQL
├── entity/ dto/ vo/ util/
├── controller/  Auth User Family Member Invitation JoinRequest Transaction Stats Category Reminder Budget Privacy Wxacode File Health
└── job/       ReminderJob(定时提醒 + Redis 分布式锁)
```

## 已实现接口
| 组 | 接口 |
|----|------|
| 登录 | `POST /login`、`GET /user/privacy`、`POST /user/privacy` |
| 用户资料 | `GET /user/me`、`PATCH /user/me` |
| 账本 | `POST /family`、`GET /family/{id}`、`PATCH /family/{id}`、`GET /families`、`POST /family/{id}/dissolve` |
| 邀请审批 | `POST /invitation`、`GET /invitation/{token}`、`POST /invitation/{token}/apply`、`POST /invitation/revoke`、`GET /family/{id}/join-requests`、`POST .../join-request/{rid}/approve`、`POST .../join-request/{rid}/reject` |
| 成员 | `GET /family/{id}/members`、`DELETE .../member/{mid}`、`POST .../transfer`、`POST .../quit`、`PATCH .../member/me` |
| 记账 | `POST /transaction`、`PUT/DELETE /transaction/{id}`、`GET /transaction/{id}`、`GET /transactions`、`GET /transactions/by-day` |
| 上传 | `POST /upload`（图片，静态访问 `/api/files/**`） |
| 统计 | `GET /stats` |
| 分类 | `GET /categories`、`POST /category`、`DELETE /category/{id}` |
| 预算 | `GET /budget`、`PUT /budget` |
| 提醒 | `GET /reminder`、`PUT /reminder` |
| 小程序码 | `GET /wxacode` |

## 文档
- `doc/接口文档.md`：全部 REST 接口契约（请求/响应字段、错误码、业务规则），字段以代码实现为准。
- `doc/前端改造说明.md`：给小程序前端 AI 的改造清单（破坏性变更、页面级改造、易踩坑点、自测清单）。
- `doc/家庭记账小程序设计方案.md`：产品与数据模型设计。

## 关键约定
- 金额一律整数「分」；时区统一 `app.time-zone`（默认 Asia/Shanghai）：JVM 默认时区、`TimeSupport`、`@Scheduled`、JDBC 会话时区四处一致，业务代码不使用数据库 `NOW()`。
- 多家庭：用户可属于多个家庭；`family_member` 唯一键 `(family_id, openid)`，表内只保留真实成员（无 status/source），退出/被移除即物理删除该行，并作废其在该家庭的流水。
- 成员数不冗余存储：`family.memberCount` 由 `family_member` 实时统计。
- 创建者可编辑/删除他人记录（按接口定义）；成员仅能改删自己的。
- 邀请：拆分 `invitation`（1）+ `join_request`（N）；取消 `used`，未过期即可用；默认 24h 有效，创建时优先复用剩余 ≥6h 的邀请；家庭 ≤10 人。
- 账目状态：`transaction.status`（1=有效，0=作废），所有查询与统计默认只看有效流水。
- 图片：`POST /upload` 落盘到 `app.upload.dir`（容器内 `/app/data/upload`，由 compose 用 `UPLOAD_HOST_DIR` 绑到宿主机图床目录持久化），通过 `/api/files/**` 静态访问（该路径不校验 token，靠 UUID 文件名防猜测）。
- 定时提醒：`reminder.cron`（默认每分钟）扫描到点成员，当日家庭已有人记账则跳过；多实例靠 Redis 锁防重复推送。

## 冒烟测试

推荐一键执行完整套件（自动构建 + 在 **8447** 端口启动独立测试实例，`WX_MOCK=true`，不干扰 8445 上的日常服务）：

```powershell
powershell -ExecutionPolicy Bypass -File script/run_smoke.ps1
```

包含两个脚本：

| 脚本 | 覆盖内容 |
|------|----------|
| `script/verify.ps1` | 全部接口契约：登录/鉴权/家庭 CRUD/邀请复用/申请幂等/审批/分类/提醒/隐私/小程序码/记账/统计/预算/上传（含静态资源读取）/用户资料/拒绝/移除/转让/退出作废/撤销/解散，共 80+ 断言 |
| `script/smoke_family.ps1` | 家庭生命周期：普通成员退出（本人流水作废、他人保留）、创建者退出（自动转让）、最后一人退出（解散）、主动解散、邀请复用 1:N、重复申请、拒绝后重新申请 |

冒烟结束后会自动收尾清理（本机无需安装 mysql 客户端）：

- `script/SmokeCleanup.java` 由 `run_smoke.ps1` 现场用 `javac` 编译，借 `~/.m2` 中的 `mysql-connector-j` 直连数据库（仍强制库名必须是 `liunian_account_dev`，否则拒绝执行），删除冒烟产生的 mock 用户（`vo_` / `vm2_` / `vm3_` / `vm4_` / `smk_` 前缀）以及未被正常解散的 `verify_*` / `smokeX_*` 家庭及其账单、邀请、申请、预算、提醒、成员、自定义分类；
- 同时回收 `data/upload` 下残留的空目录（`verify.ps1` 已删除上传的图片文件本身）。

调试时如需保留这些数据，加 `-SkipClean`：

```powershell
powershell -ExecutionPolicy Bypass -File script/run_smoke.ps1 -SkipClean
```

两个脚本均可用 `API_BASE` 指向已有实例（external 模式），且都会校验 `MYSQL_DB=liunian_account_dev`，误连生产库直接退出：

```powershell
$env:API_BASE = "http://127.0.0.1:8447/api"
powershell -ExecutionPolicy Bypass -File script/verify.ps1
```

`script/smoke.ps1` 是更快的单家庭 happy-path（同样支持 `API_BASE`）。
