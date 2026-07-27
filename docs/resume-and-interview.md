# Resume And Interview Notes

This document turns FinSight into resume-ready language and interview talking points.

## One-Line Project Description

FinSight is an AI equity research agent platform with resilient workflow orchestration, Redis Lua single-flight concurrency control, PostgreSQL/pgvector hybrid retrieval, versioned AI report caching, evidence tracing, and RAG evaluation.

## Resume Version

**FinSight AI 投研 Agent 平台**  
`Java`, `Spring Boot`, `PostgreSQL`, `pgvector`, `Redis`, `RabbitMQ`, `RAG`, `AI Agent`

- 设计并实现面向 A 股投研场景的 AI Agent 后端平台，串联数据采集、财报解析、指标计算、文档索引、公司画像建模、RAG 问答和 AI 研报生成。
- 基于 RabbitMQ 构建消息驱动投研工作流，将数据采集、指标重算、文档索引、公司画像和 AI 分析拆分为逐阶段发布的 `WorkflowTask`，通过 publisher confirm、失败重试、Dead Letter 和超时恢复治理长任务。
- 设计 Agent 工作流状态机，记录任务阶段、执行状态、失败原因、重试次数、更新时间、lease owner 和 fencing token，提升长链路任务可观测性和恢复能力。
- 基于 Redis Lua、可续租 Single-flight Lease、幂等 Key 和 Fencing Token CAS 实现多实例任务去重、AI 调用防放大与缓存击穿保护；生产环境 Redis 故障采用 fail-closed。
- 设计可信 AI 报告缓存体系，基于 `dataSnapshotHash`、`contextHash` 和 `reportVersion` 管理 AI 研报缓存、历史版本和数据快照，避免底层行情、指标或证据变化后复用过期结论。
- 基于 PostgreSQL/pgvector 实现金融文档混合检索，使用 RRF 融合关键词与向量召回，并记录各通道 rank、融合分数和引用证据。
- 构建投研 Agent 评测体系，覆盖 RAG 命中率、证据覆盖率、幻觉风险、结论一致性、置信度校准和响应延迟，用于回归验证 AI 输出质量。

## Strong Interview Summary

> 这个项目不是简单调用大模型，而是围绕金融投研场景实现 AI Agent 后端基础设施。我把投研链路拆成数据采集、指标计算、文档索引、公司画像和 AI 研报生成几个可恢复阶段，用 RabbitMQ 做异步调度，用状态机管理任务生命周期。为了防止多实例重复执行和 AI 调用放大，我实现了 Redis Lua Single-flight lease、幂等 key 和 fencing token。AI 报告不是简单缓存 prompt，而是基于 dataSnapshotHash 和 reportVersion 绑定底层行情、指标、风险信号和证据快照。最后我还做了 RAG/Agent 评测，包括证据覆盖、幻觉风险、结论一致性和置信度校准。

## What To Emphasize

- This is an AI Agent backend system, not a chat demo.
- The most expensive operations are idempotent and single-flight protected.
- The model output is tied to a data snapshot and report version.
- Evidence retrieval is inspectable and measurable.
- Failure recovery is built into the workflow rather than handled manually.

## Likely Interview Questions

### Why use Redis Lua instead of a normal Redis lock?

Because acquisition must be atomic: checking lease existence, incrementing the fencing counter, and writing the owner/token pair should happen as one operation. Lua keeps that logic server-side and prevents race conditions between multiple service instances.

### What problem does `dataSnapshotHash` solve?

It prevents stale AI reports from being reused after underlying market quotes, financial metrics, risk signals, or evidence chunks change. The cache key follows the data snapshot, not just the stock symbol or prompt text.

### What is the difference between task status and agent stage?

Status answers whether the task is created, running, succeeded, failed, retrying, or dead-lettered. Stage answers where the task is inside the research pipeline, such as ingestion, metric calculation, indexing, intelligence build, or AI analysis.

### Why build an evaluation layer?

Financial AI output needs evidence and consistency. The evaluation layer gives the project a regression loop for RAG hit rate, evidence coverage, hallucination risk, conclusion consistency, confidence calibration, and latency.

### What would you improve next?

- Add persisted workflow transition history.
- Add dashboard screenshots and hosted demo data.
- 扩大 RAG 评测集并持续记录跨提交趋势。
- Add PR-ready benchmark results for RAG evaluation cases.
