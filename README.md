# Healthy Agent

医院预约系统的综合 AI Agent。项目采用前后端分离结构，并为患者端和管理员端提供独立入口：

- `frontend`：Vue 3 + TypeScript + Vite，生产环境由 Nginx 提供静态页面。
- `backend`：Java 21 + Spring Boot 4。
- 登录认证：复用现有 `healthy-gateway` 和 `healthy-identity`，Agent 不保存账号或密码。
- 角色边界：患者使用 `PATIENT`，知识库管理员复用现有 `STAFF`。

## 当前功能

- 患者和管理员分别登录、退出，并按角色进入对应页面。
- 患者接口 `/api/agent/patient/**` 仅允许 `PATIENT`。
- 管理接口 `/api/agent/admin/**` 仅允许 `STAFF`。
- 后端只信任 Gateway 认证后注入的用户 ID 和角色。
- 管理员可以上传、查看、解析预览、构建索引和删除 PDF、DOCX、TXT、MD、Markdown 文档。
- 后端校验文件大小、扩展名和实际文件特征，将原始文件保存到 MinIO，并把成功文档的元数据保存到独立 MySQL 数据库。
- Apache Tika 从 MinIO 提取文档文本，预览最多返回 12,000 个字符且不执行 OCR。
- Agent 后端负责把全文切成最多 1,000 字符、相邻重叠 100 字符的 chunk，批量调用硅基流动 `BAAI/bge-m3` 生成 1024 维向量，再写入 Elasticsearch。
- MySQL 只记录文档级的 `indexed`、`chunk_count` 和 `indexed_at`，chunk 内容只保存在 Elasticsearch。
- Token 只保存在当前标签页的 `sessionStorage`。

文档文本解析、简单切分、BGE-M3 向量化和 ES 索引已经实现；kNN 检索接口和 RAG 问答尚未实现。

## 当前 Docker 编排

[`compose.yml`](compose.yml) 只编排 Agent 自身当前真正需要的组件：

| 服务 | 用途 | 本机地址 |
| --- | --- | --- |
| `agent-frontend` | Vue 生产包 + Nginx | `http://127.0.0.1:8090` |
| `agent-backend` | Agent Spring Boot 服务 | `http://127.0.0.1:8091` |
| `elasticsearch` | 保存知识文档 chunk，后续用于检索与向量索引 | `http://127.0.0.1:9200` |
| `minio` | 保存知识库原始文档；控制台使用 9001 | `http://127.0.0.1:9001` |
| `minio-init` | 幂等创建知识库 Bucket，执行成功后退出 | 无常驻端口 |
| `kibana` | ES 调试界面，仅在 `tools` profile 中按需启动 | `http://127.0.0.1:5601` |

Nacos、Sentinel、Gateway、Identity、MySQL 和 Redis 不在 Agent Compose 中。Agent 使用宿主机 MySQL 中独立的 `healthy_agent` 数据库；Redis 当前没有必要，因此不引入。

## Docker 启动

首次启动前复制环境变量模板并修改 MinIO 密码：

```powershell
cd D:\healthy-agent
Copy-Item .env.example .env
docker compose up -d --build
docker compose ps
```

`.env` 中还需要配置独立的 Agent 数据库账号。当前机器已经创建了 `healthy_agent` 数据库、专用账号并完成 Flyway 迁移。之后通常只需要：

```powershell
cd D:\healthy-agent
docker compose up -d
```

后续向量化使用硅基流动免费的 `BAAI/bge-m3`。将真实 Key 只填写到本地 `.env`：

```env
AGENT_EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1
AGENT_EMBEDDING_API_KEY=在这里填写自己的Key
AGENT_EMBEDDING_MODEL=BAAI/bge-m3
AGENT_EMBEDDING_DIMENSIONS=1024
```

`.env` 已被 Git 忽略；不要把 Key 写入 `.env.example`、前端代码、MySQL、Elasticsearch 或日志。后端按最多 16 个 chunk 一批调用该接口，并校验向量数量、维度和数值有效性。

停止服务但保留 ES、MinIO 数据：

```powershell
docker compose down
```

按需启动 Kibana：

```powershell
docker compose --profile tools up -d kibana
```

不要随意执行 `docker compose down -v`，它会删除 ES 和 MinIO 数据卷。

## 与医院系统联调

Agent Compose 不要求医院业务服务全部启动，但 Agent 后端需要宿主机 MySQL 可用。医院 Gateway、Identity 或 Nacos 未启动时，登录或经 Gateway 调用的接口不可用。

需要登录和权限联调时，在宿主机启动医院项目的 Gateway、Identity 及其必要依赖：

- Nginx 将 `/api/**` 转发到 `host.docker.internal:8080`。
- Agent 后端默认不连接 Nacos，避免医院系统关闭时持续重试。
- 外部 Nacos 可用后，将 `.env` 中 `NACOS_DISCOVERY_ENABLED` 改为 `true`，再执行 `docker compose up -d --force-recreate agent-backend`。
- `NACOS_SERVER_ADDR` 默认是 `host.docker.internal:8848`，不需要在 Agent Compose 中再建一个 Nacos。
- Gateway 当前运行在 Windows 宿主机，因此 Agent 默认以 `127.0.0.1:8091` 注册到 Nacos；可通过 `AGENT_DISCOVERY_IP` 覆盖。

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

## 文档管理与简单切块

管理员登录后访问 `http://127.0.0.1:8090/admin/knowledge`：

1. 选择或拖入一个文档。
2. 页面先检查扩展名、空文件和 20 MiB 大小限制。
3. 点击“确认上传”后，通过 Nginx 和 Gateway 调用 Agent。
4. 后端再次执行可信校验，并写入 MinIO 的 `healthy-agent-knowledge` Bucket。
5. MinIO 成功后，在短 MySQL 事务中写入 `knowledge_document` 元数据。
6. 如果 MySQL 写入失败，后端补偿删除刚上传的 MinIO 对象。
7. 页面从 MySQL 查询并展示成功文档，失败文档不会进入列表。
8. 点击“解析预览”时，后端从 MinIO 读取原文件并由 Apache Tika 即时提取文本。
9. 点击“构建索引”后，后端提取全文、执行简单切块、批量调用 BGE-M3，并把文本及 1024 维向量写入 Elasticsearch；成功后才更新 MySQL 文档级索引信息。
10. 已索引文档默认不会重复构建；点击“重新构建”会先清理该文档旧 chunk，再写入新 chunk。
11. 点击“删除”并二次确认后，依次清理 Elasticsearch chunk、MySQL 元数据和 MinIO 原文件。

| 接口 | 作用 | 权限 |
| --- | --- | --- |
| `POST /api/agent/admin/knowledge/documents` | 以 `multipart/form-data` 上传 `file` | `STAFF` |
| `GET /api/agent/admin/knowledge/documents` | 查看已上传文档 | `STAFF` |
| `POST /api/agent/admin/knowledge/documents/{documentId}/parse-preview` | 即时解析并返回受限长度的文本预览 | `STAFF` |
| `POST /api/agent/admin/knowledge/documents/{documentId}/index` | 切分全文并构建 ES 索引；`force=true` 可强制重建 | `STAFF` |
| `DELETE /api/agent/admin/knowledge/documents/{documentId}` | 删除 ES chunk、元数据和原文件 | `STAFF` |

MinIO 对象路径为 `documents/yyyy/MM/{documentId}/{fileName}`。MySQL 表只保存成功文档及其文档级索引结果，不保存 chunk；Elasticsearch 索引名为 `healthy-agent-knowledge-chunks-v1`，保存 chunk 文本、来源字段、`embeddingModel` 和 1024 维 `embedding`。接口返回的 `UPLOADED` 只表示原文件与元数据已经保存，是否完成切块以 `indexed` 和 `chunkCount` 为准。解析、向量化或 ES 写入任一步失败时，文档保持 `indexed=false`，可以直接重试。解析预览不会落库或写 ES；扫描版 PDF 暂不执行 OCR。

Elasticsearch 9 默认不在普通搜索响应的 `_source` 中返回 dense vector。在 Kibana Dev Tools 中检查原始向量时，需要显式加入：

```json
"_source": {
  "exclude_vectors": false
}
```

## 本地代码验证

```powershell
cd D:\healthy-agent\backend
mvn test

cd D:\healthy-agent\frontend
npm run build
```

项目进度见 [`docs/PROGRESS.md`](docs/PROGRESS.md)。
