# Healthy Agent 开发进度

最后更新：2026-10-09

## 总览

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| 1. 患者登录闭环 | 已完成（本机版） | 云端单体真实账号登录、JWT 获取、Nginx 代理和本地身份透传均已联调 |
| 2. 管理员登录与权限骨架 | 已完成 | 复用 STAFF 账号，前后端双入口、路由隔离和知识库管理页面骨架已完成 |
| 2.1 本地开发认证 | 已完成 | 医院项目关闭时使用 Agent 内存 Token，保留 PATIENT/STAFF 隔离，可配置切回 Gateway |
| 3. 文档管理第一阶段 | 已完成 | 上传、列表、删除及 PDF/DOCX/TXT/Markdown 即时解析预览已实现 |
| 4. 基础对话接口 | 进行中 | 患者 RAG/Tool 统一响应、聊天页面和 20 轮窗口记忆已完成；SSE 待实现 |
| 5. 患者查询 Tools | 已完成基础版 | 科室、医生、单人/批量号源、我的预约与候补共 7 个只读 Tool 已联调 |
| 6. 写操作确认 | 已完成基础版 | 创建/取消预约及加入/取消/确认候补统一支持 actionId、确认/拒绝按钮、执行前复查和重复执行防护 |
| 7. RAG 知识库 | 进行中 | Vector/BM25 基线、最小 RRF、104 条含多正例的评测集、管理员测试、患者 RAG 及业务 Tool 路由已完成；阈值优化和正式知识开放策略待实现 |
| 8. Agent Docker 部署 | 已完成基础版 | 前后端、ES、MinIO 已独立编排；Kibana 按需启动 |

## 已完成：双角色登录

- [x] 创建独立的 `frontend` 和 `backend` 目录。
- [x] 前端复用 `POST /api/auth/login`，未复制账号密码逻辑。
- [x] 患者入口仅接受 `PATIENT`，管理员入口仅接受 `STAFF`。
- [x] Token 只保存在当前标签页的 `sessionStorage`。
- [x] 本机简化版由前端根据 Healthy 登录响应附加 `X-Auth-User-Id` 和 `X-Auth-Role`，后端暂不校验 JWT。
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

## 已完成：云端 Healthy 单体登录适配（仅本机开发）

- [x] Nginx 将 `/api/auth/**` 转发至可配置的 `HEALTHY_API_UPSTREAM`，本机无需启动原 Healthy 项目。
- [x] 患者和管理员登录页直接复用云端登录接口，并校验返回角色后进入各自页面。
- [x] 正式 JWT 保存在当前标签页，Agent 请求继续携带原始 `Authorization`，为后续 HTTP Tool 转发保留完整凭证。
- [x] Agent API 请求附带登录响应中的用户 ID 和角色，当前后端不解析或验签 Healthy JWT。
- [x] 修复云端反向代理 Host 转发，避免云服务器选择错误虚拟主机返回 502。
- [x] JWT、密码和 API Key 均不进入模型提示词、Tool 参数、MySQL、ES 或日志。
- [!] 当前身份头由浏览器控制，可被伪造；端口只绑定 `127.0.0.1`，禁止按此方案部署给多用户或公网使用。

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
- [x] 增加 TXT、Markdown、DOCX 和 PDF 共十二份虚构医院规则测试资料及建议测试问题，覆盖预约退费、探视陪护、报告领取、急诊分诊、签到过号、医保票据、检查改期、病历复印、儿童门诊、药房、体检和互联网复诊。
- [x] 增加通用 `ChatModelClient`，底层由 Spring AI 2.0.1 的 OpenAI 兼容客户端连接 DeepSeek；Embedding 与生成模型的 Key、地址和模型配置完全分离。
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

## 已完成：RAG 阶段 1/2——标注评测集与 BM25 基线

- [x] 参考 [Elasticsearch Hybrid Search](https://www.elastic.co/docs/solutions/search/hybrid-search)、[RRF](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reciprocal-rank-fusion) 和 [Ranking](https://www.elastic.co/docs/solutions/search/ranking) 官方实现；本阶段只借鉴“先分别建立 lexical/vector 基线，再进入融合”的模式，没有提前实现 RRF、Reranker，也没有增加 ChromaDB、LangGraph 或其他依赖。
- [x] 新增 `v1-2026-10-09` 固定检索评测集，共 40 条脱敏问题：36 条正例和 4 条无答案、隐私、提示注入及虚构事实负例。
- [x] 评测标注使用稳定文件名而不是运行时 UUID `documentId`，支持同一批文档重复上传到不同环境后继续评测。
- [x] 新增管理员 `POST /api/agent/admin/knowledge/evaluations/retrieval`，可对 `VECTOR` 或 `BM25` 计算文档级 Recall@K、Precision@K、MRR、nDCG@K、负例空召回率和逐问题结果；患者访问返回 HTTP 403。
- [x] 在现有 Elasticsearch `content` 字段实现独立 BM25 检索；BM25 不调用 Embedding API，Vector 继续使用 BGE-M3，两套原始分数不混用。
- [x] 管理员检索页面可选择 BGE-M3 Vector 或 Elasticsearch BM25，并能直接运行 40 条固定评测；患者 RAG 默认策略保持 Vector，本阶段不改变线上问答路径。
- [x] 新增 8 份虚构 Markdown 制度，将仓库测试资料由 4 份扩充到 12 份；已通过本地管理员接口上传并索引，本地知识库现有 13 份文档且全部已索引。
- [x] 真实 Top 5 基线：BM25 Recall `1.0000`、MRR `0.9583`、nDCG `0.9692`；Vector Recall `1.0000`、MRR `0.9861`、nDCG `0.9897`。两种策略 Precision 均为 `0.2000`，因为每题只标注 1 个正确文档且统一取 Top 5。
- [x] 4 条负例在两种策略下均有返回结果，负例空召回率均为 `0.0000`；已明确记录“检索有结果不代表有可靠答案”，本阶段未用少量负例硬编码关键词或全局阈值。
- [x] 基线环境、指标解释、限制和下一步决策记录在 `docs/RAG_RETRIEVAL_BASELINE_2026-10-09.md`。

## 已完成：RAG 阶段 3 最小 RRF

- [x] 延续 Elasticsearch 官方 RRF 的按排名融合模式，不直接相加 BM25 `_score` 与 Vector 相似度；没有增加第三方向量库、Reranker 或状态机依赖。
- [x] 新增 `HYBRID` 策略：BM25 与 BGE-M3 kNN 各取 50 个候选，以 `chunkId` 为融合单位，使用无权重 `1 / (60 + rank)` 累加分数，再返回请求的 Top K。
- [x] 同一 chunk 在单个分支重复出现时只计分一次；最终结果按 RRF 分数、最佳分支排名和 `chunkId` 确定性排序，并重新生成连续排名。
- [x] 管理员检索和固定评测页面支持 Vector、BM25、RRF 三种策略；Hybrid 分数明确显示为 RRF 分，避免误解为向量相似度。
- [x] 初始 13 文档、40 题、Top 5 结果：RRF Recall `1.0000`、Precision `0.2000`、MRR `0.9861`、nDCG `0.9897`、负例空召回率 `0.0000`。
- [x] 在初始 13 文档语料上，RRF 与 Vector 指标相同；该历史结论已由下方扩充语料后的复测结果补充，患者 RAG 仍暂时默认 Vector。

## 已完成：RAG 语料数量与复杂度扩充

- [x] 保持原有 40 条评测问题、Top 5 口径和 Vector/BM25/RRF 参数不变，没有增加 Top1/Top3 指标，确保本轮差异只来自语料变化。
- [x] 新增编号 13～24 共 12 份虚构 Markdown 制度资料，仓库测试资料由 12 份扩充为 24 份；本地知识库连同此前开发测试文档共有 25 份。
- [x] 新增资料覆盖住院结算、日间手术、标本补采、镇静检查、证明盖章、随访转诊、无障碍服务、长处方、冷链药品、特殊病区探视、专家停诊和病案寄递。
- [x] 新文档刻意包含与原语料相邻但不等价的业务术语、例外状态和处理边界，并移除“建议测试问题”，避免把评测问法直接写进检索语料。
- [x] 12 份资料已通过 Nginx → Agent → MinIO → Tika → BGE-M3 → Elasticsearch 真实闭环上传和索引，每份实际切分为 2 个 chunk；当前 25 份文档全部已索引，共 37 个 chunk。
- [x] 扩充后继续使用同一 40 题 Top 5 评测：BM25 MRR `0.9074`、nDCG `0.9312`；Vector MRR `0.9583`、nDCG `0.9692`；RRF MRR `0.9722`、nDCG `0.9795`。三者 Recall 和 Precision 仍为 `1.0000`、`0.2000`。
- [x] v1 阶段只把新增文档作为干扰项时，RRF 曾表现出排序收益；该历史结论已经由下方包含新增文档正例的 v2 结果取代。

## 已完成：RAG 评测集 v2 与 HitRate 指标

- [x] 参考 [Elasticsearch Ranking Evaluation](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/search-rank-eval) 的标准 Precision/Recall/MRR/DCG 定义，以及 [BEIR Metrics](https://github.com/beir-cellar/beir/wiki/Metrics-available) 的 Top-K Accuracy；没有引入第三方评测依赖。
- [x] 固定数据集升级为 `v2-2026-10-09`，从 40 条扩充到 80 条：72 条正例、8 条无答案或安全负例。
- [x] 为编号 13～24 的 12 份新增文档各补充 3 条正例，共 36 条；评测问题只存在独立 JSON 中，没有写回文档正文造成查询泄漏。
- [x] 保留标准 `Precision@K = Top K 中标注相关结果数 / K`，同时新增 `HitRate@K`，表示至少命中一个标注相关文档的正例问题占比；管理页面用 HitRate 替换 Precision 主卡，但 API 继续返回 Precision 供诊断和兼容。
- [x] 真实 80 题 Top 5：BM25 HitRate `1.0000`、MRR `0.9056`、nDCG `0.9296`；Vector 与 RRF HitRate `0.9861`、MRR `0.9514`、nDCG `0.9605`；三者负例空召回率均为 `0.0000`。
- [x] Vector 与 RRF 共同漏掉 `rag-057`。BM25 对目标 chunk 排第 1，Vector 对同一文档另一 chunk 排第 10，当前以 `chunkId` 融合的 RRF 将目标排第 6；这成为后续评估文档级聚合或单文档 chunk 限制的固定回归样本。

## 已完成：RAG 评测集 v3 多正例标注

- [x] 延续 Elasticsearch Rank Evaluation/BEIR 的 qrels 集合思路，没有为了多正例引入评测框架或新依赖；窄问题保留单一权威文档，只有确实需要联合多份制度的问题才标注多个相关文档。
- [x] 固定数据集升级为 `v3-2026-10-09`，共 104 条：72 条单文档问题、24 条跨制度多文档问题和 8 条无答案或安全负例。
- [x] 24 条跨制度问题分别标注 2～3 个相关文件，覆盖停诊退费、特殊病区探视、资料授权、儿童急诊、复诊检查、出院病历、镇静改期、手术退费、冷链配送、病案寄递等组合场景。
- [x] 数据集加载器允许多个相关文件，同时拒绝空文件名和重复文件名；自动化测试不再假设每个正例必须恰好对应一个文件。
- [x] 真实 104 题 Top 5：BM25 HitRate/Recall/Precision 为 `1.0000/0.9965/0.2542`，Vector 为 `0.9896/0.9792/0.2500`，RRF 为 `0.9896/0.9809/0.2500`。
- [x] 多正例使 HitRate 与 Recall 真正分离：三种策略都至少部分命中全部 24 条跨制度问题；BM25 完整召回 23/24，Vector 和 RRF 各完整召回 22/24。Precision 不再被单正例固定在约 `0.20`，但仍受候选人工判断池不完整影响。
- [x] 当前仍保持患者 RAG 默认 Vector；Hybrid 排序指标虽略高于 Vector，但多正例完整召回没有提高，尚不足以切换默认路径。

## 已完成：患者基础聊天页面

- [x] 增加患者专用 `POST /api/agent/patient/chat/messages` 接口，在只读业务 Tool 与复用 `KnowledgeRagService` 的固定 Top 3 RAG 之间路由。
- [x] 患者可以查看回答气泡、引用文档、实际 chunk、相关分、模型名称和 Token 用量。
- [x] 增加预约退费、住院探视、报告领取和急诊规则四个快捷问题。
- [x] 支持 Enter 发送、Shift+Enter 换行、新对话、自动滚动、加载动画和错误提示。
- [x] 页面以纯文本展示模型回答，聊天记录只保存在当前页面内，不写 MySQL、Redis 或浏览器长期存储。
- [x] 接入 Spring AI `MessageWindowChatMemory`，每个患者会话保留最近 40 条最终消息（约 20 轮问答），仅保存在 Agent 进程内存。
- [x] 前端为每个聊天页生成 UUID `conversationId`；后端再按 `patient:{userId}:{conversationId}` 隔离，点击“新对话”会切换记忆空间。
- [x] 前端聊天气泡与后端 Memory 使用相同的 40 条消息窗口；开始对话后移除不属于模型上下文的欢迎语，超出窗口的旧气泡不再展示。
- [x] 前端遇到无效 `conversationId` 时会创建新会话、清除不再属于后端上下文的旧气泡，并安全重试当前只读聊天请求一次。
- [x] 已支持查询类 Tool；创建和取消预约只有确认准备工具，真正写操作仍由独立确认接口执行。
- [x] 患者接口只允许 `PATIENT`，管理员访问返回 HTTP 403。

## 已完成：患者只读 Tool Calling 基础版

- [x] 增加 `list_departments`、`search_doctors`、`get_doctor_detail`、`list_schedule_slots`、`list_doctors_schedule_slots`、`list_my_appointments`、`list_my_waitlists` 七个只读工具。
- [x] 全院号源查询改为先一次获取医生，再将最多 30 个医生 ID 交给批量号源 Tool；模型无需逐个医生调用，批量结果会过滤无余号班次并保留部分失败信息。
- [x] 增加 `search_hospital_policy` 能力入口，使同一聊天接口可由模型选择实时业务查询或现有 RAG。
- [x] 使用固定 Tool 注册表维护 HTTP 方法和业务路径，模型只能生成经过 JSON Schema 约束的业务参数，不能发起任意 HTTP 请求。
- [x] 医生分页参数、正整数 ID、日期格式及号源最多 14 天范围均由后端再次校验，不信任模型参数。
- [x] 增加独立 `HealthyApiClient`，连接云端 Healthy 单体并统一转换成功数据、鉴权失败、业务错误、限流和依赖不可用结果。
- [x] 当前请求的 Authorization 仅由 Agent 后端转发给 Healthy；不写日志、不进入 Tool 参数、不发送给 DeepSeek。
- [x] 迁移到 Spring AI 2.0.1 `ChatClient + ToolCallingAdvisor`，由框架驱动标准 Tool Calling 循环；业务代码不再自行解析 `tool_calls` 和拼接中间消息。
- [x] 通过 Spring AI 配置限制单工具最多 30 次、单请求总共最多 60 次 Tool 调用，并支持环境变量调整；全院号源查询默认一次获取最多 100 位医生，再用一次批量 Tool 查询其中最多 30 位候选医生的号源。
- [x] 当前请求的 JWT 和 userId 只通过 Spring AI `ToolContext` 传给后端工具，不进入模型提示词、JSON Schema 或 ChatMemory。
- [x] 前端展示本次回答属于“实时业务查询”还是“知识库回答”，并展示实际调用的 Tool 名称。
- [x] Compose 增加 Healthy API 地址和超时配置，地址可通过 `.env` 修改，不硬编码进业务实现。
- [x] 修复中文医生关键词被 URI 构建器与 HTTP 客户端重复编码的问题；查询参数现在由 HTTP 客户端统一安全编码一次。

## 已完成：取消预约 HITL 基础版

- [x] 增加 `prepare_cancel_appointment`，模型只能准备操作，不能直接调用取消预约接口。
- [x] 模型通常先调用 `list_my_appointments` 选择目标；`prepare_cancel_appointment` 无论是否利用会话上下文，都会重新请求 Healthy 并核验预约属于当前患者且仍为 `BOOKED`。
- [x] 查询预约后由统一 `AgentStateService` 保存 30 分钟结构化 ResultSet；“取消第三条”、日期、医生、科室和时段均通过同一 Candidate 选择器映射真实 appointmentId，不再使用取消预约专用序号旁路，也不让模型猜 ID。
- [x] 后端生成随机 `actionId`，将待确认动作绑定当前患者并仅在进程内存保存 10 分钟；不保存 Authorization。
- [x] 患者聊天页面展示医生、科室、日期、时段、时间、预约号和有效期，并提供“确认取消”与“暂不取消”按钮。
- [x] 增加独立确认和拒绝接口，不把聊天文本“确认”当作授权。
- [x] 确认时重新查询当前患者预约，只有仍为 `BOOKED` 才调用固定的 Healthy 取消接口。
- [x] 同一个动作只允许从 `PENDING` 原子转换一次；重复确认、越权确认和过期确认均拒绝执行。
- [x] 放弃动作不会调用医院写接口；Agent 重启会清空尚未确认动作，符合当前无 Redis/MySQL 的简化设计。
- [x] 创建预约使用由 Agent 生成、跨网络重试复用的 `requestId`；候补写操作没有调用方幂等键，不自动重放，网络结果不明确时只反查最新候补状态。
- [x] 取消对自然语言取消请求强制指定 `prepare_cancel_appointment`；改由工具描述和系统规则要求模型在没有真实 appointmentId 时先调用 `list_my_appointments`，只能原样使用查询结果中的 id，存在多个候选时先询问用户，不得从日期、预约号或其他数字猜测 ID。

## 已完成：患者写操作 HITL 通用骨架

- [x] 将写操作拆为“通用审批编排器 + 类型化 Handler 注册表 + 业务 Handler”。编排器只负责 `PENDING → EXECUTING → SUCCEEDED/FAILED` 状态流转，具体校验与 HTTP 写操作由创建/取消预约 Handler 负责。
- [x] 待确认动作改为保存类型化 `PatientActionPayload` 和通用 `PatientActionPreview`；payload 仅留在 Agent 后端，前端不会获得真实执行参数，只接收标题、说明、按钮文案和展示字段。
- [x] 前端确认卡改为按 `preview.fields` 动态渲染，不再写死医生、科室、时间等取消预约专用字段；后续写操作可以复用同一套确认/拒绝接口和卡片。
- [x] Handler 注册表在启动时拒绝同一动作类型被重复注册；未注册动作和 payload 类型不匹配均采用 fail-closed，不会降级为直接执行。
- [x] 当前注册 `CreateAppointmentActionHandler`、`CancelAppointmentActionHandler`、`JoinWaitlistActionHandler`、`CancelWaitlistActionHandler` 和 `ConfirmWaitlistActionHandler`；五种患者写操作全部复用同一审批编排器，但各自保留独立实时校验和固定 HTTP Handler。
- [x] 参考 [Spring AI Playground Agent Loop](https://github.com/spring-ai-community/spring-ai-playground/blob/main/docs/agent-loop-architecture.md) 与 [HITL](https://github.com/spring-ai-community/spring-ai-playground/blob/main/docs/hitl-architecture.md) 官方示例：借鉴集中审批门和按工具分派策略，不引入其完整运行时、持久化 Checkpointer 或新的框架依赖；同时参考 [LangGraph interrupt](https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/types.py) 的“保存精确动作参数，恢复后执行同一动作”原则，本版继续使用现有内存 Store。

## 已完成：创建预约 HITL 基础版

- [x] 增加无参数 `prepare_create_appointment`；它只读取 Java 已从 AgentState 唯一选定的号源，不允许模型提交 `scheduleSlotId`、`requestId`、患者 ID、医生 ID 或号源日期。
- [x] 创建准备工具接受最近多个 `list_schedule_slots` / `list_doctors_schedule_slots` ResultSet 中经统一选择流程确定的 Candidate；doctorId 和 scheduleDate 由后端结构化 State 恢复，无法凭猜测数字生成确认卡。
- [x] `CreateAppointmentActionHandler.prepare` 重新查询医生详情和指定日期号源，只有目标班次仍存在且 `remainingCapacity > 0` 才生成通用确认卡。
- [x] 创建待确认动作时由 Agent 生成 UUID `requestId`，仅保存在后端 payload；不会返回前端、进入模型上下文或由用户提供。
- [x] 用户点击确认后再次查询号源；仍有余号才调用 `POST /api/user/appointments`。若 POST 返回可重试的网络/限流错误，仅自动重试一次，并严格复用原 `requestId`。
- [x] 前端复用通用确认卡和既有确认/拒绝接口，无需增加创建预约专用页面或确认接口。
- [x] 继续采用 Spring AI Playground 的“审批精确调用、拒绝或超时则 fail-closed”模式；没有引入新框架、数据库表或 Redis。

## 已完成：候补写操作 HITL 基础版

- [x] 新增无业务参数的 `prepare_join_waitlist`、`prepare_cancel_waitlist`、`prepare_confirm_waitlist`；模型只能触发准备流程，`scheduleSlotId` 和 `waitlistId` 只能由 Java 从 `SCHEDULE_SLOT` / `WAITLIST` Candidate 读取。
- [x] 加入候补只接受余号为 0 的真实班次；准备确认卡和按钮确认时都会重新查询医生当日号源，确认班次仍开放、未结束且没有公开余号后才调用 `POST /api/user/waitlists`。
- [x] 取消候补只接受 `WAITING` Candidate；准备和执行前均重新调用 `GET /api/user/waitlists` 校验归属与状态，再调用固定 `PATCH /api/user/waitlists/{id}/cancel`。
- [x] 确认候补只接受 `OFFERED` Candidate；以 Healthy 返回的 `offerExpireTime` 判断是否可确认，确认卡有效期取“10 分钟”和服务端剩余时间中的较早者，执行前再次校验后调用固定 `POST /api/user/waitlists/{id}/confirm`。
- [x] 确认候补成功的服务端语义是同一事务内把候补改为 `CONFIRMED` 并创建 `BOOKED` 预约；该终态写入 `lastActionResult`，后续模型不得称为未执行。
- [x] 三个候补写接口均没有客户端幂等键，因此网络超时或 503 时不自动重放写请求；Agent 只查询最新候补列表，状态已经变化则确认成功，否则明确提示结果暂时无法确认并要求重新查询。
- [x] 候补 ResultSet 现在显示中文状态、门诊时间和 `offerExpireTime`，并把 `OFFERED`、`WAITING` 排在历史终态之前，便于继续统一选择。
- [x] 继续复用 Spring AI Playground 集中审批门与 LangGraph“冻结精确动作参数、恢复时执行同一参数”的模式；没有引入新的框架依赖、数据库表或 Redis。

## 已完成：患者会话级 AgentState 统一重构

- [x] 新增 `AgentStateService`，以服务端作用域键 `patient:{userId}:{conversationId}` 管理普通 Java `AgentState`；第一版继续使用 `ConcurrentHashMap`、30 分钟惰性 TTL 和最多 8 个最近 ResultSet，没有引入 Redis、数据库 Checkpoint、LangGraph 或状态机框架。
- [x] `AgentState` 只保存后续轮次仍可能引用的业务数据：`currentResultSetId`、`recentResultSets`、`selectedCandidate`、当前 `pendingAction`、`lastActionResult` 和 `expiresAt`。所有 Map 访问集中在 Service，Controller、ChatService 和 Tool 不直接持有状态 Map。
- [x] 七个患者业务只读 Tool 均已扫描并接入统一结果转换：科室、医生、医生详情、单医生号源、批量医生号源、我的预约、我的候补分别生成 `DEPARTMENT`、`DOCTOR`、`SCHEDULE_SLOT`、`APPOINTMENT` 或 `WAITLIST` ResultSet。
- [x] `search_hospital_policy` 属于制度/科普 RAG，不产生可供后续业务写操作选择的真实对象，因此不写 AgentState；ChatMemory 仍只负责最近约 20 轮自然语言上下文。
- [x] 每次业务查询追加新 ResultSet 并更新 `currentResultSetId`，不覆盖最近历史；ResultSet 描述同时包含日期范围和本轮用户查询摘要，使模型能把“刚才这周”解析为旧 ResultSet ID，而 Java 再从该 ResultSet 确定真实业务 ID。
- [x] 新增统一 `select_patient_candidate`：模型只表达 ResultSet、序号、类型、医生、科室、日期、时段和状态；Java 负责零条、唯一、多条三态匹配。多条匹配会生成新的缩小 ResultSet并要求用户继续选择，模型不得自行任选。
- [x] 医生详情、单医生号源和批量号源的模型 Schema 不再接受 `doctorId` / `doctorIds` / `departmentId`；服务端适配器只从 AgentState 中的可信 Candidate 展开内部 ID。创建/取消准备 Tool 均为空参数 Schema。
- [x] 删除 `PatientAppointmentContextStore`、`PatientScheduleSlotReference` 和“取消第 N 条”专用正则旁路；预约、号源、医生、科室与候补现在使用同一 ResultSet/Candidate 机制。
- [x] 创建预约统一为 `SCHEDULE_SLOT ResultSet → selectedCandidate → PendingAction → 按钮确认 → 重新查询实时号源 → POST`；是否等待确认直接由 `pendingAction != null` 判断，`scheduleSlotId`、`doctorId`、日期和服务端生成的 `requestId` 冻结在 PendingAction payload 中。
- [x] 取消预约统一为 `APPOINTMENT ResultSet → 按 BOOKED/日期/医生/科室/时段/序号选择 → PendingAction → 按钮确认 → 重新查询归属与状态 → PATCH`；`appointmentId` 冻结后不再交给模型重选。
- [x] PendingAction 增加 `conversationId` 绑定；确认/拒绝接口同时校验 actionId、userId、conversationId、状态和过期时间。只有当前仍为 `PENDING` 时，重复准备才复用原 actionId；`REJECTED`、`EXPIRED`、`FAILED`、`SUCCEEDED` 都是不可恢复终态，再次明确请求必须重新校验并生成具有新 actionId 的新卡。
- [x] 文本“确认”只返回原确认卡并提示点击按钮；“算了”等明确放弃表达由服务端真正把当前 Action 转为 `REJECTED`。动作终态会清除 pending/selection，但保留最近 ResultSet。
- [x] `AgentState` 提示摘要现在始终显式输出 `pendingAction=none` 或活动卡状态；系统规则和五个 prepare Tool 描述共同声明：ChatMemory 中出现过旧确认文本不代表旧卡仍有效，终态旧卡永久不可再次确认。前端保留旧终态卡作为历史记录，新请求追加新的 `PENDING` 卡，不复活旧卡。
- [x] 前端确认与拒绝请求携带当前 conversationId；收到相同 actionId 时更新原卡而不是追加重复卡，并能用 `actionUpdate` 同步聊天中触发的放弃结果。
- [x] 确认卡的医生、科室、日期、时段等非敏感预览字段同时写入助手文本和 ChatMemory；前端仍渲染结构化卡片。这样后续“上文提到的那一条”可以用预览字段匹配 ResultSet，而 actionId、scheduleSlotId、appointmentId 和 requestId 不进入模型文本。
- [x] `AgentState.lastActionResult` 保存最近一次按钮确认/拒绝的服务端终态及非敏感预览。确认接口成功后即使没有新的聊天消息，下一轮也能看到 `CREATE_APPOINTMENT + SUCCEEDED`；该终态优先于确认前的“尚未执行”文本，用户随后说“不想挂了”时只能进入取消流程，不能声称预约未创建。
- [x] 参考 [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html) 的 conversation-id 隔离，以及 [Spring AI Tools / ToolContext](https://docs.spring.io/spring-ai/reference/api/tools.html) 将请求级安全上下文与模型参数分离的做法；本项目只把自然语言留给 ChatMemory，把真实业务引用放入独立 AgentState。
- [x] 同时参考 [LangGraph Persistence](https://langchain-ai.github.io/langgraph/concepts/persistence/) 的 thread-scoped state/checkpoint 思路和 HITL 恢复同一动作参数的原则；本项目没有照搬图运行时、Reducer、持久化 Checkpointer 或依赖，只保留会话作用域 State 和冻结 PendingAction 这两个适用模式。
- [x] 终态重试语义继续参考 [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html) 的“应用拥有 Tool 执行与审批门”原则，以及 [LangGraph HITL Interrupts](https://langchain-ai.github.io/langgraph/how-tos/create-react-agent-hitl/) 将一次人工决策绑定到一次具体 interrupt 的模式；本项目没有引入自定义 ToolAdvisor、图运行时或 Checkpointer，而是在现有 PendingAction 上实现不可变 Action Attempt。

## 已完成：AgentState 最小业务数据重构

- [x] 删除持久化 `AgentPhase` 和 `AgentTaskType`。查询、RAG、闲聊属于当前轮行为，不再以 `QUERY → NONE` 等形式写入会话 State，也不用于长期意图路由。
- [x] 普通聊天和知识问答只读取必要的 State 摘要，不改变 ResultSet、选择对象或 PendingAction；“查询下周医生 → 无关问答 → 刚才第二个医生”仍可从原 ResultSet 确定真实业务 ID。
- [x] 查询只追加 ResultSet并移动 `currentResultSetId`；唯一选择只更新 `selectedCandidate`；写操作准备只保存自带 `PatientActionType` 和状态的 PendingAction，避免同一业务含义在 `activeTask`、`phase`、PendingAction 三处重复。
- [x] 等待确认直接由 `pendingAction != null` 判断。确认、拒绝、失败或过期后清理 PendingAction和选择对象，历史 ResultSet继续保留。
- [x] 借鉴 Spring AI Alibaba Graph 与 LangGraph 的“字段独立、节点只提交局部更新”模式，以及 Dify 会话变量与单次运行变量分离的思路；没有引入图框架、Reducer、Checkpoint、Redis或数据库依赖。

## 已完成：患者五类意图路由与动态 Tool 白名单

- [x] 新增无状态 `PatientIntentRouter`，每轮在主 Patient Agent 之前进行一次短分类，输出可多选的 `CHAT`、`QUERY`、`RAG`、`WRITE` 或安全回退 `ALL`。路由结果是本轮执行策略，不写入 AgentState，也不会把一次查询误变成长期 `activeTask`。
- [x] 路由器使用独立的 `statelessChatClient`、最多最近 6 条消息和精简业务 State 摘要；主 Agent 仍使用原有约 20 轮 ChatMemory。路由摘要只含 ResultSet 类型、描述、数量、选择展示文本和动作状态，不包含 doctorId、appointmentId、scheduleSlotId、waitlistId 或 resultSetId。
- [x] 不采用未经校准的数字“置信度”。路由模型显式返回 `uncertain`；Java 对 JSON、路由名称和组合关系做确定性校验。模型明确不确定、输出非法、`CHAT` 与工具路由冲突、超时或调用异常时统一回退 `ALL`。
- [x] 多意图直接取工具组并集，不因多意图自动回退。例如“查询下周一号源，没有号就加入候补”路由为 `QUERY + WRITE`；真正不清楚“刚才那个”所指且上下文不足时才使用 `ALL`。
- [x] `CHAT` 不注册 Tool；`QUERY` 注册 7 个业务只读 Tool 与 `select_patient_candidate`；`RAG` 只注册 `search_hospital_policy`；`WRITE` 注册查询/选择前置工具与 5 个仅生成确认卡的 prepare Tool；`ALL` 注册当前全部 14 个安全模型工具。
- [x] 窄路由使用显式 Tool 名称白名单并默认拒绝未知工具；未来新增 Tool 必须明确归组，避免仅靠“不是 RAG 就当 QUERY”的反向判断扩大权限。
- [x] `ALL` 的边界严格限定为 `PatientSpringAiTools.callbacks()` 中的安全工具。按钮确认/拒绝 REST 接口、ActionHandler、HealthyApiClient 和真实医院写接口永远不会暴露给模型；写入仍必须经过 PendingAction、HITL 按钮确认和实时复核。
- [x] `PatientChatService` 根据路由结果按请求调用 Spring AI `.tools(...)`；`CHAT` 时完全不传工具定义。路由调用的输入/输出 Token 合并进患者响应的总 Token，避免只显示主 Agent 消耗而低估真实成本。
- [x] 参考 [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html) 的按请求 Tool 注册能力，以及 Dify Question Classifier、LangGraph 条件分支的“分类后进入受限分支、无法判断时走保守路径”模式。只借鉴路由和回退原则，没有引入 Dify、LangGraph、额外状态机或新基础设施依赖。

## 已完成：患者聊天 SSE 流式输出与输入框焦点修复

- [x] 新增 `POST /api/agent/patient/chat/messages/stream`，以 UTF-8 `text/event-stream` 返回 `delta`、`complete`、`error` 三类事件；原 `/messages` JSON 接口继续保留，避免破坏现有调用方。
- [x] 主 Agent 使用 Spring AI 2.0.1 原生 `ChatClient.stream().chatResponse()`。继续复用框架的 `ToolCallingAdvisor`、ChatMemory Advisor、动态 Tool 白名单和 ToolContext，没有自行重写 Tool Calling 循环。
- [x] 只有纯 `CHAT` 展示模型真实增量。`QUERY`、`WRITE`、`RAG`、复合路由和回退 `ALL` 可能执行多步 Tool 链，因此不发布执行过程中不断变化的 `directAnswer`；整条链完成后只发送一次最终 Tool/RAG 文本，再用 `complete` 返回引用、Token、模型、Tool 轨迹、PendingAction 和 actionUpdate。这样不会先闪现预约列表，随后又被取消确认卡覆盖，也不通过拆字和延迟制造“假流式”。
- [x] 流中断统一转换为不泄露异常细节的业务错误事件；路由 Token 与主模型 Token 仍合并统计。Spring MVC 异步超时和 Nginx SSE 读取超时设置为 180 秒，SSE 路径关闭代理缓冲和 gzip。
- [x] 前端用 `fetch + ReadableStream` 解析 POST SSE，不依赖只能 GET 的 `EventSource`；消息气泡随增量更新并显示流式光标，最终事件再补齐引用、Token、工具标签和确认卡。
- [x] 发送期间不再禁用 textarea，发送按钮和重复提交仍受 `sending` 保护；发送后、请求结束后、确认/拒绝动作后以及新建对话后都会通过模板 ref 重新聚焦。用户等待回答时可以继续输入下一条内容。
- [x] 参考 [Spring AI ChatClient Streaming](https://docs.spring.io/spring-ai/reference/api/chatclient.html#_streaming_responses) 和 [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html) 的原生流式工具循环；没有采用手写 Tool 循环或按字符定时输出。

## 工程实现约定

- Agent 架构、Tool Calling、HITL、RAG、Memory、权限和可靠性的重要修改，实施前先参考维护活跃的优秀开源项目或框架官方实现。
- 优先借鉴与 Java、Spring Boot、Spring AI 接近的实现，并说明借鉴点与本项目的简化取舍；不为照搬开源方案而无必要地引入复杂组件。

## 管理员端当前边界

本版已经完成真实的文档上传、MinIO 保存、MySQL 文档元数据、列表、删除、文本预览、简单切块、BGE-M3 向量化、ES 持久化、管理员向量检索和管理员 RAG 前后端入口。`knowledge_document` 只保存上传成功且尚未删除的文档以及文档级索引结果；chunk 文本和向量只保存在 Elasticsearch。接口中的 `UPLOADED` 只表示原文件和元数据已保存，是否完成索引以 `indexed` 为准。管理员检索与 RAG 直接覆盖所有已成功写入 ES 的 chunk，本阶段不增加知识发布机制。

后续 RAG 阶段计划接入：

1. 继续补充三段以上的长制度和相似制度资料；当前保持 Top 5 评测口径，不增加 Top1/Top3 指标。
2. 如果两次 Elasticsearch 查询的延迟或候选量成为问题，再评估原生单请求 RRF；当前不为尚未出现的性能问题增加 Low Level REST 实现。
3. 结合负例校准最低相关分和无答案判定，再决定是否加入 reranker 或 Query Rewrite。
4. 患者 RAG 正式开放前，再评估是否需要知识发布控制；当前测试不增加发布机制。
5. 文档量或处理时间明显增长后，再评估异步任务状态和消息队列；Redis 暂不引入。

## 本次验证记录

- HITL 终态卡重试新增 2 项回归后，全量后端 153 项测试通过，0 failures、0 errors。定向 36 项测试覆盖 `pendingAction=none` 明示、五个 prepare Tool 的终态规则、PENDING 重试复用 actionId、REJECTED 后重新校验并生成不同 actionId、旧 actionId 不可确认；全部使用 Mock/内存数据，没有调用真实医院取消或其他写接口。
- HITL 终态重试版本已重新构建并替换 `healthy-agent-backend`；容器状态为 `healthy`，`GET /actuator/health` 返回 `UP`。本次未改动或重启前端，也没有调用任何医院查询或写接口。
- 患者 SSE 流式输出新增 4 项后端回归后，全量后端 151 项测试通过，0 failures、0 errors；前端 `vue-tsc --noEmit` 与 Vite 生产构建成功（44 个模块）。测试覆盖纯 CHAT 增量事件、最终元数据、Token 汇总、流异常安全映射、UTF-8 SSE Controller 输出，以及“Tool 链先查询全部预约、再生成取消确认卡”时不向前端发布中间预约列表。
- 流式版本已重新构建并替换 Agent 前后端容器，两者均为 `healthy`，后端健康接口返回 `UP`、前端返回 HTTP 200。通过 `http://127.0.0.1:8090` 完成一次无业务 Tool 的真实 SSE 短问答：收到多个增量事件和唯一 complete 事件，首个事件约 1.07 秒到达、总耗时约 1.42 秒；耗时仅为本次开发环境样本，不作为性能承诺。本次没有调用医院写接口。
- 修复 Tool 链中间预约列表被最终取消确认卡覆盖的问题后，已重新构建并替换 `healthy-agent-backend`；容器为 `healthy`，健康接口返回 `UP`。使用隔离测试用户完成纯 CHAT SSE 冒烟，收到最终文本和唯一 `complete` 事件；未调用查询预约、取消预约或其他医院业务接口。
- 五类意图路由版本已重新构建并替换 `healthy-agent-backend` 容器；容器状态为 `healthy`，`http://127.0.0.1:8091/actuator/health` 返回 `UP`。本次只重启 Agent 后端，没有重启前端、Elasticsearch 或 MinIO。
- 五类意图路由定向测试 25 项通过；后端执行 `mvn -pl backend clean test` 共 147 项通过，0 failures、0 errors。新增覆盖复合 `QUERY + WRITE`、不确定/非法/冲突输出回退 ALL、路由调用失败回退、最小上下文、路由 State 不含内部 ID、五组 Tool 精确白名单、未知 Tool 在窄路由下默认拒绝、CHAT 零 Tool、QUERY 按请求注入及路由 Token 合并统计；全部使用 Mock/内存数据，没有调用真实医院写接口。
- AgentState 最小化重构定向回归 22 项通过；后端全量 135 项测试通过，0 failures、0 errors。新增覆盖“业务查询产生 ResultSet → 中间进行无关普通问答 → 原 ResultSet 仍可引用”，并验证 State 摘要不再包含 `phase` 或 `activeTask`。测试全部使用 Mock/内存数据，没有执行任何真实医院写操作。
- 后端：`BUILD SUCCESS`，133 个测试，0 failures，0 errors；新增覆盖固定评测集加载与校验、BM25 不调用 Embedding、Vector/BM25/HYBRID 策略选择、RRF 跨分支融合与单分支去重、HitRate 与其他检索指标计算和管理员评测接口权限；原有 AgentState、Tool Calling、HITL、RAG、文档管理与权限回归继续通过。
- 前端：TypeScript 检查和 Vite 生产构建成功，44 个模块完成转换。
- RAG 阶段 1/2 与最小 RRF 版本已重建并替换前后端容器；后端健康检查返回 `UP`，管理端通过同一页面完成 Vector/BM25/HYBRID 单题检索和固定评测。
- 编号 05～24 的 20 份新增虚构制度均已通过 Nginx → Agent → MinIO → BGE-M3 → Elasticsearch 真实闭环上传和索引；当前知识库 25 份文档、37 个 chunk 全部已索引。
- 104 条真实检索评测未调用聊天生成模型，因此没有产生回答 Token；Vector 与 Hybrid 评测调用 BGE-M3 查询 Embedding，BM25 评测只访问 Elasticsearch。
- 本轮统一 State 回归全部使用 Mock/本地测试数据，没有创建或取消任何云端真实预约。
- 针对页面截图中的回归完成运行态核对：截图请求来自 19:38 构建的旧容器，调用轨迹缺少 `select_patient_candidate`。已于 21:20 重建并仅重启 Agent 前后端；后端 `/actuator/health` 返回 HTTP 200，本地开发会话验证无 PendingAction 时输入“算了”由 `server-rule` 零 Tool 返回，不再生成“这次不预约”的模型回答。
- 第二张截图中的“服务暂时不可用”定位为旧浏览器 JS 对新版确认接口发送了空请求体，并非 Healthy API 或号源异常，确认逻辑尚未开始、没有发生预约写入。确认/拒绝接口现在把缺少 conversationId 映射为 HTTP 400 / `40018` 明确会话错误，不再误报 HTTP 500；刷新页面后新版前端会发送 `{conversationId}`。
- 第三张截图中的“卡片已完成但助手声称未执行”定位为按钮确认结果只更新了前端卡片、没有进入后端会话 State。现已在确认/拒绝终态原子回写 `lastActionResult`，下一轮系统上下文明确包含动作类型、`SUCCEEDED/REJECTED/FAILED` 和非敏感预览；测试同时验证 actionId 和业务内部 ID 不进入模型提示词。
- 终态回写修复已于 21:34 重建并替换 `healthy-agent-backend`，容器状态为 `healthy`，`GET /actuator/health` 返回 HTTP 200；部署与测试均未触发新的云端预约或取消操作。
- 候补三种写操作新增 9 项回归后，全量后端 122 项测试通过；前端 TypeScript 检查和 Vite 构建通过（44 个模块）。测试全部使用 Mock/本地 HTTP Server，没有加入、取消或确认任何真实云端候补，也没有由候补确认创建真实预约。
- 候补写操作版本已于 21:59 重建并替换 Agent 前后端容器；后端健康接口返回 `UP`，`http://127.0.0.1:8090/` 返回 HTTP 200，前后端容器均为 `healthy`。
- 云端患者登录：经 `http://127.0.0.1:8090/api/auth/login` 使用演示账号返回 `PATIENT` 和正式 JWT，Token 内容未输出。
- 云端管理员登录：同一路径使用演示账号返回 `STAFF` 和正式 JWT，Token 内容未输出。
- Nginx 云端代理：`/api/health` 返回 `UP`；患者、管理员登录结果中的 userId/role 透传到 Agent 后与原结果一致。
- 患者会话接口：患者 Token 返回 HTTP 200，管理员 Token 返回 HTTP 403。
- 管理员会话接口：管理员 Token 返回 HTTP 200，患者 Token 返回 HTTP 403。
- 前端：患者聊天页、管理员登录页、管理首页、向量检索页和 RAG 问答页均返回 HTTP 200。
- Docker Compose：前端、后端、Elasticsearch、MinIO 均为 `healthy`。
- Agent 后端：`GET /actuator/health` 返回 HTTP 200。
- Elasticsearch：`GET /_cluster/health` 返回 HTTP 200。
- MinIO API 与控制台均返回 HTTP 200，知识库 Bucket 已创建。
- 管理员经 Nginx → Agent → MinIO 上传 Markdown 成功，列表可立即读取；当时使用本地开发管理员身份。
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
- 患者聊天已完成 Nginx → 患者鉴权 → BGE-M3 → ES → DeepSeek 真实闭环：探视问题返回 HTTP 200、约 1.8 秒、1,239 Token 和 3 个引用片段；管理员 Token 调用患者聊天接口返回 HTTP 403。
- 患者只读 Tool 已完成真实联调：查询预约调用 `list_my_appointments`，查询科室调用 `list_departments`，两次均完成 Nginx → Agent → Healthy API → DeepSeek 闭环且返回 HTTP 200；规则问题选择 RAG 并返回 3 个引用片段。
- 取消预约 HITL 已完成安全联调：云端演示患者存在 1 条 `BOOKED` 预约时，聊天返回 `CONFIRMATION_REQUIRED` 和确认卡，工具链为 `list_my_appointments → prepare_cancel_appointment`；本次选择“暂不取消”后状态为 `REJECTED`，重复决策返回 HTTP 409，未调用真实取消接口。
- Spring AI Memory 已完成真实两轮联调：同一 `conversationId` 首轮调用 `list_my_appointments`，次轮仅输入“取消第一条”即可从最近对话确定目标，并只调用 `prepare_cancel_appointment` 生成确认卡；随后已拒绝该动作，真实预约未取消。
- 修复确认取消时报“医院业务服务暂时不可用”：原因为 `SimpleClientHttpRequestFactory` 底层 `HttpURLConnection` 不支持 PATCH；已切换到 Java 21 `HttpClient`，并通过真实本地 HTTP Server 验证 PATCH 方法和 Authorization 转发。
- 中文医生名查询已完成回归：问题“陈书宁医生是哪个科室的”调用 `search_doctors`，返回“消化内科”；修复前因关键词被重复 URI 编码而误报未查询到。
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
- 除显式 `local-dev` 测试 Token 外，Agent 不自行签发或解析 Healthy JWT。
- Authorization 不写入日志、MySQL、Redis、ES 或模型上下文。
- 当前云端登录适配仅限绑定 `127.0.0.1` 的单人开发环境；浏览器提供的身份头不可信，不能作为生产鉴权。
- 生产环境不得直接暴露 Agent 的 `8091` 端口，并必须由受信任后端验签 JWT 或由 Gateway 注入身份。
- 患者接口与管理员接口采用不同 URL 命名空间；当前仅做角色路由检查，不应误称为安全的生产身份验证。
- MySQL 保存上传者 ID、SHA-256、对象路径和时间；正式的发布和删除审计将在后续阶段补齐。
