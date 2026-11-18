# AI Text2SQL 使用与设计说明

## 模型服务与客户端

当前默认使用 Qiniu AI 的 OpenAI-compatible Chat Completions 接口，地址为 `https://api.qnaigc.com/v1`，模型为 `qiniu/conatus-free`。默认启用 `stream=true`，请求超时 45 秒，最多生成 1,200 个 token。请求不会携带 `response_format`、工具调用或 Qiniu 专有参数。

项目评估过 Spring AI 2.0.2 的 OpenAI-compatible 客户端；目前通过 Java 21 `HttpClient` 实现 `ChatModelClient`，便于限制 Qiniu SSE 响应体、校验 `[DONE]` 结束标记和处理超时，不依赖可选的厂商扩展。该实现没有新增模型 SDK 依赖。

后端只从进程环境读取模型密钥。密钥不会发送到 Vue、写入 API 返回内容或加入应用日志。除 Qiniu 外，支持本地 OpenAI 兼容服务 FreeLLMAPI；本地模型通过服务端 `/models` 发现，前端只能提交模型目录里的 ID，不能指定任意地址或密钥。目录缓存 30 秒。自动化测试使用确定性 Mock Client 验证相同的业务编排。更改进程环境变量后，需要重启后端。

另支持 ModelScope OpenAI 兼容推理端点及固定模型 `moonshotai/Kimi-K3.1-Pro`。当前工作台按文本业务问题调用该模型，SSE 响应只保留 `content` 作为模型答案，`reasoning_content` 不展示也不交给 SQL 解析器；示例中的图片输入不属于当前 Text2SQL 工作台能力。

## 配置项

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| `DATAPILOT_AI_PROVIDER` | `qiniu` | 响应中显示的服务名称 |
| `DATAPILOT_AI_BASE_URL` | `https://api.qnaigc.com/v1` | OpenAI-compatible API 根地址 |
| `DATAPILOT_AI_MODEL` | `qiniu/conatus-free` | 模型名称 |
| `QINIU_API_KEY` | 空 | 仅供后端读取的 Bearer 凭据 |
| `DATAPILOT_AI_LOCAL_BASE_URL` | `http://127.0.0.1:31415/v1` | 本地 OpenAI 兼容 API 根地址 |
| `DATAPILOT_AI_LOCAL_MODEL` | `auto` | 本地首选模型 |
| `DATAPILOT_AI_LOCAL_API_KEY` | 空 | 本地 API Bearer 凭据，仅后端读取 |
| `DATAPILOT_AI_LOCAL_STREAM` | `false` | 本地服务是否使用 SSE；默认使用 JSON |
| `DATAPILOT_AI_MODELSCOPE_BASE_URL` | `https://api-inference.modelscope.cn/v1` | ModelScope OpenAI 兼容 API 根地址 |
| `DATAPILOT_AI_MODELSCOPE_MODEL` | `moonshotai/Kimi-K3.1-Pro` | ModelScope 模型 ID |
| `DATAPILOT_AI_MODELSCOPE_API_KEY` | 空 | ModelScope Token，仅后端读取 |
| `DATAPILOT_AI_MODELSCOPE_STREAM` | `true` | 是否使用 SSE 流式响应 |

本地密钥建议在启动后端的 PowerShell 进程中通过 `Read-Host -AsSecureString` 输入；不要将真实密钥写进 `.env.example`、源码或命令历史。项目模板只列出非敏感的本地 API 地址、模型和传输模式。
| `DATAPILOT_AI_STREAM` | `true` | `true` 使用 SSE，`false` 使用普通 JSON 响应 |
| `DATAPILOT_AI_TIMEOUT_SECONDS` | `45` | 请求与响应体超时时间（1–90 秒） |
| `DATAPILOT_AI_MAX_OUTPUT_TOKENS` | `1200` | 输出上限（128–4096） |
| `DATAPILOT_AI_MAX_PROMPT_CHARS` | `12000` | 系统上下文字符上限 |
| `DATAPILOT_AI_MAX_REPAIR_ATTEMPTS` | `2` | 初次生成后的最多修复请求数（0–2） |
| `DATAPILOT_AI_MAX_CONCURRENCY` | `2` | 同时处理的模型请求数（1–4） |
| `DATAPILOT_AI_MAX_PROVIDER_RETRIES` | `1` | 对已识别请求频率限制错误进行的有限重试数（0–2） |
| `DATAPILOT_AI_MAX_RETRY_DELAY_SECONDS` | `5` | 重试前最大等待时间（0–30 秒） |

## 请求处理流程

1. `Text2SqlPromptBuilder` 读取版本化提示词 `text2sql-v1.txt`，并注入当前 `SqlPolicy` 允许的字段清单和 9 个业务指标定义。提示词会提供已批准的关系及支付/退款 JOIN 防重复说明。用户问题单独作为不可信内容传入，密钥和数据库凭据不会进入提示词。
2. `OpenAiCompatibleChatClient` 发出 Chat Completions 请求。SSE 的 `data:` 数据帧会持续收集到 `[DONE]`，完成前不会开始 JSON 和 SQL 处理。响应体字节数与输出字符数都有上限；模型可能不返回 token 用量。
3. `Text2SqlOutputParser` 读取 JSON 对象并校验必要字段，处理澄清问题和不支持的场景；不假定服务端一定启用了 JSON 输出模式。
4. 每条模型 SQL 都必须通过现有 `SqlPolicy`。仅通过授权后，`QueryService` 才会进行 `EXPLAIN` 成本审查、获取查询并发名额，并通过 `datapilot_reader` 在执行时限和 200 行上限内查询。

模型不会为自己的输出授权。提示词用于提高可用性；真正的安全边界由 `SqlPolicy` 和数据库权限实施。

## SQL 修复与错误处理

解析错误或安全范围内的一般 MySQL 语法/Schema 执行错误，最多触发两次附加模型请求。每次修复后的 SQL 都要重新解析和校验后才能执行。发送给模型的仅为经过筛选的错误类别，不会传递 JDBC 原始异常或数据库内部细节。

以下错误不会触发自动修复：`UNAUTHORIZED_TABLE`、`UNAUTHORIZED_COLUMN`、`UNAUTHORIZED_FUNCTION`、其他 SQL 策略拒绝、执行计划成本过高、超时、查询繁忙、认证错误、限流、无效 JSON、空输出和服务端错误。这些情况会返回结构化错误。

## 429 限流识别与保护

遇到 HTTP 429 时，只解析长度受限响应体里的 JSON 错误码/类型，以及允许列表中的 `Retry-After` 和剩余请求/token 响应头。原始响应体和任意响应头不会写入日志或返回前端。错误类别包括请求频率、额度/计费、模型容量、服务商全局限制和未知错误。

只有明确识别为请求频率限制时才按配置执行有限次数重试；`Retry-After` 会遵守配置的等待上限。重试采用指数退避与随机抖动。最终 429 后会启动进程内冷却：额度或全局限制 300 秒、容量限制 30 秒、请求频率限制 5 秒、未知原因 60 秒；服务商提供的 `Retry-After` 在配置边界内优先。API 与前端只展示安全的类别、状态码和建议等待时间。冷却状态保存在当前进程内，重启后清空。

2026-11-07 的一次 Qiniu 诊断请求返回 HTTP 429，响应类型为 `rate_limit_exceeded_error`，错误码为 `free_campaign_global_limit`；没有发现受检查的 `Retry-After` 或标准 rate-limit 响应头。这只能证明服务商报告了全局限制，不能据此确认根因是请求频率、额度/活动政策还是模型容量。该类别不会自动重试。

第五阶段浏览器实测中，还有一次模型响应无法通过严格 Text2SQL 解析器，API 安全地返回 `AI_INVALID_RESPONSE`，没有生成 SQL，也没有执行查询。未立即重试。另一次 SSE 评测的 Q01 端到端成功；在线证据与调用次数记录见 [Text2SQL 评测说明](text2sql-evaluation.md)。

## API 示例

先查看允许选择的模型；API 请求可选包含返回目录中的 `modelId`，不传则使用配置的默认模型：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/ai/models
$body = @{ question = "统计订单总数"; modelId = "freellmapi:auto" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/ai/query -ContentType 'application/json' -Body $body
```

### 先生成 SQL 并检查安全结果

```powershell
$body = @{ question = "比较 2026 年 9 月和 10 月各地区实付金额" } | ConvertTo-Json
$generated = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/ai/generate -ContentType 'application/json' -Body $body
$generated | Select-Object success, intent, sql, explanation, validation, model, modelResponseTimeMs, tokenUsage
```

### 一次请求完成生成与查询

```powershell
$body = @{ question = "列出 2026 年每月实付金额" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/ai/query -ContentType 'application/json' -Body $body
```

`/api/v1/ai/generate` 不执行 SQL；若要执行，仍应使用 `/api/v1/query/execute`，由该接口再次执行 AST 校验。`/api/v1/ai/query` 是一键流程，内部调用相同的策略与查询服务。

响应字段包括意图、SQL、解释、校验结果、provider/model、提示词版本、模型耗时、修复次数和可选 token 用量。`/ai/query` 还会返回列名/类型、数据行、查询耗时、行数、是否截断和受控错误信息。

查询 API 将 DECIMAL 与 BIGINT 编码为十进制字符串，避免浏览器解析 JSON 时静默舍入金额或大整数。列元数据仍包含 MySQL 原始类型；图表服务只会把处于 JavaScript 安全数值范围内的数据转换成图表数值。

## 评测语料

`backend/src/main/resources/text2sql/evaluation-v1.json` 包含 20 条中文业务问题、指标 ID、参考 SQL、输出列和关键指标固定值。`Text2SqlEvaluationCorpusTest` 检查题目唯一性以及所有参考 SQL 是否通过同一安全策略。`Text2SqlEvaluationDatabaseIT` 会在真实合成 MySQL 上执行全部参考 SQL，检查输出结构及订单数、支付订单数、实付本金、退款额和净支付本金等精确值。

该语料可以验证参考 SQL 与数据库口径，不代表在线模型已经达到 20/20 准确率。离线和受限在线评测方法见 [Text2SQL 评测说明](text2sql-evaluation.md)。Mock Client 和编排测试可重复运行，不产生模型调用费用。

AI 查询结果可基于已执行的数据行生成图表和结论，详情见[图表与洞察说明](ai-visualization.md)。
