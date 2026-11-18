# DataPilot 项目阶段与验证摘要

DataPilot 是一个以可验证数据库结果为核心的电商数据分析演示项目。项目使用固定种子生成合成数据；AI 只负责提出 SQL 候选，数据库查询仍须通过服务端安全校验和只读执行。

## 项目阶段

| 阶段 | 主要内容 | 状态 |
|---|---|---|
| 第一阶段：工程基础 | Java 21 / Spring Boot 后端、Vue 前端、Maven Wrapper、健康检查、配置与验证脚本 | 完成 |
| 第二阶段：数据库与合成数据 | MySQL 8.4 Schema、独立只读与播种账号、固定种子电商数据、业务口径验证 | 完成 |
| 第三阶段：SQL 安全与执行 | JSqlParser AST 校验、对象白名单、`EXPLAIN` 成本检查、只读执行、超时、并发与结果上限、手动 SQL 工作台 | 完成 |
| 第四阶段：AI Text2SQL | Qiniu-compatible AI 客户端、提示词与 20 道问题语料、SQL 生成/安全校验/查询 API、Vue AI 工作台 | 完成 |
| 第五阶段：可靠性评测与可视化 | 429 分类和冷却、在线请求上限、离线评测、精确结果比较、图表推荐、事实洞察和前端分块优化 | 完成 |

## 已验证结果

- Maven `clean verify`：33 个单元测试和 10 个真实 MySQL 集成测试通过。
- 前端：8 个图表/洞察单测通过；TypeScript 类型检查和生产构建通过。
- 合成数据：验证脚本 12/12 项通过。
- 离线 Text2SQL 工作流：20/20 条参考查询通过 AST 校验、在 MySQL 执行并与参考结果一致；0 次安全违规执行。离线 fixture 结果不代表真实模型准确率。
- 浏览器：仪表盘及 ECharts 图表可显示；使用真实查询结果验证了地区/月度柱状图、原始表格切换和中文结论。
- ECharts 拆分后入口 JavaScript 约 82.48 kB，最大图表分块约 370.11 kB；生产构建无大分块警告。

## 在线模型验证限制

第五阶段在最多 5 次主动请求的约束内实际调用 Qiniu 4 次：一条 SSE Q01 查询端到端成功，结果与参考 SQL 一致，模型耗时 4,389 ms、用量 2,142 tokens；两次遇到 HTTP 429 `free_campaign_global_limit`；另一次浏览器请求返回无法解析的模型内容，后端未执行 SQL。

因此在线有效准确率样本目前为 1 条，不能据此推断全语料准确率或 P95。没有通过重复切换密钥或批量请求绕过服务商限流。完整技术记录见英文原始文档 [implementation-plan.md](implementation-plan.md)，评测方法见[Text2SQL 评测说明](text2sql-evaluation.md)。

## 2026-11-18 后续改动验证

- 工作台新增可选模型列表：已配置的 Qiniu、从 FreeLLMAPI `/models` 发现的本地模型，以及 ModelScope Kimi；模型请求仍由后端统一执行。
- 后端单元测试 **40 项通过**；前端单元测试 **8 项通过**，类型检查和生产构建通过。
- MySQL 集成测试未能完成：本机 Docker Desktop Linux 引擎不可用，MySQL 无法启动。此次也未运行在线模型评测；恢复 Docker 和数据集后，可按最多 5 次调用运行 `scripts/evaluate-text2sql.ps1 -Mode live -MaxCalls 5`。
