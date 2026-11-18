# DataPilot 快速使用指南

本文适用于本地演示和开发。数据库中的订单、支付、退款与商品数据都是固定种子生成的合成数据，不代表真实业务。

## 环境要求

- Windows PowerShell
- JDK 21
- Node.js 22.12 或更新版本及 npm
- Docker Desktop（Docker Engine 和 Compose 可用）

先在项目根目录检查环境：

```powershell
.\scripts\check-environment.ps1
```

## 启动项目

### 启动 MySQL 并准备数据

```powershell
.\scripts\start-mysql.ps1
.\scripts\seed-synthetic-data.ps1
.\scripts\validate-dataset.ps1
```

默认生成 100,000 笔订单。数据校验脚本应显示 12 项通过。数据库只绑定到本机回环地址；不要把本地账号密码提交到 Git。

### 启动应用

打开两个 PowerShell 窗口，在项目根目录分别运行：

窗口一：

```powershell
.\scripts\start-backend.ps1
```

窗口二：

```powershell
.\scripts\start-frontend.ps1
```

浏览器打开 <http://127.0.0.1:5173>。本机若显示不同端口，以 Vite 启动日志为准。后端健康检查地址为 `http://127.0.0.1:8080/actuator/health/liveness`。

## 使用仪表盘

页面加载后会从本机 MySQL 读取订单和分析视图。仪表盘展示订单数、已支付订单数、实付金额、退款金额、地区销售变化和品类退款率。实付与退款分别列示；退款分析也会注明观察截止时间。

如果仪表盘显示数据库不可用，请确认 Docker 中的 MySQL 为 healthy，并查看[故障排查指南](troubleshooting.md)。

## 使用手动 SQL 工作台

工作台提供几个 SQL 示例。选择示例后，可先点“安全校验”，再点“执行查询”。服务端会重新执行安全校验，因此仅通过前端校验不构成执行授权。

PowerShell API 示例：

```powershell
$body = @{ sql = "SELECT paid_month, paid_order_count, paid_amount FROM v_monthly_paid_sales ORDER BY paid_month" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/query/validate -ContentType 'application/json' -Body $body
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/query/execute -ContentType 'application/json' -Body $body
```

获取业务指标目录：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/metrics
```

查询只允许访问授权表、视图和字段；每次最多返回 200 行，默认执行超时 5 秒。DECIMAL 和 BIGINT 结果以字符串返回，列元数据保留 MySQL 类型。支持语法、指标公式和更多示例见[SQL 安全与指标说明](sql-query-safety.md)。

## 使用 AI 自然语言分析（可选）

在 AI 分析区的“使用模型”下拉框切换当前模型。Qiniu 可通过 `QINIU_API_KEY` 配置；本地 FreeLLMAPI 默认地址为 `http://127.0.0.1:31415/v1`，启动后端前设置 `DATAPILOT_AI_LOCAL_API_KEY`，后端会读取 `/models` 并展示可用模型。ModelScope Kimi-K3.1-Pro 使用 `https://api-inference.modelscope.cn/v1`，启动后端前设置 `DATAPILOT_AI_MODELSCOPE_API_KEY`。这些模型可以同时配置和切换。若还配置了 Qiniu，默认模型由 `DATAPILOT_AI_PROVIDER` 决定；不配置 Qiniu 时，后端会选择可用的本地模型服务。模型密钥不能放进前端环境变量、源码、日志或 Git。没有可用模型时，仪表盘和手动 SQL 功能仍可使用。

需要临时启动本地模型时，可在项目根目录用 PowerShell 安全输入密钥，然后启动后端。密钥只保存在当前 PowerShell 进程环境中；关闭后端后清除变量：

```powershell
$secureKey = Read-Host 'FreeLLMAPI API Key' -AsSecureString
$env:DATAPILOT_AI_LOCAL_API_KEY = [System.Net.NetworkCredential]::new('', $secureKey).Password
.\scripts\start-backend.ps1
Remove-Item Env:DATAPILOT_AI_LOCAL_API_KEY
Remove-Variable secureKey
```

启用 ModelScope 时，用安全输入的 Token 设置 `DATAPILOT_AI_MODELSCOPE_API_KEY`，然后启动后端；如需同时保留本地 FreeLLMAPI，请在同一 PowerShell 进程中也设置其 API Key：

```powershell
$secureKey = Read-Host 'ModelScope Token' -AsSecureString
$env:DATAPILOT_AI_MODELSCOPE_API_KEY = [System.Net.NetworkCredential]::new('', $secureKey).Password
.\scripts\start-backend.ps1
Remove-Item Env:DATAPILOT_AI_MODELSCOPE_API_KEY
Remove-Variable secureKey
```

页面内可选择问题示例，也可输入中文问题，再点击“一键生成并分析”。成功时可查看生成 SQL、查询耗时、模型耗时、图表和原始数据表格。模型输出必须先通过服务端 SQL 安全策略；不安全或无法解析的 SQL 不会执行。

API 示例：

```powershell
$body = @{ question = "比较 2026 年 9 月和 10 月各地区实付金额，哪个地区下降最多？" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/ai/query -ContentType 'application/json' -Body $body
```

查看模型目录及默认模型：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/ai/models
```

请求体可传模型目录返回的 `id`，例如：

```powershell
$body = @{ question = "统计订单总数"; modelId = "freellmapi:auto" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/ai/query -ContentType 'application/json' -Body $body
```

若希望先查看 SQL、再决定是否执行，可调用 `/api/v1/ai/generate`。该接口只生成并校验 SQL，不执行；`/api/v1/ai/query` 是一键查询接口。遇到 429 限流时，应用会显示安全提示并在冷却期间暂停重复请求。详细配置和错误分类见 [AI Text2SQL 文档](ai-text2sql.md)。

## 离线评测与项目验证

离线评测不调用外部模型：

```powershell
.\scripts\evaluate-text2sql.ps1 -Mode mock
```

项目完整回归验证：

```powershell
.\scripts\verify-project.ps1
```

完整评测模式和结果定义见 [Text2SQL 评测说明](text2sql-evaluation.md)。

## 停止本地服务

- 在运行后端和前端的 PowerShell 窗口按 `Ctrl+C`。
- 停止本仓库的 MySQL 容器可在项目根目录运行 `docker compose stop mysql`。本地命名卷会保留数据。

更多项目背景见 [README](../README.md)；故障处理见[故障排查指南](troubleshooting.md)。
