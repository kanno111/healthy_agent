# Healthy Agent 开发进度

最后更新：2026-10-07

## 总览

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| 1. 患者登录闭环 | 已完成 | 真实账号登录、Gateway 身份校验、退出和 Nginx 发布均已联调 |
| 2. 管理员登录与权限骨架 | 已完成 | 复用 STAFF 账号，前后端双入口、路由隔离和知识库管理页面骨架已完成 |
| 2.1 本地开发认证 | 已完成 | 医院项目关闭时使用 Agent 内存 Token，保留 PATIENT/STAFF 隔离，可配置切回 Gateway |
| 3. 文档管理第一阶段 | 已完成 | 上传、列表、删除及 PDF/DOCX/TXT/Markdown 即时解析预览已实现 |
| 4. 基础对话接口 | 未开始 | 普通响应与 SSE 流式响应 |
| 5. 患者查询 Tools | 未开始 | 科室、医生、号源、我的预约与候补 |
| 6. 写操作确认 | 未开始 | actionId、requestId、确认及幂等执行 |
| 7. RAG 知识库 | 进行中 | 管理员检索与 DeepSeek 引用问答前后端已完成；患者问答页面与正式开放策略待实现 |
| 8. Agent Docker 部署 | 已完成基础版 | 前后端、ES、MinIO 已独立编排；Kibana 按需启动 |

## 已完成：双角色登录

- [x] 创建独立的 `frontend` 和 `backend` 目录。
- [x] 前端复用 `POST /api/auth/login`，未复制账号密码逻辑。
- [x] 患者入口仅接受 `PATIENT`，管理员入口仅接受 `STAFF`。
- [x] Token 只保存在当前标签页的 `sessionStorage`。
- [x] 后端只信任 Gateway 注入的 `X-Auth-User-Id` 和 `X-Auth-Role`。
- [x] 身份认证与角色授权拆分为独立拦截逻辑。
- [x] 患者路由 `/api/agent/patient/**` 与管理路由 `/api/agent/admin/**` 分别授权。
- [x] 增加管理员登录页、管理首页和知识库管理入口。
- [x] 增加患者/管理员互相越权测试。
- [x] 完成退出登录及本地状态清理。
- [x] 前端在 Nginx Docker 镜像内完成生产构建并发布到 `8090`。

## 已完成：无医院项目依赖的本地开发认证

- [x] 增加 `gateway` 和 `local-dev` 两种明确的认证模式，后端脱离 Compose 时安全默认仍为 `gateway`。
- [x] 当前 Agent Compose 默认使用 `local-dev`，医院项目、Gateway、Identity 和 Nacos 均可保持关闭。
- [x] Agent 每次启动分别生成 PATIENT 与 STAFF 随机内存 Token，不保存到 MySQL、ES、MinIO 或日志。
- [x] 管理员和患者登录页均增加本地开发登录按钮，不需要输入或保存测试密码。
- [x] 本地 Token 仍经过统一身份拦截器和角色拦截器，患者不能调用管理员接口，管理员不能调用患者接口。
- [x] Nginx 的 `/api/agent/**` 上游可配置；本地模式直连 Agent，切回 Gateway 模式时改为 `host.docker.internal:8080`。
- [x] 当前 Compose 强制关闭 Nacos 配置与服务发现，避免医院组件关闭时产生连接重试。
- [x] Agent 重启后旧开发 Token 自动失效，浏览器会回到登录页重新获取。

## 已完成：Agent 独立 Docker 编排

- [x] 增加后端 Java 21 多阶段 Docker 镜像。
- [x] 前端与后端均由当前项目的 `compose.yml` 构建和启动。
- [x] Elasticsearch 使用独立持久化数据卷并通过健康检查后再启动后端。
- [x] MinIO 使用独立持久化数据卷，并由一次性初始化容器幂等创建知识库 Bucket。
- [x] Kibana 放入 `tools` profile，默认不常驻以降低内存占用。
- [x] Nacos、Sentinel、Gateway、Identity、MySQL、Redis 不纳入 Agent Compose。
- [x] Agent 复用宿主机 MySQL 服务，但使用独立 `healthy_agent` 数据库和专用账号。
- [x] Nacos 发现和配置默认关闭；外部 Nacos 可用时通过 `.env` 开关接入。
- [x] 医院项目 Docker 容器已停止，未删除其数据卷。

## 已完成：文档上传第一阶段

- [x] 管理端支持选择和拖放文件，并在真正上传前展示文件名、大小和确认按钮。
- [x] 支持 PDF、DOCX、TXT、MD 和 Markdown，单文件上限为 20 MiB。
- [x] 前端做即时体验校验，后端独立执行最终安全校验。
- [x] PDF 检查 `%PDF-` 文件头，DOCX 检查必要 ZIP 条目，文本检查完整 UTF-8 内容并拒绝 NUL 字节。
- [x] 清理客户端路径和控制字符，MinIO 对象使用 UUID 隔离，避免同名覆盖。
- [x] 原始文件保存到 `documents/yyyy/MM/{documentId}/{fileName}`。
- [x] 使用 Flyway 创建 `knowledge_document`，只记录成功文档，不设置冗余上传状态列。
- [x] 文档列表从 MySQL 查询，不再扫描 MinIO；失败文档不会显示。
- [x] MinIO 成功但 MySQL 写入失败时，补偿删除刚上传的对象。
- [x] MySQL 元数据写入使用独立的短事务，不使用分布式事务。
- [x] 增加管理员上传和文档列表 API，患者访问管理接口返回 HTTP 403。
- [x] Nginx、Spring Multipart 和业务校验三层限制上传大小。
- [x] 上传失败统一返回可展示的业务错误，不向前端暴露存储密钥或底层异常。
- [x] 管理页面显示文件名、大小、上传时间和 `UPLOADED` 状态。

## 已完成：文档删除与解析预览

- [x] 增加文档删除 API 和管理端二次确认弹窗。
- [x] 删除时先清理该文档的 ES chunk，再在短事务中删除 MySQL 元数据，最后清理 MinIO 原文件。
- [x] MinIO 清理失败时记录服务端错误，避免已经删除的文档重新出现在用户列表；后续可补充孤儿对象巡检。
- [x] 增加 Apache Tika 4.1.0，仅引入 PDF、Microsoft Office 和文本解析模块。
- [x] 支持从 MinIO 即时读取并解析 PDF、DOCX、TXT、MD 和 Markdown。
- [x] 解析结果不落库、不写 ES，预览最多保留 12,000 个字符，同时返回提取字符数和截断标记。
- [x] PDF 解析显式关闭 OCR；扫描版或空文本文件返回清晰提示。
- [x] 管理端增加解析加载态、文本预览弹窗、完整/截断标记和异常提示。
- [x] 新增 `40410`、`42210`、`42211` 业务错误，底层解析异常不会直接暴露给前端。

## 已完成：简单切块与 Elasticsearch 索引

- [x] Agent 后端新增 `DocumentChunker`，统一负责 chunk 切分，不把 chunk 写入 MySQL。
- [x] 第一版采用最多 1,000 字符、重叠 100 字符的可配置规则，并优先在换行和常见中英文句末切分。
- [x] Apache Tika 从 MinIO 提取完整文本，单文档默认最多处理 2,000,000 个字符。
- [x] Elasticsearch 使用 `healthy-agent-knowledge-chunks-v1` 索引，保存 chunk 文本、顺序、文档 ID、文件名、类型、SHA-256、Embedding 模型、1024 维向量和索引时间。
- [x] 使用 Elasticsearch 官方 Java API Client，并通过 Bulk API 一次写入同一文档的所有 chunk。
- [x] chunk ID 采用 `{documentId}-{chunkIndex}`，重建时先按 documentId 删除旧 chunk，避免重复数据。
- [x] Flyway V2 为文档表增加 `indexed`、`chunk_count` 和 `indexed_at` 三个文档级字段。
- [x] 构建成功后才把 MySQL 文档标记为已索引；失败时保持未索引，可再次点击重试。
- [x] 已索引文档默认跳过重复构建，管理端提供“重新构建”入口。
- [x] 文档删除同步清理 Elasticsearch chunk，管理端列表展示索引状态和 chunk 数量。
- [x] 未引入 RabbitMQ、Redis、复杂任务状态或 chunk 元数据表。
- [x] 预置硅基流动 `BAAI/bge-m3` 的后端与 Compose 配置，API Key 仅从被 Git 忽略的 `.env` 注入。
- [x] 实现批量 Embedding HTTP 客户端，校验响应数量、顺序、1024 维度以及 NaN/Infinity，并将 dense vector 写入 ES。
- [x] Flyway V3 将旧文档重置为待索引，重新构建后同一 chunk 同时包含 content 和 embedding。
- [x] 实现查询文本向量化与 Elasticsearch kNN 检索接口。
- [x] 增加管理员向量检索测试页，可选择 Top 3/5/10/20，展示来源文档、chunk 序号、文本和 ES 相关分。
- [x] 查询使用与建库相同的 `BAAI/bge-m3` 和 1024 维向量，检索响应排除原始向量以减少传输体积。
- [x] 查询文本限制为 1 至 1,000 字符，返回数量限制为 1 至 20；患者访问检索接口返回 HTTP 403。
- [x] 增加 TXT、Markdown、DOCX 和 PDF 四种虚构医院规则测试资料及建议测试问题。
- [x] 增加通用 `ChatModelClient` 和 DeepSeek HTTP 实现，Embedding 与生成模型的 Key、地址和模型配置完全分离。
- [x] 增加管理员 `POST /api/agent/admin/knowledge/rag/ask` 接口，复用现有 BGE-M3 + ES 向量检索。
- [x] RAG 回答返回来源文档、chunk、相关分和实际送入模型的引用文本。
- [x] 返回 DeepSeek 的输入、输出与合计 Token 用量，便于观察成本。
- [x] 默认使用低成本 `deepseek-flash`，关闭思考模式，输出上限为 600 Token。
- [x] 增加最低相关分和 6,000 字符上下文上限；无可靠资料时不调用模型。
- [x] 系统提示要求只依据资料回答、标注引用，并将文档内容视为不可信数据以降低提示词注入风险。
- [x] DeepSeek Key 只从后端环境变量注入，不进入浏览器、日志、数据库、ES 或模型上下文。
- [x] 增加管理员 RAG 测试页面和管理首页入口，支持示例问题、Top K 选择、加载态及错误提示。
- [x] 页面展示回答、引用文档、实际上下文 chunk、相关分、模型名称、Token 用量和请求耗时。
- [x] RAG 页面默认使用真实联调效果更稳的 Top 3，同时保留 Top 1/5/8 供管理员比较召回质量。
- [x] 前端只以纯文本展示模型回答和引用内容，不直接渲染模型返回的 HTML。

## 管理员端当前边界

本版已经完成真实的文档上传、MinIO 保存、MySQL 文档元数据、列表、删除、文本预览、简单切块、BGE-M3 向量化、ES 持久化、管理员向量检索和管理员 RAG 前后端入口。`knowledge_document` 只保存上传成功且尚未删除的文档以及文档级索引结果；chunk 文本和向量只保存在 Elasticsearch。接口中的 `UPLOADED` 只表示原文件和元数据已保存，是否完成索引以 `indexed` 为准。管理员检索与 RAG 直接覆盖所有已成功写入 ES 的 chunk，本阶段不增加知识发布机制。

后续 RAG 阶段计划接入：

1. 使用测试文档对比关键词检索和向量检索，再决定是否加入混合检索与 reranker。
2. 把同一 `KnowledgeRagService` 接入患者问答入口，并设计患者侧更简洁的会话界面。
3. 患者 RAG 正式开放前，再评估是否需要知识发布控制；当前管理员测试不增加发布机制。
4. 结合真实问题调整最低相关分、Top K、切块策略，并评估混合检索与 reranker。
5. 文档量或处理时间明显增长后，再评估异步任务状态和消息队列；Redis 暂不引入。

## 本次验证记录

- 后端：`BUILD SUCCESS`，65 个测试，0 failures，0 errors；覆盖 PDF、DOCX、Markdown 解析、预览截断、简单切块、Embedding 客户端、索引服务、向量检索、DeepSeek 客户端、RAG 编排、本地开发认证与角色权限。
- 前端：TypeScript 检查和 Vite 生产构建成功，42 个模块完成转换。
- 患者登录：`patient_demo` 返回 `PATIENT`。
- 管理员登录：`staff_demo` 返回 `STAFF`。
- 患者会话接口：患者 Token 返回 HTTP 200，管理员 Token 返回 HTTP 403。
- 管理员会话接口：管理员 Token 返回 HTTP 200，患者 Token 返回 HTTP 403。
- 前端：患者页、管理员登录页、管理首页、向量检索页和 RAG 问答页均返回 HTTP 200。
- Docker Compose：前端、后端、Elasticsearch、MinIO 均为 `healthy`。
- Agent 后端：`GET /actuator/health` 返回 HTTP 200。
- Elasticsearch：`GET /_cluster/health` 返回 HTTP 200。
- MinIO API 与控制台均返回 HTTP 200，知识库 Bucket 已创建。
- 管理员经 Nginx → Gateway → Agent → MinIO 上传 Markdown 成功，列表可立即读取。
- 管理员可从页面触发即时解析预览和二次确认删除。
- 已完成真实的上传 → 解析（提取 3,372 字符）→ 列表校验 → 删除闭环；测试文档已从 MySQL 和 MinIO 清理。
- 已完成真实的上传 → 构建索引闭环：测试文档生成 4 个 chunk，API、MySQL 和 ES 的 chunk 数一致，最大 chunk 为 994 字符。
- 已验证重复构建会跳过；删除测试文档后，Elasticsearch 中该 documentId 的 chunk 数量为 0。
- 硅基流动 `BAAI/bge-m3` API 已完成真实连通测试：批量返回 2 个 1024 维归一化向量，数值均有效；API Key 已通过 `.env` 注入后端容器且未输出到日志。
- 已完成真实的上传 → 切块 → BGE-M3 → ES 闭环：测试文档生成 5 个 chunk，每个 chunk 均可取回 1024 维有效向量，模型字段为 `BAAI/bge-m3`，测试数据已清理。
- 已通过本地开发管理员 Token 完成 Nginx → Agent 身份校验，未启动医院 Gateway、Identity 或 Nacos。
- 已上传 TXT、Markdown、DOCX、PDF 四份医院规则样本并完成索引，每份当前生成 1 个 chunk，测试数据保留给页面继续使用。
- 已完成四组真实 BGE-M3 + ES kNN 检索：预约退费、住院探视、报告领取和夜间胸痛问题的 Top 1 均正确命中对应文档，相关分分别约为 0.8878、0.8760、0.8520 和 0.8514。
- 新版后端镜像已构建并启动为 healthy；管理员 RAG 请求已完成 Nginx → 权限校验 → BGE-M3 → ES → DeepSeek 真实闭环。预约退费问题返回 HTTP 200、耗时约 2.2 秒、使用 1,873 Token；住院探视问题返回 HTTP 200、耗时约 1.5 秒、使用 1,239 Token，两次回答均正确引用 Top 1 文档。
- 真实联调发现 Top 5 在最低相关分 `0.65` 时可能纳入无关资料（例如 Redis 测试文档得分约 `0.6567`）；当前答案未引用该资料，后续建议将管理员 RAG 默认 Top K 调为 3 或把最低相关分提高到约 `0.75`，再结合更多问题校准。
- Kibana 已停止以节省内存；需要检查 ES 时可单独执行 `docker compose --profile tools up -d kibana`。
- Flyway V1/V2 已在独立 `healthy_agent` 数据库创建元数据表并增加文档级索引字段。
- 真实上传后，同一 documentId 已同时在 MinIO、MySQL 和列表 API 中验证。
- 已验证单元级补偿路径：MinIO 失败不写 MySQL，MySQL 失败删除 MinIO 对象。
- 患者 Token 上传文档返回 HTTP 403；伪造 PDF 返回业务码 `40012`。
- 本轮联调产生的临时 MySQL 记录和 MinIO 对象均已清理，本地原文件未改动。
- 当前常驻容器总内存约 1.75 GiB，其中 Elasticsearch 约 1.25 GiB。
- 发布地址：`http://127.0.0.1:8090`。

## 安全约束

- Agent 不建立患者或管理员账号表，不保存密码。
- Agent 不自行签发或解析 JWT。
- Authorization 不写入日志、MySQL、Redis、ES 或模型上下文。
- 浏览器请求必须经过 Gateway；生产环境不得直接暴露 Agent 的 `8091` 端口。
- 患者接口与管理员接口采用不同 URL 命名空间，并在后端强制校验角色。
- MySQL 保存上传者 ID、SHA-256、对象路径和时间；正式的发布和删除审计将在后续阶段补齐。
