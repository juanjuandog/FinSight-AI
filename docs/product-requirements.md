# FinSight AI 产品需求文档

## 1. 产品定位

FinSight AI 是面向个人投资研究者、金融技术学习者和投研工程团队的
证据驱动型 AI 投研平台。产品将公开行情、财务报表、公司公告、结构化指标
和公司事件组织为可追踪的研究任务，生成带来源证据和数据版本的分析结果。

产品重点不是提供买卖建议，而是降低公开信息整理、财务指标检查、风险线索
发现和研究结论复核的成本。

## 2. 目标用户

### 个人研究者

- 快速查看一家公司的行情、核心财务指标和风险信号。
- 通过自然语言问题检索财报和公告中的相关证据。
- 判断 AI 结论使用了哪些数据，避免无来源结论。

### 投研工程团队

- 将数据采集、指标计算、索引和报告生成拆分为可恢复任务。
- 观察任务状态、失败原因、重试次数和执行所有权。
- 通过评测和性能数据回归验证检索及生成质量。

### 技术面试与教学用户

- 运行完整的 Spring Boot、PostgreSQL、Redis、RabbitMQ 和 FastAPI 链路。
- 学习分布式 single-flight、fencing token、RAG 和证据追踪的工程实现。
- 使用确定性降级模式在没有本地大模型时完成演示。

## 3. 核心用户场景

### 场景 A：发起公司研究任务

1. 用户输入 A 股代码。
2. 系统创建带幂等键的根任务。
3. RabbitMQ 依次调度数据采集、指标计算、文档索引、公司画像和 AI 分析。
4. 前端展示当前阶段、重试次数、失败原因和完成状态。
5. 用户查看最新报告及历史版本。

验收标准：

- 相同幂等键不会创建多条有效任务链。
- 单阶段失败会自动延迟重试，超过上限后进入 Dead Letter。
- 超时任务可被恢复调度，旧 worker 不能覆盖新 owner 的结果。

### 场景 B：查看带证据的投研报告

1. 系统读取最新行情、指标、风险和证据块。
2. 根据数据内容计算 `dataSnapshotHash`。
3. 相同快照优先复用已有报告，不同快照生成新版本。
4. 报告展示引用、模型来源、生成时间、缓存命中和版本号。

验收标准：

- 同一公司报告版本单调递增且并发下不重复。
- 底层数据变化后不能复用旧快照报告。
- 并发缓存 miss 只允许一个 owner 发起昂贵生成。

### 场景 C：证据问答

1. 用户提出公司相关问题。
2. 系统执行关键词召回和 pgvector 向量召回。
3. 使用 RRF 融合两路排名，再通过 cross-encoder rerank。
4. 生成答案并返回证据块、召回通道、通道排名和融合分数。

验收标准：

- 回答必须返回至少一个可检查的证据来源，或明确说明证据不足。
- Trace 能区分 keyword、vector 和 rerank 阶段。
- 评测接口记录命中率、证据覆盖、答案覆盖和响应延迟。

## 4. 功能范围

### 当前范围

- A 股公司与行情查询。
- 财务数据采集和核心指标计算。
- 财务风险规则检测。
- 公告、财报和结构化摘要索引。
- PostgreSQL 全文检索与 pgvector 向量检索。
- RRF 融合、语义 embedding 和 cross-encoder rerank。
- 证据问答和版本化 AI 投研报告。
- 邮箱账户、服务端会话、个人关注列表和密码重置入口。
- RabbitMQ 工作流、Redis single-flight 和超时恢复。
- Dashboard、Actuator、Prometheus 指标和 RAG 评测。

### 非目标

- 不提供自动交易、委托下单或收益承诺。
- 不替代持牌机构的投资建议。
- 不保证公开数据源持续可用或完全实时。
- 当前版本不处理多租户计费、机构级权限和合规审批流。

## 5. 可靠性设计

- `createIfAbsent` 和数据库唯一键保证任务幂等创建。
- Redis Lua 原子获取、续租和释放 single-flight lease。
- fencing token 与任务状态 CAS 阻止过期 worker 写入终态。
- 生产环境 Redis 不可用时 fail-closed，不静默退化为本机锁。
- RabbitMQ publisher confirm 确认消息被 broker 接收。
- Retry Queue 使用 TTL 延迟后重新投递，达到上限后进入 DLQ。
- `dataSnapshotHash` 定义报告缓存边界。
- PostgreSQL 原子分配报告版本并施加唯一约束。

## 6. 质量指标

### 工作流

- 任务成功率、失败率和 Dead Letter 数量。
- 各任务类型 p50、p95 和 p99 执行时间。
- lease 获取失败、续租失败和 ownership lost 次数。
- 超时恢复次数及最终恢复成功率。

### 检索与生成

- RAG hit rate 和 evidence coverage。
- answer coverage 和 conclusion consistency。
- hallucination risk 和 confidence calibration。
- 检索、rerank、缓存命中、缓存未命中和生成延迟。

指标必须由自动化测试、评测接口或 benchmark 脚本生成；没有实际测量结果时，
文档和简历不得填写推测性的性能提升百分比。

## 7. 发布门槛

合入 `master` 前必须满足：

- GitHub Actions 全部通过。
- Java 单元测试和 Testcontainers 集成测试通过。
- Python 与 Shell 语法检查通过。
- Flyway migration 能在空数据库和已有 V8 数据库上执行。
- Docker Compose 配置可解析。
- PR 没有未解决的阻塞性 review。

## 8. 后续路线

1. 扩大真实公告和财报评测集。
2. 建立跨提交的 RAG 指标趋势。
3. 增加 workflow transition history 与 DLQ replay API。
4. 增加研究历史、报告收藏、个人笔记和审计日志。
5. 增加模型熔断、资源隔离和推理成本统计。
6. 建立演示环境和只读公开数据集。
