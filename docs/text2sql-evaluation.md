# Text2SQL 可靠性评测

语料位于 `backend/src/main/resources/text2sql/evaluation-v1.json`，包含 20 条中文问题、指标 ID、参考 SQL、输出字段和核心指标固定值。评测器始终通过现有 SQL AST policy、EXPLAIN 计划审查、`datapilot_reader` 和有界 `QueryService` 执行模型与参考 SQL。

## 离线 Mock

```powershell
.\scripts\evaluate-text2sql.ps1 -Mode mock
```

Mock 会将语料里的参考 SQL 作为确定性响应，跑完全部 20 个问题，不读取或调用 Qiniu API；这用于验证解析、AST、真实 MySQL 执行和结果比较工作流，不代表在线模型的 Text2SQL 准确率。Markdown 和 JSON 报告默认写入被 Git 忽略的 `.runtime/evaluations/`。

## 真实模型（受限）

```powershell
.\scripts\evaluate-text2sql.ps1 -Mode live -MaxCalls 3 -IntervalSeconds 10
```

`MaxCalls` 只接受 1–5，默认 5；请求间隔最少 5 秒。评测脚本关闭自动 SQL 修复与 provider 内部重试，以便调用上限对应外部请求数。收到 429 后停止本次后续模型请求，其余样本标为“未请求”，不会进入 SQL 生成失败或结果不准确分母。评测前必须在后端进程环境配置 `QINIU_API_KEY`。

脚本记录每个真实调用的 SQL、AST 结果、执行结果、参考/实际结果摘要、错误码、HTTP 状态、提供方错误类别、建议等待秒数、模型耗时及可用 token 用量。结果摘要是字段排序、行顺序无关、DECIMAL 精确规范化后的 SHA-256；原始数据行不会写入评测报告。

## 指标定义

| 指标 | 分子 | 有效分母 |
|---|---|---|
| SQL 生成成功率 | 产生非空 SQL | 返回完整模型响应的样本（429 等未生成响应单独报告） |
| AST 通过率 | 通过现有安全策略的 SQL | 已生成 SQL |
| SQL 执行成功率 | 成功查询数 | AST 通过数 |
| 查询结果准确率 | 与参考 SQL 的列和值完全相同的结果 | 模型与参考 SQL 均成功且参考值符合语料定义的样本 |
| 业务指标准确率 | 按 metricId 分组的准确结果 | 对应指标的有效结果样本 |
| 模型延迟 | 平均值及 nearest-rank P95 | 返回模型请求耗时的实际请求 |
| Token 用量 | 分 prompt/completion/total 统计 | provider 返回 token 用量的样本 |
| 自动修复成功率 | 修复后 SQL 执行成功数 | 实际发生修复的样本 |
| 安全违规执行 | 未通过 AST 仍被执行的次数 | 实际请求样本，目标为 0 |

金额以 `BigDecimal` 规范化后比较，不采用浮点容差。查询 API 将 DECIMAL 与 BIGINT 结果编码为十进制字符串，前端依据列类型识别数值；超出 JavaScript 安全精度的指标保留表格，避免浮点图表失真。结果列按名称比较、行按规范签名排序，因此不依赖 SQL 未保证的行顺序；LIMIT/Top-N 仍需返回相同的成员集合。评测前还会验证参考 SQL 的输出列及语料提供的固定标量，参考环境不符时将其从结果准确率分母排除并单独记录。

报告包含全局聚合、按 metricId 汇总和逐题记录；429、认证、超时、AST 拒绝、SQL 执行失败、结果不匹配与未请求样本分别记录。Mock 报告中模型延迟和 token 使用量为 N/A。

## 2026-11-07 实际验收记录

- 离线 Mock：20/20 生成、AST 通过、真实 MySQL 执行并与参考结果一致；安全违规执行 0；模型请求 0。
- 受控在线验收共发出 4 次 Qiniu 请求：一次诊断和一次 JSON 评测收到 HTTP 429 `free_campaign_global_limit`；一次 SSE 评测的 Q01 从生成到 AST、MySQL 执行及结果摘要匹配全部通过（模型 4,389 ms，2,142 tokens）；一次浏览器地区分析请求返回了无法解析的模型内容，因此未生成 SQL，也未执行查询。
- 在线结果只覆盖一个有效样本，不能据此推断全语料准确率或 P95。429 未计入 SQL 生成失败或准确率错误。没有继续发送请求，以遵守本阶段最多五次主动验证调用的限制。
