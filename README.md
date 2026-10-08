# 流年小账 · 家庭共享记账微信小程序后端

Spring Boot 3.3 (JDK 17) + MyBatis-Plus + MySQL 8 + Redis。提供 REST JSON 接口，Base Path `/api`。

## 环境要求
- JDK 17（本机：`E:\JDK\jdk-17.0.19`）
- Maven 3.9.x（本机：`D:\Program Files\maven\bin\mvn.cmd`）
- MySQL 8（库 `liunian_account`）、Redis（远端已部署，连接信息见 `ci/.env`）

## 快速开始
1. 准备数据库：在 MySQL 执行 `doc/sql/schema.sql`（含建表 + `global` 预置分类种子）。
2. 配置环境变量：复制 `ci/.env.example` 为 `ci/.env`，按实际填写 MySQL / Redis / 微信凭据。
3. 启动（脚本会自动把 `.env` 注入环境变量，并强制 Asia/Shanghai 时区）：
   ```powershell
   powershell -ExecutionPolicy Bypass -File ci/start.ps1
   ```
   服务监听 `http://127.0.0.1:8445/api`，健康检查 `GET /api/health`。

> 也可在 IDE 中直接 Run `AccountApplication`，但需自行把 `ci/.env` 的变量配到 Run Configuration 的环境变量里。

## 本地联调（mock 模式）
`ci/.env` 中 `WX_MOCK=true` 时，`/login` 的 `code` 写成 `mock:<openid>` 即可直接换为该 openid 的 token，
无需真实微信 appid/secret 即可跑通全部业务接口（含创建家庭、邀请、审批、记账、统计）。
生产环境将 `WX_MOCK` 置为 `false` 即走真实 `code2Session`。

## 鉴权
除 `POST /login`、`GET /invitation/{token}`、`POST /invitation/{token}/apply`、`GET /health` 外，
所有接口请求头需带 `Authorization: Bearer <token>`。token 由服务端签发并写入 Redis（可主动失效）。

## 目录结构
```
src/main/java/com/liunian/account
├── common/    R(统一响应) / ErrorCode / BizException / GlobalExceptionHandler / JacksonListTypeHandler
├── config/    WebMvcConfig(Jwt拦截器) / WxProperties / MybatisPlusConfig
├── security/  JwtUtil / UserContext / AuthInterceptor
├── wx/        WxService / WxAccessTokenManager(access_token 缓存)
├── service/   业务服务（Auth/Family/FamilyMember/Invitation/Transaction/Stats/Category）
├── mapper/ + resources/mapper/*.xml   统计聚合 SQL
├── entity/ dto/ vo/ util/
└── controller/  Auth Family Invitation Transaction Stats Member Category Reminder Budget Privacy Wxacode
└── job/       ReminderJob(定时提醒 + Redis 分布式锁)
```

## 已实现接口
| 组 | 接口 |
|----|------|
| 登录 | `POST /login`、`GET /user/privacy`、`POST /user/privacy` |
| 账本 | `POST /family`、`GET /family/{id}`、`PATCH /family/{id}` |
| 邀请审批 | `POST /invitation`、`GET /invitation/{token}`、`POST /invitation/{token}/apply`、`POST /invitation/revoke`、`GET /family/{id}/pending`、`.../approve`、`.../reject` |
| 成员 | `GET /family/{id}/members`、`DELETE .../member/{mid}`、`POST .../transfer`、`POST .../quit`、`PATCH .../member/me` |
| 记账 | `POST /transaction`、`PUT/DELETE /transaction/{id}`、`GET /transaction/{id}`、`GET /transactions`、`GET /transactions/by-day` |
| 统计 | `GET /stats` |
| 分类 | `GET /categories`、`POST /category`、`DELETE /category/{id}` |
| 预算 | `GET /budget`、`PUT /budget` |
| 提醒 | `GET /reminder`、`PUT /reminder` |
| 小程序码 | `GET /wxacode` |

## 关键约定
- 金额一律整数「分」；时区强制 Asia/Shanghai（提醒任务依赖）。
- v1 单家庭：一个 openid 在 `family_member` 中仅一条活跃/待审记录；被移除后重新申请走 upsert。
- 创建者可编辑/删除他人记录（按接口定义）；成员仅能改删自己的。
- 邀请：token 一次性 + 7 天有效 + ≤10 人；拒绝后同 openid 24h 内再申请被拒（防骚扰）。
- 定时提醒：`reminder.cron`（默认每分钟）扫描到点成员，当日家庭已有人记账则跳过；多实例靠 Redis 锁防重复推送。

## 一键冒烟
```powershell
powershell -ExecutionPolicy Bypass -File ci/smoke.ps1
```
（需先启动服务，且 `WX_MOCK=true`）
