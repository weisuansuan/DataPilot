# 本地数据库故障排查

本指南中的命令只操作当前仓库的 Docker Compose 项目，不会连接其他 MySQL 安装。

## Docker 或 Compose 不可用

运行环境检查：

```powershell
.\scripts\check-environment.ps1
```

确认 Docker CLI、Engine 和 Compose 插件均可用。若 Engine 未启动，请打开 Docker Desktop 并等待启动完成，再运行：

```powershell
.\scripts\start-mysql.ps1
```

## MySQL 容器不健康

查看当前 Compose 项目的服务状态和日志：

```powershell
docker compose ps
docker compose logs --tail 100 mysql
```

确认本机 3307 端口没有被占用。若端口冲突，可修改被 Git 忽略的 `.env` 中的 `DATAPILOT_DB_PORT`，然后重新启动本项目数据库。MySQL 只绑定到 `127.0.0.1`。

## MySQL 初始化没有创建用户或表

MySQL 只会在数据目录为空时自动执行 `database/init/` 下的初始化脚本。查看首次初始化日志，定位最早的错误。项目使用持久化 Compose 数据卷 `datapilot_datapilot_mysql_data`（Compose 可能会加上项目名前缀），里面保存本地数据。不要把删除数据卷当成通用排查步骤。

如果已有数据卷需要修复初始化脚本的变更，请针对本项目数据库执行经过检查的迁移；只有确认项目和目标数据卷后，才可重建开发数据库。

若本地数据库是开发期间从中间版本 v1 建立的，请先运行一次：

```powershell
.\scripts\apply-refund-cutoff-migration.ps1
```

此脚本会先检查字段是否已存在，并且只操作本仓库 Compose 数据库。新建数据库已在 `001-schema.sql` 中包含截止时间字段。

## API 报告数据库错误

1. 执行 `docker compose ps`，确认 `mysql` 状态为 healthy。
2. 运行 `.\scripts\validate-dataset.ps1`；若尚无数据，再运行 `.\scripts\seed-synthetic-data.ps1`。
3. 重启后端 `.\scripts\start-backend.ps1`，让它读取当前 `.env`。
4. 应用使用 `datapilot_reader` 连接数据库，不能创建或修改表。数据播种器单独使用 `datapilot_seed`。

API 的安全错误响应不会包含 SQL 文本或凭据。后端日志会带请求 ID，可用来关联排查。

## 可重复生成数据

重新运行播种器前，会确认当前数据库名称和 `SYNTHETIC_ECOMMERCE_V1` 标记；确认后只会在 `datapilot` Schema 中删除并重新生成业务行。播种器不会删除表或修改结构。订单数可设为 10 至 1,000,000；失败后可用相同订单数和固定随机种子重新运行。

也可以手动执行已提交的数据集校验脚本：

```powershell
.\scripts\validate-dataset.ps1
```

脚本会检查 `PASS` 结果、支付/退款一致性、西部地区 9 月至 10 月销售额下降，以及电子产品较高的退款率。
