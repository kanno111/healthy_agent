# Healthy Agent

医院预约系统的综合 AI Agent。项目采用前后端分离结构，并为患者端和管理员端提供独立入口：

- `frontend`：Vue 3 + TypeScript + Vite，生产环境由 Nginx 提供静态页面。
- `backend`：Java 21 + Spring Boot 4。
- 登录认证：复用现有 `healthy-gateway` 和 `healthy-identity`，Agent 不保存账号或密码。
- 本地联调：可启用 `local-dev` 内存 Token，在医院 Gateway/Identity 不启动时独立测试 Agent；后端脱离 Compose 启动时仍默认使用安全的 `gateway` 模式。
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
- 管理员可以输入自然语言问题，使用同一 BGE-M3 模型生成查询向量，并通过 Elasticsearch kNN 查看 Top 3/5/10/20 chunk、来源文档和相关分。
- 管理员可以在 RAG 测试页面提问：先使用 BGE-M3 + ES 召回资料，再由 DeepSeek 根据有限上下文生成带引用的回答，同时展示本次实际 Token 用量和耗时。
- MySQL 只记录文档级的 `indexed`、`chunk_count` 和 `indexed_at`，chunk 内容只保存在 Elasticsearch。
- Token 只保存在当前标签页的 `sessionStorage`。

文档文本解析、简单切分、BGE-M3 向量化、ES 索引、管理员 kNN 检索和管理员 RAG 前后端测试入口已经实现；面向患者的 RAG 对话页面尚未实现。本阶段不增加知识发布机制，测试范围为 ES 中所有已成功构建索引的 chunk。

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

Nacos、Sentinel、Gateway、Identity、MySQL 和 Redis 不在 Agent Compose 中。当前 Compose 强制关闭 Nacos 发现和配置，Agent 使用宿主机 MySQL 中独立的 `healthy_agent` 数据库；Redis 当前没有必要，因此不引入。

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

RAG 生成使用 DeepSeek。真实 Key 同样只填写到本地 `.env`：

```env
AGENT_LLM_BASE_URL=https://api.deepseek.com
AGENT_LLM_API_KEY=在这里填写自己的Key
AGENT_LLM_MODEL=deepseek-flash
AGENT_LLM_MAX_OUTPUT_TOKENS=600
```

第一版显式关闭模型思考模式，默认最多召回 5 个 chunk、只采用相关分不低于 `0.65` 的结果、上下文最多 6,000 字符、回答最多 600 Token。Embedding Key 与 DeepSeek Key 分开配置，二者不能放入前端。

停止服务但保留 ES、MinIO 数据：

```powershell
docker compose down
```

按需启动 Kibana：

```powershell
docker compose --profile tools up -d kibana
```

不要随意执行 `docker compose down -v`，它会删除 ES 和 MinIO 数据卷。

## 本地认证与医院系统联调

当前 Compose 默认使用本地开发认证，不要求医院项目、Gateway、Identity 或 Nacos 启动，但 Agent 后端仍需要宿主机 MySQL 可用。

- 管理员登录页点击“使用本地开发管理员登录”，患者登录页点击“使用本地开发患者登录”。
- Agent 启动时为两个角色分别生成随机的内存 Token；Token 不写入仓库、数据库、日志或 Elasticsearch。
- 前端把 Token 保存到当前标签页的 `sessionStorage`，Agent 重启后旧 Token 自动失效。
- 本地模式仍执行 `PATIENT`/`STAFF` 路由授权，并不是给所有请求伪造管理员请求头。
- `POST /api/agent/dev-auth/login` 只在 `AGENT_SECURITY_MODE=local-dev` 时签发开发 Token。

恢复医院 Gateway 模式时修改 `.env`：

```env
AGENT_SECURITY_MODE=gateway
AGENT_API_UPSTREAM=host.docker.internal:8080
```

然后执行 `docker compose up -d --build --force-recreate agent-backend agent-frontend`。`gateway` 是后端配置的默认值；该模式会关闭本地开发登录，浏览器请求重新经过 Gateway，并复用 Identity 登录。

需要登录和权限联调时，在宿主机启动医院项目的 Gateway、Identity 及其必要依赖：

- Nginx 将 `/api/**` 转发到 `host.docker.internal:8080`。
- Agent Compose 当前强制关闭 Nacos，避免医院系统关闭时持续重试；后续真正接入微服务环境时再单独恢复注册配置。

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

`gateway` 模式下，浏览器只向 Gateway 发送 JWT，Agent 后端不解析 JWT，只信任 Gateway 在认证后注入的身份头。`local-dev` 模式下，Agent 只接受自己启动时生成的随机开发 Token。两种模式都执行角色隔离：患者 Token 访问管理员接口、管理员 Token 访问患者接口均返回 HTTP 403。

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
| `POST /api/agent/admin/knowledge/search` | 将问题向量化并执行 ES kNN 检索，`limit` 支持 1 至 20 | `STAFF` |
| `POST /api/agent/admin/knowledge/rag/ask` | 检索知识库并由 DeepSeek 生成带引用回答，`limit` 支持 1 至 8 | `STAFF` |

MinIO 对象路径为 `documents/yyyy/MM/{documentId}/{fileName}`。MySQL 表只保存成功文档及其文档级索引结果，不保存 chunk；Elasticsearch 索引名为 `healthy-agent-knowledge-chunks-v1`，保存 chunk 文本、来源字段、`embeddingModel` 和 1024 维 `embedding`。接口返回的 `UPLOADED` 只表示原文件与元数据已经保存，是否完成切块以 `indexed` 和 `chunkCount` 为准。解析、向量化或 ES 写入任一步失败时，文档保持 `indexed=false`，可以直接重试。解析预览不会落库或写 ES；扫描版 PDF 暂不执行 OCR。

管理员可访问 `http://127.0.0.1:8090/admin/knowledge/search` 测试向量召回。本仓库的 [`test-data/knowledge`](test-data/knowledge) 提供 TXT、Markdown、DOCX 和 PDF 四种虚构医院规则样本，以及推荐测试问题。先上传并为每份文档构建索引，再进入检索测试页观察召回结果。

管理员可访问 `http://127.0.0.1:8090/admin/knowledge/rag` 测试完整 RAG 回答。页面默认使用 Top 3，可切换 Top 1/3/5/8，并展示回答、引用 chunk、相关分、生成模型、Embedding 模型、Token 用量和浏览器侧请求耗时。

管理员 RAG 请求示例：

```json
POST /api/agent/admin/knowledge/rag/ask
{
  "question": "门诊预约取消后如何退费？",
  "limit": 5
}
```

响应包含 `answer`、`citations`、生成模型、Embedding 模型，以及 `usage.promptTokens`、`usage.completionTokens` 和 `usage.totalTokens`。低于最低相关分或没有召回结果时不会请求 DeepSeek，而是直接返回知识库信息不足；引用内容来自真正送入模型的 chunk。当前只开放管理员接口，底层 `KnowledgeRagService` 不绑定角色，后续患者端可以复用同一套检索与生成逻辑，再由独立的患者 Controller 执行权限和展示策略。

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
