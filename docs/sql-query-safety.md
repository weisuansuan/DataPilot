# SQL 安全、查询执行与业务指标

## 查询执行路径

`POST /api/v1/query/validate` 与 `POST /api/v1/query/execute` 使用同一套服务端策略。执行接口会再次校验 SQL，浏览器不能自行授权查询。后端通过 JSqlParser 5.4 按 MySQL 方言解析 SQL AST，要求语句恰好为一个 `SELECT`，并递归检查表、字段和函数是否属于允许清单。解析错误、可执行注释和优化器提示均会被拒绝。

查询通过现有 Hikari `JdbcTemplate` 数据源执行，使用 `datapilot_reader` 和 `read-only: true`。该账号对 `datapilot.*` 只有 `SELECT` 与元数据级 `SHOW VIEW` 权限，不具备数据或结构写权限。并发信号量默认最多允许 4 个临时查询。执行前会对已校验语句运行 `EXPLAIN FORMAT=JSON`，当执行计划缺失或估算成本超过上限时拒绝执行；默认 `DATAPILOT_QUERY_MAX_PLAN_COST=10000000`。

每条查询同时受 JDBC 超时和 MySQL Session `max_execution_time` 限制，默认 5 秒。连接归还前会重置 Session 设置；重置失败时会中止该连接。JDBC `setMaxRows(201)` 独立于用户 SQL 中的 `LIMIT` 限制结果，API 最多返回前 200 行；若检测到第 201 行，则标记 `truncated=true`。

超时、执行计划拒绝和数据库异常会转换成固定错误码与安全提示。SQL、数据库原始错误、凭据和堆栈不会返回给客户端。请求 ID 可用于关联后端日志。可通过本地环境配置 `DATAPILOT_QUERY_TIMEOUT_SECONDS`（1–30 秒）、`DATAPILOT_QUERY_MAX_CONCURRENCY`（1–8）和 `DATAPILOT_QUERY_MAX_PLAN_COST`。

## 支持语法与限制

授权对象包括第二阶段的 7 张表：`customer`、`product_category`、`product`、`sales_order`、`order_item`、`payment`、`refund`，以及 5 个分析视图：`v_paid_order`、`v_refund_by_order`、`v_refund_by_item`、`v_category_paid_sales`、`v_monthly_paid_sales`。不得给表添加数据库或 Schema 前缀。每个字段都会按表或视图的元数据校验；表别名和选择表达式别名可用。只有来源为授权对象时才允许 `*`。

允许普通 `SELECT`、JOIN、`WHERE`、`GROUP BY`、`HAVING`、`ORDER BY`、聚合函数、标量子查询和非递归 CTE。当前允许函数（大小写不敏感）：`COUNT`、`SUM`、`AVG`、`MIN`、`MAX`、`COALESCE`、`IFNULL`、`NULLIF`、`ROUND`、`ABS`、`YEAR`、`MONTH`、`QUARTER`、`DATE`、`DATE_FORMAT`、`YEARWEEK`、`CONCAT`、`LOWER`、`UPPER`、`SUBSTRING`、`LENGTH`、`CAST`、`GREATEST`、`LEAST`、`TIMESTAMPDIFF` 和 `DATEDIFF`。客户邮箱及支付/退款引用号不在字段授权范围内。

以下内容会被拒绝：多语句、非 `SELECT`、数据库限定名、未授权表或字段、未知函数、用户变量和赋值、`SELECT INTO`、锁定/等待/跳过锁定子句、可执行注释、优化器提示、文件/休眠/基准/锁相关函数以及其他未允许函数。当前不支持递归 CTE、`UNION`、窗口函数、存储函数、表函数和未识别的厂商语法。AST Visitor 无法覆盖的结构默认拒绝。SQL 长度上限为 16,384 个字符。

应用层策略与 MySQL 权限、执行计划检查、JDBC 超时、结果上限和并发上限共同构成防护。执行计划成本是估算值；即使估算成本低，服务端执行超时仍然有效。

## 指标目录

所有金额计算在 MySQL 使用 `DECIMAL`、在 Java 使用 `BigDecimal`，不使用浮点运算。

| ID | 定义与统计范围 | JOIN 防重复建议 |
|---|---|---|
| `order_count` | 订单数：统计 `sales_order.order_id` 去重；除非问题指定状态，否则包含全部订单状态 | JOIN 后仍使用订单 ID 去重 |
| `gross_paid_amount` | 成功支付本金：按 `payment.paid_at` 汇总；状态为 `SUCCEEDED`、`PARTIALLY_REFUNDED`、`REFUNDED` | 每笔订单对应一笔支付；不要直接 JOIN 原始退款明细 |
| `paid_amount` | 与现有仪表盘相同的支付本金口径；退款单独展示 | `v_paid_order` 每笔订单仅保留一条支付记录 |
| `refund_amount` | 已完成退款金额：按 `refund.refunded_at` 汇总；状态为 `COMPLETED` | JOIN 其他明细前，先按订单或商品汇总退款 |
| `net_paid_amount` | 期间支付本金减去期间已完成退款金额 | 支付与退款分别汇总后再相减 |
| `refund_rate` | 退款商品件数 / 已支付订单商品件数；观察截止 `2026-11-06` | 使用 `v_refund_by_item`；不要把原始退款明细直接与支付和订单商品明细 JOIN |
| `regional_sales` | 按收货地区和支付期间统计实付金额及已支付订单数 | 使用 `v_paid_order` |
| `category_sales` | 按商品品类汇总已支付订单商品行 `line_total` | 每笔订单一笔支付；退款单独汇总 |
| `monthly_sales_trend` | 按 `payment.paid_at` 月份汇总支付本金 | 使用预聚合视图 `v_monthly_paid_sales` |

`database/init/003-analytics-views.sql` 中的视图均为 `SQL SECURITY INVOKER`，延续第二阶段的支付/退款状态口径，不会修改合成业务记录。已有本地数据库可运行 `scripts/apply-query-views.ps1` 更新视图。

## API 示例

### 安全校验

```powershell
$body = @{ sql = "SELECT paid_month, paid_order_count, paid_amount FROM v_monthly_paid_sales ORDER BY paid_month" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/query/validate -ContentType 'application/json' -Body $body
```

### 查询各地区实付金额

```powershell
$body = @{ sql = "SELECT ship_region_name, SUM(paid_amount) AS paid_amount FROM v_paid_order GROUP BY ship_region_name ORDER BY paid_amount DESC" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/query/execute -ContentType 'application/json' -Body $body
```

### 获取指标目录

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/metrics
```

以上接口不调用 AI 服务，也不需要模型密钥。
