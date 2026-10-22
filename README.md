# 流年小账 · 家庭共享记账微信小程序后端

家庭共享记账小程序的后端：一家人共用一个账本，记录收支、按分类统计、设预算，支持邀请审批入群与定时记账提醒。

技术栈：Spring Boot 3.3（JDK 17）+ MyBatis-Plus + MySQL 8 + Redis。接口前缀 `/api`，响应体统一 `{code,msg,data}`，鉴权用 JWT（会话同时写入 Redis，可主动失效）。

## 主要功能

- **微信登录**：`code2Session` 换 openid 签发 token，记录隐私协议同意状态
- **家庭账本**：创建 / 修改 / 解散家庭，上限 10 人，创建者退出自动转让
- **邀请与审批**：二维码邀请（一份邀请可多人使用，24h 有效）+ 申请审批流
- **记账**：收支增删改查、按日 / 按月查询，金额一律整数「分」，支持作废
- **统计与预算**：分类聚合统计、家庭月度预算
- **分类管理**：系统预置分类 + 自定义分类
- **储蓄（第二本账）**：独立于收支的储蓄账户 + 流水，支持存入 / 取出 / 账户转账 / 更新余额（余额校准），
  记账页可「从储蓄支付」「到账即存」；余额纯派生不落库，不产生任何收入或支出
- **图片上传**：账单图片落盘，经 `/api/files/**` 静态访问
- **定时提醒**：到点为成员推送订阅消息，家庭当日已有人记账则跳过
- **资料**：用户全局资料、家庭内昵称 / 头像分别可改

## 本地启动

前提：JDK 17、Maven 3.9+、MySQL 8、Redis。

```bash
# 1. 建库（开发库固定 liunian_account_dev，禁止连生产库 liunian_account）
mysql -e "CREATE DATABASE IF NOT EXISTS liunian_account_dev DEFAULT CHARSET utf8mb4;"

# 2. 建表 + 预置分类
mysql liunian_account_dev < ci/schema.sql

# 3. 配置环境变量（模板 ci/.env.example）
cp ci/.env.example .env

# 4. 启动（默认 8445）
powershell -ExecutionPolicy Bypass -File script/start.ps1
```

启动成功后 `GET http://127.0.0.1:8445/api/health` 返回 `code=0`，日志见 `log/boot.log`。也可在 IDE 直接 Run `AccountApplication`，会自动读取根目录 `.env`。

## 远程部署（Docker Compose）

```bash
cp ci/.env.example .env    # 与 docker-compose.yml 同级
docker compose up -d       # 插值与容器注入都读同目录 .env，无需 --env-file
docker compose ps          # 状态 healthy 即就绪
docker compose logs -f app
```

`.env` 中可覆盖的部署项：`CONTAINER_NAME`（默认 `liunian-account-backend`）、`SERVER_PORT`（默认 8445）、`IMAGE`、`UPLOAD_HOST_DIR`（图片落盘宿主机目录，容器内固定 `/app/data/upload`）。

注意三点：

- `.env` 必须在 compose 同目录；若放在 `ci/.env`，启动要写 `docker compose --env-file ci/.env up -d`，否则 `${VAR}` 全部回落默认值。
- 容器内 `MYSQL_HOST` / `REDIS_HOST` 由 compose 固定为 `mysql8-container` / `redis_container`（走 docker 网络），`.env` 里的同名值在容器内不生效。
- 生产库 `liunian_account` 仅在线上使用，需同时设置 `MYSQL_DB=liunian_account`、`DB_ALLOW_PROD=true`。

## 其他

- 接口与业务冒烟：`powershell -ExecutionPolicy Bypass -File script/run_smoke.ps1`
- 存量库升级执行 SQL：`powershell -ExecutionPolicy Bypass -File script/apply_sql.ps1 -File ci/migration/20261022_saving.sql`
  （无需 mysql 客户端；脚本只允许连 `liunian_account_dev`）
- 文档：`doc/接口文档.md`（接口契约）、`doc/储蓄功能设计.md`（储蓄产品设计）、`doc/储蓄功能后端设计.md`（储蓄实现说明）、
  `doc/家庭记账小程序设计方案.md`（设计）
