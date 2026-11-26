# DataPilot

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

DataPilot 是一个面向业务分析场景的 AI 数据分析演示项目。用户可以查看电商仪表盘、手动编写只读 SQL，或用中文提出问题，由模型生成 SQL 后交给服务端安全校验，再从 MySQL 读取真实合成数据并展示结果。

项目强调结果可核验：查询受 SQL AST 策略、`datapilot_reader` 只读权限、`EXPLAIN` 成本检查、执行超时、并发上限和 200 行结果上限共同保护。AI 生成的 SQL 不能绕过这些限制。

> **第五阶段已完成：** 已加入 Text2SQL 可靠性处理、20 题离线评测、结果图表推荐和中文数据洞察。在线模型评测样本有限；详见[项目阶段与验证摘要](docs/implementation-summary.md)。

## 技术栈

- Java 21、Spring Boot 4.1.2、Maven Wrapper 3.9.16
- Vue 3、TypeScript、Vite、ECharts
- MySQL 8.4.13、Docker Compose
- Qiniu AI OpenAI-compatible Chat Completions API（可选）

## 快速开始

需要 JDK 21、Node.js 22.12 或更新版本、npm、Git，以及已启动的 Docker Desktop。以下命令在项目根目录的 PowerShell 中执行。

```powershell
git clone https://github.com/weisuansuan/DataPilot.git
cd DataPilot
```

### 1. 启动数据库并生成合成数据

```powershell
.\scripts\start-mysql.ps1
.\scripts\seed-synthetic-data.ps1
.\scripts\validate-dataset.ps1
```

首次启动会创建被 Git 忽略的 `.env`，保存本地数据库随机密码。默认数据集包含 100,000 笔订单；种子固定，适合重复验证。所有业务数据均为合成数据。

### 2. 启动后端与网页

分别在两个 PowerShell 窗口运行：

```powershell
.\scripts\start-backend.ps1
```

```powershell
.\scripts\start-frontend.ps1
```

打开 <http://127.0.0.1:5173>。开发服务器固定使用 5173 端口；若端口被占用，请先释放端口再启动。

### 3. （可选）启用 AI 分析

模型密钥只在后端进程环境中设置，不要写入前端配置、源码、日志或 Git。支持 Qiniu、FreeLLMAPI 本地服务和 ModelScope 的 Kimi-K3.1-Pro；后两者都可在工作台下拉切换。FreeLLMAPI 默认地址为 `http://127.0.0.1:31415/v1`，ModelScope 默认地址为 `https://api-inference.modelscope.cn/v1`。没有可用模型时，仪表盘和手动 SQL 工作台仍可使用，自动化测试使用离线 Mock。

AI 默认模型仍按 `DATAPILOT_AI_PROVIDER` 配置（默认 Qiniu）；工作台可切换到已发现的 FreeLLMAPI 本地模型。配置方式、模型目录 API、限流处理和调用示例见 [AI Text2SQL 使用说明](docs/ai-text2sql.md)。

## 工作台功能

- **仪表盘：** 展示订单、实付、退款、地区销售变化和品类退款率；金额口径及退款观察截止时间随结果说明。
- **手动 SQL：** 提供 SQL 编辑、服务端安全校验、执行、错误提示和表格结果。
- **AI 分析：** 输入中文业务问题；模型生成 SQL 后，由服务端独立校验并通过只读执行引擎查询。结果可查看 SQL、耗时、图表和原始表格。
- **图表与洞察：** 根据结果列和数据确定性推荐 KPI、柱状图、折线图、饼图或表格；中文结论只引用本次 SQL 结果。

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/metrics` | 获取业务指标目录 |
| `POST` | `/api/v1/query/validate` | 校验 SQL 是否符合服务端安全策略 |
| `POST` | `/api/v1/query/execute` | 重新校验并执行只读查询 |
| `POST` | `/api/v1/ai/generate` | 生成 SQL 并返回安全校验，不执行查询 |
| `POST` | `/api/v1/ai/query` | 生成、校验并执行 SQL 的一键流程 |
| `GET` | `/api/v1/ai/models` | 获取已配置模型与默认选项 |

SQL 查询 API 将 DECIMAL 和 BIGINT 以十进制字符串返回，避免浏览器解析 JSON 时舍入金额或大整数。完整示例见[中文快速使用指南](docs/user-guide.md)及[SQL 安全与指标说明](docs/sql-query-safety.md)。

## 评测与开发验证

离线评测会用语料中的参考 SQL 作为确定性模型响应，通过真实 MySQL、AST 校验和查询结果比较跑完 20 题；它验证流程，不代表在线模型准确率。

```powershell
.\scripts\evaluate-text2sql.ps1 -Mode mock
.\scripts\verify-project.ps1
```

真实模型评测最多允许 5 次 API 请求，并在限流时停止：

```powershell
.\scripts\evaluate-text2sql.ps1 -Mode live -MaxCalls 3 -IntervalSeconds 10
```

评测结果默认保存在被忽略的 `.runtime/evaluations/`。更多指标与限制见 [Text2SQL 评测说明](docs/text2sql-evaluation.md)。

## 文档导航

- [中文快速使用指南](docs/user-guide.md)：启动、工作台操作、SQL 与 AI API 示例。
- [SQL 安全与业务指标](docs/sql-query-safety.md)：支持语法、执行边界、指标口径和请求示例。
- [AI Text2SQL 配置与调用](docs/ai-text2sql.md)：Qiniu 配置、错误处理、限流和工作流程。
- [Text2SQL 可靠性评测](docs/text2sql-evaluation.md)：离线/在线模式、分母定义与实际结果。
- [图表推荐与数据洞察](docs/ai-visualization.md)：图表规则、数值精度和结论生成。
- [本地数据库故障排查](docs/troubleshooting.md)：Docker、MySQL 和 API 常见问题。
- [项目阶段与验证摘要](docs/implementation-summary.md)：五个阶段的交付内容、验收结果和已知限制。

## 数据库账号与凭据

应用使用 `datapilot_reader`，该账号仅有当前 schema 的 `SELECT` 和元数据级 `SHOW VIEW` 权限。数据播种器单独使用 `datapilot_seed`。数据库初始化、迁移、播种和查询执行凭据彼此分离。

`.env` 由本地脚本生成并被 Git 忽略。严禁提交 `.env`、数据库密码、AI 密钥、客户数据或其他凭据。

## 使用范围

本项目面向本地学习、演示和开发，默认使用合成电商数据，尚未实现用户登录与多租户权限。请在本地或可信环境运行；部署到公网前需要自行补齐身份认证和访问控制。AI 服务需自行配置，服务商可用性、额度及使用条款由各服务商决定。

## 贡献与许可证

欢迎提交 [Issue](https://github.com/weisuansuan/DataPilot/issues) 或 Pull Request，开发和验证步骤见 [贡献指南](CONTRIBUTING.md)。

本项目采用 [MIT 许可证](LICENSE)。第三方依赖及外部模型服务保留各自的许可证和使用条款。
