# AI 编排功能下线

日期：2026-09-18。按项目所有者要求，移除技能、Agent、Agent Workflow，
包括隐藏的技能设计器、管理接口、执行队列、版本/草稿/事件、专属权限和翻译。
MCP、Tool Runtime、模型、知识库、扩展目录和账单保留。

## 升级顺序

1. 备份目标数据库、当前源码（包括未提交修改）及部署配置。
2. 构建并验证新版本；停止旧 AI、Agent Runtime、Workflow Runtime 的所有副本。
3. 对核实后的 PostgreSQL 数据库执行
   `psql -X -v ON_ERROR_STOP=1 -d <database> -f scripts/sql/cleanup-ai-orchestration.sql`。
4. 部署新 AI/MCP Gateway、Host 和翻译资源；从部署定义移除两个专属 Runtime。
5. 验证 26 张专属表、菜单/授权关系和翻译命名空间不存在，其他功能表仍在。

脚本在事务中执行，不使用 `CASCADE`。意外依赖会导致失败回滚，而不会顺带删除
其他功能。Hibernate `ddl-auto=update` 不会自动删除遗留表；旧副本也可能重建表，
因此不能跳过停服、备份和显式迁移。脚本可重复执行。

新 Host 会将这些旧书签和持久化标签页迁移到用户有权限的 AI 页面或仪表盘，
避免请求已移除的 MF 页面。静态资源归档中的旧 AI 产物应一并退役，其他远端归档保留。

## 验证与恢复

运行前端 `corepack pnpm check:frontend`、受影响 Java 模块测试和 MCP 扩展模板测试。
真实 MCP 工具调用测试需要独立测试资源及授权，不应借清理任务调用生产工具。

恢复需要协调还原源码、旧镜像、部署配置及备份中的专属表/关联行。不要只启动旧
Runtime；不要用整库覆盖恢复破坏清理后其他功能的新数据。备份可能包含凭证和
业务数据，须限制文件权限，不能提交到 Git。
