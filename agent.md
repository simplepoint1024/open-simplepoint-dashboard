# AI 工作台 MCP 平台实施记录

本文件不是代理编码规则；编码规则见 `AGENTS.md`。

当前架构以 [AI 与 MCP 平台边界](doc/design/ai_mcp_platform.md) 为准。
2026-09-18 的功能精简及数据库迁移见
[AI 编排功能下线](doc/release-notes/ai-orchestration-removal.md)。

保留模型网关、知识库、扩展目录、MCP Gateway、Tool Runtime、Publication 和
MCP Tasks。历史阶段性验收不等于当前部署的真实端到端验收，升级后需重新执行
对应测试。
