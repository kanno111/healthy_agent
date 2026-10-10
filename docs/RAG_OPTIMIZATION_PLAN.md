# Healthy Agent RAG 与检索优化规划

> 状态：阶段 1、阶段 2 和阶段 3 最小 RRF 已于 2026-10-09 完成；阶段 4 及以后尚未实施
>
> 创建日期：2026-10-09
>
> 适用范围：患者端医院知识问答、管理员 RAG 测试、检索评测，以及与 Patient Agent Tool Calling 相关的 Token 优化

## 1. 背景与当前基线

当前项目已经完成：

- 使用 `BAAI/bge-m3` 生成 1024 维向量。
- 使用 Elasticsearch 9.5.4 保存文档 chunk 和向量，并执行 kNN 向量检索。
- 管理员端和患者端均已接入 DeepSeek RAG 问答。
- RAG 响应能够展示来源文档、chunk、相关分、Token 用量和请求耗时。
- 当前默认 Top K 为 3，设置了最低相关分和约 6,000 字符的上下文上限。
- 已验证若干测试问题的 Top 1 命中，但尚未建立足够大的人工标注评测集。
- 真实联调发现：Top 5、最低相关分为 `0.65` 时，可能召回得分接近阈值但内容无关的测试文档。

因此，下一阶段不应直接堆叠 Query Rewrite、Reranker 或新的向量数据库，而应先建立可重复的评测基线，再逐步验证每项改动是否真正提高检索质量。

## 2. 借鉴的设计与取舍

本规划借鉴以下模式：

1. 截图中的 Salvo 电商售后 Agent 项目描述：
   - BM25 与向量检索并行召回，通过 RRF 融合。
   - 使用人工标注问题集比较 direct、hybrid、rewrite 等检索策略。
   - 同时观测 Recall、MRR、NDCG、Token 和运行耗时。
   - 通过任务类型限制可用 Tool，并对上下文做预算控制。
2. Elasticsearch 官方 Hybrid Search 与 RRF 实现：
   - 使用全文检索补足精确术语、数字和制度名称的匹配能力。
   - 使用 RRF 合并 BM25 与向量检索的排名，避免直接比较两套不可直接等价的原始分数。
3. Spring AI 2.0 Tool Calling 官方机制：
   - Tool 由应用程序控制并按请求提供。
   - 高风险或无关 Tool 不应作为所有请求的默认工具。
   - 保留现有 `ChatClient + ToolCallingAdvisor`，后续按任务动态缩小 Tool 集合。

不照搬的部分：

- 不引入 LangGraph 或 LangGraph4j；当前 Java `AgentState + Tool Calling + HITL` 已能表达所需流程。
- 不引入 ChromaDB；现有 Elasticsearch 已同时支持 BM25、dense vector、过滤和混合检索。
- 暂不引入 MCP；当前 Tool 仅供本 Agent 使用，Spring AI `ToolCallback` 已满足需求。
- 不建立患者长期向量记忆；医疗对话和患者偏好涉及授权、保存期限、删除与审计问题。
- 不一次性加入 Query Rewrite、Reranker、缓存和新切块算法，避免无法判断收益来源。
- Salvo 截图中的 GitHub 地址当前无法公开访问，截图中的指标只作为方案线索，不作为本项目的验证依据。

## 3. 总体目标

本轮优化需要达到：

- 对精确制度名称、科室名、药品名、时间和数字条款保持稳定召回。
- 对口语、同义表达和不完整问题保持语义召回能力。
- 能够用离线评测证明每次检索改动的收益或退化。
- 降低无关 chunk 进入模型上下文的概率。
- 控制 RAG 请求的输入 Token、响应时间和外部模型调用次数。
- 无可靠资料时明确拒答，不让模型利用常识补写医院制度。
- 不改变 Healthy 医院系统作为实时业务数据最终权威来源的原则。

## 4. 非目标

本阶段不实施：

- 更换 Elasticsearch 或 BGE-M3。
- 复杂 Agent 图运行时或多 Agent。
- Redis/MySQL RAG Checkpoint。
- 患者对话的长期向量化存储。
- 对预约、候补、个人信息等实时业务结果进行公共缓存。
- 未经评测直接引入付费 Rerank 服务。

## 5. 目标检索流程

```text
用户问题
   ├── Elasticsearch BM25 全文检索
   └── BGE-M3 查询向量 + Elasticsearch kNN
                     ↓
                  RRF 融合
                     ↓
        文档状态/类型过滤、去重、阈值控制
                     ↓
       可选 Rerank（只有评测证明有收益时启用）
                     ↓
          上下文预算裁剪与来源编号
                     ↓
        DeepSeek 基于证据回答或明确拒答
```

当前最小版继续使用 Elasticsearch 分别返回 BM25 与 kNN 排名，再由无状态 Java 代码按 `chunkId` 执行标准 RRF 公式。这样可以复用现有外部 BGE-M3 查询向量和两套已验证的检索方法，不增加依赖，也避免在尚未证明收益前改造索引。后续如果候选量或延迟成为问题，再评估 Elasticsearch 原生 RRF 单次请求；不会建立带业务状态的客户端排名系统。

## 6. 分阶段实施计划

### 阶段 0：冻结现有向量检索基线

在修改算法前记录当前实现：

- 索引 mapping、Embedding 模型和维度。
- chunk 大小、重叠量和切分边界。
- `numCandidates`、Top K、最低相关分。
- 实际进入 Prompt 的 chunk 数和总字符数。
- Embedding、ES 检索、LLM 生成的分段耗时。
- 输入、输出和合计 Token。

输出一份可重复运行的 `vector-baseline` 报告，后续所有策略与它比较。

### 阶段 1：建立医院 RAG 人工标注评测集（已完成）

完成记录：当前固定评测集为 `v3-2026-10-09`，共 104 条脱敏问题，其中 96 条可回答问题、8 条无答案/安全负例。编号 01～24 每份测试文档各有 3 条单文档正例，另有 24 条跨制度问题分别标注 2～3 个相关文档。管理员评测接口和页面同时提供 HitRate@K、Recall@K、标准 Precision@K、MRR、nDCG@K 和负例空召回率。标注采用稳定文件名，因为上传后 `documentId` 为运行时 UUID。详细结果见 `docs/RAG_RETRIEVAL_BASELINE_2026-10-09.md`。

第一版准备 30～50 个问题，后续扩展到 80～100 个。至少覆盖：

- 精确制度名称和专业术语。
- 时间、金额、次数、天数等数字条款。
- 患者口语化表达和同义问法。
- 简称、错别字和不完整问题。
- 一个问题需要同一文档多个 chunk 的场景。
- 多份相似制度容易混淆的场景。
- 知识库无答案、应当拒答的负例。
- 恶意提示注入或要求忽略医院资料的安全负例。

建议每条用例包含：

```json
{
  "id": "rag-001",
  "question": "预约取消后多久退款？",
  "expectedDocumentIds": ["..."],
  "expectedChunkIds": ["..."],
  "answerKeyPoints": ["..."],
  "shouldAnswer": true,
  "category": "精确条款"
}
```

评测集只使用虚构或脱敏问题，不写入 JWT、患者姓名、手机号、身份证号、appointmentId 等信息。

### 阶段 2：实现 BM25 基线（已完成）

完成记录：已在现有 Elasticsearch `content` 字段上实现独立 BM25 检索，管理端可在 Vector/BM25 之间切换；患者 RAG 仍默认使用 Vector。真实 13 文档、40 问题、Top 5 基线结果为：BM25 Recall 1.0000、MRR 0.9583、nDCG 0.9692；Vector Recall 1.0000、MRR 0.9861、nDCG 0.9897。两者的负例空召回率均为 0%，留待后续阈值和 Hybrid 阶段解决。

在现有 `content` 字段上实现 Elasticsearch 全文检索，先不与向量结果融合。

需要验证：

- 当前中文 analyzer 对医院术语的分词效果。
- 精确词、数字和短查询是否命中正确制度。
- 是否需要为标题、文件名或制度类型增加独立字段和适度权重。
- 是否需要少量、可维护的医院领域同义词；不得把大量业务规则硬编码为关键词补丁。

产出 `bm25-baseline`，与 `vector-baseline` 使用同一评测集比较。

### 阶段 3：BM25 + BGE-M3 + RRF 混合检索（最小版已完成）

完成记录：新增 `HYBRID` 检索策略，每路取最近 50 个候选，以 `chunkId` 去重，采用 `rank_constant=60` 的无权重 RRF，最后裁剪到请求的 Top K。完整 v3 104 题 Top 5 下，BM25 HitRate/Recall 为 `1.0000/0.9965`，Vector 为 `0.9896/0.9792`，RRF 为 `0.9896/0.9809`。RRF 的 MRR `0.9635`、nDCG `0.9603` 略高于 Vector，但多正例完整召回仍同为 22/24，且同文档不同 chunk 无法合并证据的限制仍存在，因此患者 RAG 暂不切换默认策略。

在一个检索请求中并行执行：

- BM25：负责精确词、稀有词和数字条款。
- kNN：负责语义、同义表达和口语问题。
- RRF：按排名融合，不直接相加 BM25 `_score` 与向量相似度。

第一轮只调较少参数：

- 两路候选窗口大小。
- RRF `rank_constant`。
- 最终 Top K。
- 文档级去重或单文档最大 chunk 数。

只有 Hybrid 在总体指标或关键类别上优于两种单路检索，才将其设为默认策略。否则保留单路检索并记录失败原因。

### 阶段 4：切块、阈值和上下文预算优化

混合检索稳定后，再分别实验：

- 当前 1,000 字符、100 字符重叠的切块基线。
- 标题感知或段落感知切块。
- 不同 Top K。
- 不同最低相关阈值或无答案判定策略。
- 单文档相邻 chunk 合并。
- 单个来源文档进入 Prompt 的最大 chunk 数。
- 上下文字符预算与 Token 预算。

每次只改变一类变量。不能仅凭个别问答结果调整全局阈值。

### 阶段 5：按评测结果决定是否引入 Rerank

仅在以下情况考虑 Rerank：

- Hybrid 的 Recall 已较高，但 Top 1/Top 3 排序仍不稳定。
- 错误主要来自候选排序，而不是文档缺失或切块失败。
- 增加的延迟和成本能够接受。

先用较大的候选集召回，再把少量候选交给 Reranker。比较：

- `Hybrid`
- `Hybrid + Rerank`

如果 MRR/nDCG 或最终答案质量没有明显提升，或者延迟、Token 成本明显变差，则不保留 Rerank。

### 阶段 6：Query Rewrite 作为最后选项

Query Rewrite 会增加一次模型调用，可能引入新含义，因此仅用于：

- 指代明显、依赖对话上下文的问题。
- 极短或表达混乱、直接检索稳定失败的问题。

普通单轮问题不默认调用 Rewrite。重写后的查询必须保留原问题，并在 Trace 中同时记录原查询和重写查询；记录内容需要脱敏。

### 阶段 7：Token 与 Tool 暴露优化

RAG 检索优化完成后，继续降低 Agent Token：

- 不把全部患者 Tool Schema 无条件发送给每轮模型。
- 根据 PendingAction、可信业务引用和粗粒度路由按请求提供 Tool；查询、RAG、闲聊等单轮行为不写入会话级 `activeTask`。
- RAG 问题只开放知识检索能力；写操作确认阶段不再开放无关准备 Tool。
- 保持 Spring AI Tool resolution fallback 关闭，防止模型调用本轮未授权的 Tool。
- 给 ChatMemory、RAG chunk、Tool Result 分别设置上下文预算。
- 优先结构化裁剪 Tool Result，避免使用额外 LLM 做摘要。

动态 Tool 白名单不能削弱 HITL：创建预约、取消预约和候补写操作仍必须由 Java 冻结 PendingAction，并由按钮确认接口执行。

### 阶段 8：有限缓存与可观测性

可以缓存：

- 公共医院制度的查询 Embedding。
- 脱敏后的公共知识检索结果。
- 文档未变化期间的离线评测结果。

不得缓存或复用：

- 患者预约、候补等个人业务查询结果作为业务真相。
- Authorization、JWT、密码或患者隐私字段。
- 写操作执行前的校验结果。

Trace 至少记录：

- 检索策略：vector、bm25、hybrid、hybrid-rerank。
- 命中的 documentId/chunkId、排名和可比较的策略内分数。
- 各阶段耗时、候选数、最终上下文字符数。
- 模型输入/输出 Token。
- 是否拒答及拒答原因。

生产环境默认不记录完整患者问题、Tool 参数和完整文档内容。

## 7. 评测指标

### 检索指标

- `Recall@1/3/5`
- `Precision@3/5`
- `HitRate@K`（至少命中一个标注相关文档的问题比例）
- `MRR`
- `nDCG@5`
- 无答案问题误召回率

### 回答指标

- 答案关键点覆盖率。
- 引用是否真实支持答案。
- 忠实性：是否出现资料中不存在的制度、数字或结论。
- 无答案时是否正确拒答。
- 医疗安全边界是否正确。

### 工程指标

- P50/P95 检索耗时。
- P50/P95 完整 RAG 耗时。
- 平均输入、输出和总 Token。
- 进入 Prompt 的平均 chunk 数及字符数。
- Embedding、Rerank 和 LLM 调用次数。

LLM-as-Judge 只能作为辅助指标。核心检索指标必须依据人工标注的正确文档/chunk 计算，关键医疗制度答案需要人工复核。

## 8. 第一版验收标准

具体数值在基线报告完成后冻结，第一版至少满足：

- Hybrid 的关键问题 Recall@3 不低于当前纯向量基线。
- 精确术语、数字条款类别的 Recall@3 有明确提升。
- 无答案负例不会因为扩大召回而明显增加错误回答。
- 最终 Prompt 默认不超过现有上下文预算。
- RAG 平均 Token 不高于当前基线；如略有增加，必须有可量化质量收益。
- P95 延迟仍能满足患者聊天体验。
- 管理端可以查看检索策略、来源、排名、耗时和 Token。
- 全量后端测试通过，新增 BM25、RRF、阈值、拒答和权限测试。
- 患者端不能访问管理员检索评测接口。

## 9. 建议的代码与测试交付物

实施阶段再根据现有包结构确定最终类名，预计包括：

- 统一的检索策略接口：Vector、BM25、Hybrid。
- Elasticsearch BM25 与 RRF 查询实现。
- 检索配置对象及参数校验。
- 人工标注评测集文件。
- 可重复运行的离线评测器和 Markdown/JSON 报告。
- 管理端策略对比入口或批量评测入口。
- 检索 Trace 与 Token/耗时指标。
- 动态 Patient Tool 集合选择器。
- 单元测试、集成测试和必要的真实联调记录。

完成每个阶段后，把已经实施的设计取舍、参考实现、评测结果和回滚结论同步到 `docs/PROGRESS.md`。本文件继续保留为规划和决策记录，不把未完成事项标记为已完成。

## 10. 推荐执行顺序

1. 冻结当前 Vector Baseline。
2. 建立人工标注评测集。
3. 实现并评测 BM25 Baseline。
4. 实现并评测 BM25 + BGE-M3 + RRF。
5. 优化切块、Top K、阈值与上下文预算。
6. 根据排序错误决定是否加入 Rerank。
7. 只有必要时加入 Query Rewrite。
8. 动态 Tool 白名单已完成：五类意图路由按请求限制 Tool，路由器只读取最多 6 条上下文和精简 State；后续继续优化主 Agent 的 ChatMemory/Tool Result Token 预算。
9. 增强可观测性和有限公共知识缓存。

## 11. 参考资料

- Elasticsearch Hybrid Search：<https://www.elastic.co/docs/solutions/search/hybrid-search>
- Elasticsearch Reciprocal Rank Fusion：<https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reciprocal-rank-fusion>
- Elasticsearch Ranking and Reranking：<https://www.elastic.co/docs/solutions/search/ranking>
- Spring AI 2.0 Tool Calling：<https://docs.spring.io/spring-ai/reference/api/tools.html>
- Spring AI Observability：<https://docs.spring.io/spring-ai/reference/observability/>
- 截图中的 Salvo 地址（当前无法公开访问）：<https://github.com/li02045617/Salvo>
