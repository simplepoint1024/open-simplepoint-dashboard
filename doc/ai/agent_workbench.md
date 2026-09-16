# Agent 工作台使用与运维指南

Agent 工作台用于维护 Agent 定义、创建不可变版本并观察持久化执行。Agent 不能直接调用 MCP Tool；版本只能固定已发布的 Skill 版本，运行链路始终是 `Agent → Skill → MCP`。

## 创建和发布

1. 新建 Agent，只填写稳定代码、名称和说明。代码创建后不可修改。
2. 在 Agent 工作区打开“不可变版本”，按向导依次配置基本信息、模型与 Skill、记忆与治理、输入输出。
3. 主模型和回退模型不能重复；Skill 别名和固定版本不能重复。依赖在版本创建及发布时都会由服务端重新校验。
4. 输入和输出 Schema 优先使用可视化字段编辑器；复杂 `enum`、嵌套对象或组合约束可切换到高级 JSON，原有高级约束不会因修改普通字段而丢失。
5. 确认页只创建草稿版本。发布需要再次确认；发布成功后成为活动版本，新执行会固定该版本、模型、Skill Content Hash 和策略。

向导关闭前会提示未保存修改。字段校验失败或接口失败不会清空表单。已创建版本不可编辑，已废弃版本不能重新发布。

## 执行、审批和人工介入

- “执行 Agent”根据活动版本的输入 Schema 生成结构化表单，每次提交携带幂等键。同一 Agent、作用域和幂等键的相同请求返回原执行，不同输入会被拒绝。
- 需要审批的版本先进入“等待审批”。自审批是否允许由不可变版本策略决定，不能通过前端绕过。
- 暂停是协作式安全检查点操作，不会强制中断正在进行的模型或 Skill 调用。恢复、取消、审批、驳回均要求明确确认。
- 人工介入会在下一个安全检查点等待结构化输入；最大次数、超时和超时动作固定在版本中。
- 后台轮询只同步活动执行，短暂网络失败不会连续刷全局错误；手动刷新会显示可定位的服务端错误。

## 记忆与安全边界

- 短期记忆受消息数和摘要字符上限约束，持久执行重启后仍可恢复。
- 长期记忆只在相同 Agent、平台/租户作用域和当前登录主体之间复用；管理接口不能读取或删除其他主体的记忆。
- 记忆按不可信历史数据注入，不能覆盖 System Prompt 或当前请求。条数、检索 Top K、相关度、注入大小、单条大小和保留天数均有硬上限。
- Manifest 最大 512 KiB、Skill 绑定最多 32 个；禁止在 Agent Manifest 中嵌入脚本、命令、镜像、入口点或源代码执行字段。

## 权限与审计

| 操作 | 权限 |
| --- | --- |
| 查看定义、版本、执行、指标 | `ai.workbench.agents.view` |
| 新建/修改/删除 | `ai.workbench.agents.create` / `edit` / `delete` |
| 创建不可变版本 | `ai.workbench.agents.versions.manage` |
| 发布或废弃版本 | `ai.workbench.agents.publish` |
| 启动或取消执行 | `ai.workbench.agents.execute` |
| 审批或驳回 | `ai.workbench.agents.approve` |
| 暂停或恢复 | `ai.workbench.agents.control` |
| 请求或处理人工介入 | `ai.workbench.agents.intervene` |
| 删除当前主体长期记忆 | `ai.workbench.agents.memory.manage` |

管理操作写入 `org.simplepoint.audit.ai.agent` 的无密钥结构化日志。执行状态、操作者、Trace 和人工介入写入持久事件流，敏感输入输出不会写入管理审计日志。

## 监控与排障

| 指标 | 说明 |
| --- | --- |
| `simplepoint.agent.events` | 按固定事件类型和执行状态统计持久事件 |
| `simplepoint.ai.agent.runtime.active.tasks` | 当前 Agent Worker 活跃任务数 |
| `simplepoint.ai.agent.runtime.accepting` | Worker 是否接受新任务（1/0） |

执行详情按顺序显示事件和模型/Skill Trace。失败时先查看执行错误码，再展开对应事件数据和 Trace 输入输出。Worker 重启后执行由持久租约重新领取；如果 `accepting` 为 0 或活动任务长期不下降，检查 AI 服务健康、模型网关、Agent Runtime、Skill 执行和数据库锁等待。

本地验收：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew \
  :simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-agent-implementation:check \
  :simplepoint-services:simplepoint-service-ai:check

cd simplepoint-react
pnpm --filter @simplepoint/ai test
pnpm typecheck:ai
pnpm --filter @simplepoint/ai build
```

社区 MCP E2E 的 Filesystem、Git、PostgreSQL、Docker Hub 和 Playwright 场景都会经过真实 Agent 编排链路；GitHub 真实 OAuth 场景还需要有效 OAuth App 凭据和交互式用户授权。
