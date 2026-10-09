# Healthy Agent

医院预约系统的综合 AI Agent。项目采用前后端分离结构，并为患者端和管理员端提供独立入口：

- `frontend`：Vue 3 + TypeScript + Vite，生产环境由 Nginx 提供静态页面。
- `backend`：Java 21 + Spring Boot 4。
- 登录认证：前端通过 Nginx 调用云端 Healthy 单体的 `/api/auth/**`，复用其账号体系和正式 JWT；Agent 不保存账号或密码。
- 本地联调：Agent 暂不校验 Healthy JWT，而是信任浏览器根据登录响应附带的用户 ID 和角色；该简化方案仅允许在 `127.0.0.1` 使用。此外仍保留 `local-dev` 内存 Token 作为纯页面测试入口。
- 角色边界：患者使用 `PATIENT`，知识库管理员复用现有 `STAFF`。

## 当前功能

- 患者和管理员分别登录、退出，并按角色进入对应页面。
- 患者首页提供统一聊天界面：模型在固定业务 Tools 与医院知识库 RAG 之间选择，并展示回答、来源、实际工具和 Token 用量；创建和取消预约采用人工确认卡片。
- 患者对话使用 Spring AI 2.0.1 的 `ChatClient`、`ToolCallingAdvisor` 和 `MessageWindowChatMemory`，每个会话保留最近 40 条最终消息（约 20 轮问答）。
- 患者接口 `/api/agent/patient/**` 仅允许 `PATIENT`。
- 管理接口 `/api/agent/admin/**` 仅允许 `STAFF`。
- 当前本机版本通过 `X-Auth-User-Id` 和 `X-Auth-Role` 区分患者/管理员；这些请求头可由客户端伪造，因此不能作为生产认证方案。
- 管理员可以上传、查看、解析预览、构建索引和删除 PDF、DOCX、TXT、MD、Markdown 文档。
- 后端校验文件大小、扩展名和实际文件特征，将原始文件保存到 MinIO，并把成功文档的元数据保存到独立 MySQL 数据库。
- Apache Tika 从 MinIO 提取文档文本，预览最多返回 12,000 个字符且不执行 OCR。
- Agent 后端负责把全文切成最多 1,000 字符、相邻重叠 100 字符的 chunk，批量调用硅基流动 `BAAI/bge-m3` 生成 1024 维向量，再写入 Elasticsearch。
- 管理员可以输入自然语言问题，使用同一 BGE-M3 模型生成查询向量，并通过 Elasticsearch kNN 查看 Top 3/5/10/20 chunk、来源文档和相关分。
- 管理员可以在 RAG 测试页面提问：先使用 BGE-M3 + ES 召回资料，再由 DeepSeek 根据有限上下文生成带引用的回答，同时展示本次实际 Token 用量和耗时。
- MySQL 只记录文档级的 `indexed`、`chunk_count` 和 `indexed_at`，chunk 内容只保存在 Elasticsearch。
- Token 只保存在当前标签页的 `sessionStorage`。

文档文本解析、简单切分、BGE-M3 向量化、ES 索引、管理员 kNN 检索、管理员 RAG 测试入口、患者聊天、第一批只读 Tool Calling，以及创建/取消预约 HITL 基础版已经实现。本阶段不增加知识发布机制，RAG 范围为 ES 中所有已成功构建索引的 chunk。

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

Nacos、Sentinel、Gateway、Identity、Healthy 单体、MySQL 和 Redis 不在 Agent Compose 中。当前 Compose 强制关闭 Nacos 发现和配置，Agent 使用宿主机 MySQL 中独立的 `healthy_agent` 数据库；Healthy 单体部署在云端，Redis 当前没有必要，因此不引入。

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

云端 Healthy 单体作为登录接口和后续 Tool API 的上游：

```env
HEALTHY_API_UPSTREAM=8.137.98.235:80
AGENT_HEALTHY_API_BASE_URL=http://8.137.98.235
```

`HEALTHY_API_UPSTREAM` 供前端 Nginx 代理登录使用；`AGENT_HEALTHY_API_BASE_URL` 供 Agent 后端执行 Tool 时直连 Healthy 业务 API。二者暂时指向同一台云端单体。

当前服务器只有 HTTP，只能使用演示账号和非敏感数据；正式环境必须先配置域名和 HTTPS。

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

## 本地认证与云端 Healthy 联调

当前不需要在本机启动原 Healthy 项目、Gateway、Identity 或 Nacos：

- Nginx 将 `/api/agent/**` 转发给本地 Agent 后端，将其他 `/api/**`（包括登录、退出和未来业务 Tool）转发给 `HEALTHY_API_UPSTREAM`。
- 云端 Healthy 校验账号密码并签发正式 JWT。前端只把 JWT 保存到当前标签页的 `sessionStorage`，不写入 Agent 数据库、日志、ES 或模型上下文。
- 前端调用 Agent 接口时携带原始 `Authorization`，并根据登录响应附加 `X-Auth-User-Id`、`X-Auth-Role`。当前 Agent 不解析 JWT，只使用这两个身份头执行 `PATIENT`/`STAFF` 路由区分。
- Tool 适配器从当前 HTTP 请求读取 `Authorization` 并原样转发给云端 Healthy；JWT 不会成为 LLM Tool 参数，也不会传给 DeepSeek。
- 点击“使用本地开发患者/管理员登录”仍可使用 Agent 自己签发的随机内存 Token，仅用于不访问云端业务的页面测试。

这里的身份头由浏览器生成，用户可以伪造，所以当前方案只适用于绑定在 `127.0.0.1` 的个人开发环境。部署给其他人使用之前，必须改成由受信任后端校验 JWT，或让 Gateway 校验后注入身份头。

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

云端账号登录后，当前本机版本通过登录结果中的角色选择对应接口；`local-dev` 模式则校验 Agent 自己生成的随机开发 Token。两种方式都会执行角色路由检查，但云端登录方式的身份头可由客户端伪造，不能视为真正的生产鉴权。

## 文档管理与简单切块

管理员登录后访问 `http://127.0.0.1:8090/admin/knowledge`：

1. 选择或拖入一个文档。
2. 页面先检查扩展名、空文件和 20 MiB 大小限制。
3. 点击“确认上传”后，通过 Nginx 调用本地 Agent；当前本机版使用登录响应中的 STAFF 身份头做角色检查。
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
| `POST /api/agent/patient/chat/messages` | 患者发送聊天消息，在业务 Tools 与固定 Top 3 RAG 之间路由；创建/取消预约时返回确认卡片 | `PATIENT` |
| `POST /api/agent/patient/actions/{actionId}/confirm` | 确认并执行尚未过期的患者写操作 | `PATIENT` |
| `POST /api/agent/patient/actions/{actionId}/reject` | 放弃尚未过期的患者写操作，不调用医院写接口 | `PATIENT` |

患者聊天请求必须携带当前页面生成的会话 UUID：

```json
{
  "conversationId": "11111111-1111-4111-8111-111111111111",
  "message": "查询我的预约记录"
}
```

后端不会直接信任这个 ID，而是与当前患者 ID 组合后再作为 Spring AI Memory 的键，避免不同患者共享同一个记忆空间。

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

响应包含 `answer`、`citations`、生成模型、Embedding 模型，以及 `usage.promptTokens`、`usage.completionTokens` 和 `usage.totalTokens`。低于最低相关分或没有召回结果时不会请求 DeepSeek，而是直接返回知识库信息不足；引用内容来自真正送入模型的 chunk。

患者登录后访问 `http://127.0.0.1:8090/` 进入聊天页面。每次提问先由 DeepSeek 从以下固定能力中选择，模型不能自行指定 URL、HTTP 方法或 Authorization：

- `list_departments`：查询可用科室。
- `search_doctors`：按科室或关键词查询医生；不传筛选条件时一次查询所有科室最多 100 位医生。
- `get_doctor_detail`：查询指定医生详情。
- `list_schedule_slots`：查询指定医生最多 14 天的开放号源。
- `list_doctors_schedule_slots`：一次查询最多 30 位医生在指定日期范围内的号源，默认只返回仍有余号的班次。
- `list_my_appointments`：查询当前登录患者的预约记录。
- `list_my_waitlists`：查询当前登录患者的候补记录。
- `prepare_create_appointment`：只接受本轮号源查询返回的真实 `scheduleSlotId`，重新核验后生成预约确认卡片，本身不创建预约。
- `prepare_cancel_appointment`：根据刚查询到的 `BOOKED` 预约生成取消确认卡片，本身不执行取消。
- `search_hospital_policy`：转入现有 BGE-M3 + Elasticsearch + DeepSeek RAG 链路。

业务 Tool 由后端固定注册表校验参数后，通过 Java 21 `HttpClient` 调用 Healthy 单体，并仅在该服务端请求中转发当前用户 JWT；该客户端支持读取接口所需的 GET 和取消接口所需的 PATCH。模型只看到清洗后的业务结果，永远看不到 JWT、密码、目标地址或任意请求能力。Spring AI `ToolCallingAdvisor` 负责多轮工具循环，默认限制单工具最多 30 次、单请求总共最多 60 次；“全院谁有号”使用“全部医生 → 一次批量查询号源 → 最终回答”的链路，避免模型逐个医生循环。JWT 和 userId 只放在不会发送给模型的 `ToolContext` 中。

取消预约是第一版 HITL 写操作：模型可以先调用 `list_my_appointments` 选择目标，也可以利用当前会话最近约 20 轮内容理解“取消第一条”。但 `prepare_cancel_appointment` 一定会重新查询 Healthy，核验预约属于当前患者且仍为 `BOOKED`，然后才生成不可猜测的 `actionId` 和确认卡片，本身不会取消。待确认动作只保存在 Agent 进程内存中，绑定当前患者并在 10 分钟后过期，不保存 JWT。患者点击“确认取消”后，后端会再次查询该预约是否仍为 `BOOKED`，随后才调用固定的 `PATCH /api/user/appointments/{id}/cancel`；点击“暂不取消”不会访问写接口。重复确认、越权确认、过期确认都会被拒绝。Agent 重启后未确认动作会失效，这是当前简化版的预期行为。

创建预约复用同一套 HITL 状态机。模型必须在本轮先调用单人或批量号源查询，`prepare_create_appointment` 只接受该次查询真实返回的 `scheduleSlotId`；医生和日期由后端结构化上下文恢复。准备确认卡和点击确认时都会重新读取号源并检查余量。Agent 在准备阶段生成 UUID `requestId` 并隐藏在后端 payload 中，最终只向 Healthy 发送 `scheduleSlotId + requestId`；可重试 POST 最多自动重放一次，且复用完全相同的 `requestId`。

查询预约后，Agent 会按照实际返回给页面的顺序，把“序号 → 真实 appointmentId”作为当前会话的结构化快照在内存中保留 30 分钟，不保存 JWT。“取消第三条”等序号指令由后端直接解析快照并调用 `prepare_cancel_appointment`，不再让模型根据聊天文字猜 ID；没有快照或序号越界时会要求先重新查询。对于用户明确提供预约号的操作指令，后端仍会为本轮 DeepSeek 请求指定准备工具。最终响应要么包含真正的确认卡片，要么返回业务核验失败，不允许只用文字要求确认却不生成按钮。取消规则、退费等咨询仍按 RAG 查询处理，不会被强制进入写操作。

患者 RAG 仍由后端固定使用 Top 3，浏览器不能自行扩大召回范围。页面支持聊天气泡、示例问题、回答依据展开、实际工具、Token 展示、通用写操作确认卡、新对话、加载与错误状态。前端与后端统一只保留同一 `conversationId` 最近 40 条最终用户/助手消息，约 20 轮；欢迎语不计入记忆，超过窗口的旧气泡会从页面移除，避免让用户误以为模型仍知道更早内容。Spring AI Memory 不保存中间 Tool 消息、不写 MySQL/Redis，服务重启即清空。点击“新对话”会生成新的 UUID，因此旧会话不再参与回答。当前尚未接入 SSE 和候补写操作。创建预约的 `requestId` 由 Agent 在准备确认动作时生成，同一次自动重试严格复用，且不会发送给模型或前端。

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
