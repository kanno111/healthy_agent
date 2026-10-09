# Repository collaboration rules

- 对 Agent 架构、Tool Calling、HITL、RAG、Memory、权限与可靠性进行非平凡修改前，先调研至少一个维护活跃、设计成熟的开源项目或对应框架的官方实现。
- 优先参考与当前 Java、Spring Boot、Spring AI 技术栈接近的实现；必要时再参考 LangGraph、Dify 等跨语言项目的架构模式。
- 在实施前说明借鉴的模式、适用于本项目的部分以及没有照搬的部分。不要仅因为开源项目使用某个组件就直接增加依赖。
- 完成后在 `docs/PROGRESS.md` 记录关键设计取舍、参考项目和验证结果。
