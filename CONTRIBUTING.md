# 参与贡献

欢迎通过 [Issues](https://github.com/weisuansuan/DataPilot/issues) 报告问题或讨论改进，并提交 Pull Request。提交问题时请提供复现步骤、预期行为、实际行为及相关版本；分享日志前请移除密钥、数据库密码和个人信息。

## 本地开发

1. Fork 并克隆仓库，按 [README](README.md) 准备 JDK 21、Node.js 22.12+ 和 Docker Desktop。
2. 在项目根目录的 PowerShell 中运行数据库启动、合成数据播种和数据验证脚本。
3. 创建工作分支，保持修改聚焦；涉及功能或缺陷时补充对应测试，更新受影响的使用文档。
4. 提交 PR 前运行：

```powershell
.\scripts\validate-dataset.ps1
.\scripts\verify-project.ps1
.\scripts\evaluate-text2sql.ps1 -Mode mock
```

完整验证依赖已播种的 MySQL。请在 PR 中说明修改目的、验证命令和实际结果；遇到环境阻碍时如实说明，不能将未执行或失败的检查写成通过。Mock 评测不调用模型，不代表在线模型准确率。

## 项目边界

- 参阅 [AGENTS.md](AGENTS.md) 和 [实施计划](docs/implementation-plan.md)，保持现有架构及指标定义。
- 模型生成的 SQL 必须经过服务端 AST 白名单校验和统一只读执行通道；保留成本检查、超时、并发及结果行数限制。
- 保持查询、播种和初始化账号分离；DECIMAL/BIGINT 在 API 中继续使用十进制字符串。
- 所有业务数据必须标记为合成数据。不要提交真实客户数据、`.env`、凭据、运行日志或评测输出。
- 修改依赖或框架 API 时先核对当前官方文档。使用仓库 PowerShell 脚本和 Maven Wrapper，不修改全局 Git 配置。

提交贡献即表示你有权提供这些内容，并同意以本仓库的 [MIT 许可证](LICENSE) 分发贡献。
