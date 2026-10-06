# Healthy Agent

医院预约系统的综合 AI Agent，当前已建立患者端与管理员端的独立登录入口。项目采用前后端分离结构：

- `frontend`：Vue 3 + TypeScript + Vite
- `backend`：Java 21 + Spring Boot 4
- 登录认证：复用现有 `healthy-gateway` 和 `healthy-identity`
- 角色边界：患者使用 `PATIENT`，知识库管理员复用现有 `STAFF`

## 当前功能

- 患者使用医院预约系统账号登录，进入患者 Agent 页面。
- 管理员使用医院预约系统账号登录，进入管理控制台和知识库管理入口。
- 两类账号共用现有 `/api/auth/login`，Agent 不建立账号表、不保存密码。
- 登录后分别通过 Agent 后端验证 Gateway 注入的用户 ID 和角色。
- 患者接口位于 `/api/agent/patient/**`，仅允许 `PATIENT`。
- 管理接口位于 `/api/agent/admin/**`，仅允许 `STAFF`。
- 使用 `sessionStorage` 保存当前标签页的登录状态，支持主动退出和会话失效处理。

管理员端当前完成的是登录、权限隔离、管理首页和知识库页面骨架。文档上传、解析、MySQL 元数据、ES 索引和 Redis 任务状态尚未接入，将在 RAG 阶段实现。

## 启动条件

先启动现有 `D:\healthy` 项目的 Nacos、Redis、MySQL、Identity 和 Gateway，并将
[`deploy/nacos/healthy-gateway-route-snippet.yml`](deploy/nacos/healthy-gateway-route-snippet.yml)
中的路由合并到 Gateway 路由列表。

### 后端

```powershell
cd D:\healthy-agent\backend
mvn spring-boot:run
```

默认端口：`8091`。

### 前端开发模式

```powershell
cd D:\healthy-agent\frontend
npm install
npm run dev
```

默认地址：`http://localhost:5173`。Vite 会将 `/api/**` 转发到
`VITE_GATEWAY_BASE_URL`，未配置时使用 `http://localhost:8080`。

### Nginx 生产构建

```powershell
cd D:\healthy-agent
docker compose up -d --build frontend
```

生产访问地址：

- 患者登录：`http://127.0.0.1:8089/login`
- 管理员登录：`http://127.0.0.1:8089/admin/login`
- 管理控制台：`http://127.0.0.1:8089/admin`
- 知识库管理：`http://127.0.0.1:8089/admin/knowledge`

本地演示账号：

| 身份 | 用户名 | 密码 | 角色 |
| --- | --- | --- | --- |
| 患者 | `patient_demo` | `123456` | `PATIENT` |
| 管理员 | `staff_demo` | `123456` | `STAFF` |

## 会话验证接口

| 接口 | 允许角色 |
| --- | --- |
| `GET /api/agent/patient/auth/me` | `PATIENT` |
| `GET /api/agent/admin/auth/me` | `STAFF` |

浏览器只向 Gateway 发送 JWT。Agent 后端不解析 JWT，只信任 Gateway 在认证后注入的身份头；患者 Token 访问管理员接口、管理员 Token 访问患者接口均返回 HTTP 403。

## 验证

```powershell
cd D:\healthy-agent\backend
mvn test

cd D:\healthy-agent\frontend
npm run build
```

项目进度见 [`docs/PROGRESS.md`](docs/PROGRESS.md)。
