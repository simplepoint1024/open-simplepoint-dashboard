# AI 工作台与 MCP 平台边界

更新日期：2026-09-18。

## 保留的功能

AI 工作台保留 API Key、供应商、模型与模型调试、知识库、扩展目录、MCP Server
和账单。页面依据当前授权上下文确定平台/租户范围，不接受客户端任意指定租户。

领域模块分为 core、knowledge、catalog、mcp、runtime 的 API 与实现层；
管理 REST 接口位于 `simplepoint-service-ai`。模型网关与 MCP Gateway 保持独立。

## MCP 调用与运行时

MCP Server 支持远程接入与平台托管。托管服务使用不可变 Runtime Profile Revision、
能力快照和独立 Tool Runtime。第三方代码不得加载进 AI 或 Gateway JVM。

MCP Tool、Resource、Prompt、Publication 和 MCP Tasks 属于保留的协议能力。
调用经过授权、主体/租户范围检查和 Gateway；台账记录能力快照与 Runtime Revision，
不记录凭证或敏感正文。MCP Tasks 自身的持久队列不依赖已下线的业务编排模块。

Tool Runtime 保持受限 Engine Proxy、网络出口白名单、镜像准入、资源限额和工作负载
隔离。用户 OAuth 凭证经授权码与 PKCE 获取，按主体加密保存，在派发边界解析；
刷新或断开连接需要替换对应工作负载，不能把凭证写进镜像、Profile 或公开响应。

## 扩展与验证

新工具通过独立 MCP Server 扩展，不需要修改平台业务代码。
参见 [MCP Tool 独立开发](../development/ai_tool_extensions.md) 和
[Tool Runtime](../../simplepoint-services/simplepoint-service-tool-runtime-node/README.md)。

历史业务编排功能的移除范围、数据迁移和回滚要求见
[AI 编排功能下线](../release-notes/ai-orchestration-removal.md)。
