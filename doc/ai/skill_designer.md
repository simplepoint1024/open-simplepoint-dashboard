# Skill 可视化设计、调试与发布

Skill Designer 用结构化画布编排已发现并固定到 Snapshot 的 MCP Tool、Prompt 和
Resource。画布不会执行任意脚本；服务端会把 Draft 编译成不可变
`simplepoint.io/v1alpha1` Manifest，再由发布任务生成 OCI Artifact。

## 使用前准备

1. 在“AI 工作台 → MCP Servers”完成 Server 连通性检查、能力发现和 Snapshot 发布。
2. 确认当前角色具有所需的最小权限：

   | 操作 | 权限 |
   | --- | --- |
   | 查看 Draft/Revision | `ai.workbench.skills.drafts.view` |
   | 保存、校验、恢复和复制版本 | `ai.workbench.skills.drafts.manage` |
   | MOCK/LIVE 调试和安全控制 | `ai.workbench.skills.debug` |
   | 发布、Registry 检查和重试 | `ai.workbench.skills.publish` |
   | 正式执行审批 | `ai.workbench.skills.approve` |

3. 发布 OCI Artifact 时，管理员需要配置托管 Registry。浏览器不会接触 Registry
   用户名、密码或 Token。

## 从画布创建 Skill

1. 新建 Skill 后进入 Designer。每个 Skill 固定包含一个 Input 和一个 Output 节点。
2. 在 Input/Output 属性面板用字段表单维护 JSON Schema。字段可设置必填、枚举、
   默认值、示例、长度/数值范围、对象和数组结构。
3. 从左侧面板拖入或点击 Tool、Prompt、Resource、Condition 或 Parallel。键盘用户
   可以用 Tab 聚焦节点类型并按 Enter 添加；选中画布节点后可用 Delete 删除。
4. MCP 节点必须选择 Server、当前能力 Snapshot 和具体能力。保存后绑定的是不可变
   Snapshot；后续重新发现能力不会静默改变已保存 Draft。
5. 参数和 Output 映射优先从列表选择固定值、Skill Input 或上游节点输出。上游能力
   没有 `outputSchema` 时会显示弱类型警告，运行时仍会做最终校验。
6. Condition 使用结构化 `isTrue`、`equals`、`notEquals`、`all`、`any` 和 `not`；
   Parallel 的各分支全部完成后才会汇合。
7. 保存并校验。诊断区会一次显示所有可发现问题，并可定位到对应节点。

Draft 自动保存使用 Revision 乐观锁。发生并发冲突时，当前页面的编辑不会被清空；
用户可以保留编辑或显式重新加载服务端最新 Revision。Revision 历史显示产生来源和
操作人，恢复历史或复制已发布版本都会创建新 Revision，不覆盖原记录。

## 测试与调试

### MOCK 回归

为每个可执行节点配置固定成功输出或固定错误，再添加对工作流输出/节点输出的断言。
MOCK 执行不会调用外部 MCP Server；缺少节点 Mock 时安全失败，不会回退到 LIVE。
“运行全部”固定同一 Draft Revision，并保存批次和每个用例结果，适合发布前回归。

### LIVE 调试

LIVE 调试会调用当前 Revision 固定的真实 MCP 能力，且继续经过平台的租户作用域、
能力令牌和审批策略。执行启动后，Snapshot、描述符 Hash 和 Draft Content Hash 都会
固定；随后修改 Draft 不会改变已开始的执行。

断点只在外部调用前的安全检查点命中。暂停、继续和取消均为协作式控制：已发出的
外部调用不会被强制中断，取消会在其返回后的安全检查点生效，未启动步骤会标记为
跳过。副作用 Tool 应启用审批，且不要通过重复提交不同幂等键来模拟重试。

## 发布并绑定 Agent

1. 打开“发布”，先完成 Draft 保存、服务端校验和 Registry 连通性检查。
2. 输入 SemVer，例如 `1.2.0` 或 `1.2.0-rc.1`，确认变更摘要；按需选择发布后激活。
3. 发布任务依次执行生成、推送、远端校验、创建不可变版本和可选激活。关闭页面或
   重启 AI 服务不会丢失任务；失败任务会有限重试，也可从原任务手动重试。
4. 成功后选择“绑定到 Agent”。Agent 新版本会预填这个固定 Skill Version；保存并
   发布 Agent 版本后，Runtime 按 ID 解析不可变 Skill，不引用 Draft。

同一个发布幂等键只能对应同一 Draft Revision、版本号和激活选择。相同请求重放返回
原任务；不同请求复用同一键会明确失败。

## 社区 MCP 组合示例

仓库提供六个固定 OCI Digest 的原始社区 MCP Profile，位于
`simplepoint-services/simplepoint-service-tool-runtime-node/testdata/community-mcp-profiles/`：

| Profile | 推荐画布 | 验收重点 |
| --- | --- | --- |
| GitHub | Input → GitHub Tool → Output | 用户 Provider Connection、只读仓库操作 |
| Filesystem + Git | Filesystem → Git 顺序节点 | 工作区挂载、字段映射、无网络运行 |
| PostgreSQL | 查询 Tool → Condition → 两个输出分支 | 只读数据库 Secret 引用、条件表达式 |
| Docker Hub | 搜索 Tool；副作用操作加审批 | 公共发现、出站域名限制、审批边界 |
| Playwright | Parallel 下多个浏览器步骤 → 汇合 | 浏览器隔离、并行分支、输出目录限制 |

Profile 中只能保存平台 Secret 引用或 Provider Token 引用，不能粘贴 PAT、数据库 URL
或其他明文凭据。Profile 的原始来源、固定 Digest 和运行时兼容说明见
`simplepoint-services/simplepoint-service-tool-runtime-node/COMMUNITY_MCP_COMPATIBILITY.md`。

## 安全与容量边界

- Designer Document 最大 2 MiB；可执行节点最多 128，测试用例最多 64。
- 服务端 Schema 最多 32 层、4096 个节点、单对象 1024 个属性、单列表 1024 项；
  可视化 Schema/Condition 编辑器限制为 8 层，避免生成难以维护的配置。
- 明文敏感字段会在保存前扫描并拒绝；`secretRef` 和平台 Provider 引用允许保存。
- Draft 调试执行不能被 Agent、Workflow 或正式 Publication 当作版本引用。
- OCI 推送只允许配置的 Registry/Token Host，校验响应大小、Digest、Repository 和
  重定向位置；生产环境应保持签名校验开启。

## 监控与告警

AI 服务通过 Actuator metrics 暴露以下低基数指标：

| 指标 | 说明 |
| --- | --- |
| `simplepoint.ai.skill.publish.tasks` | `submitted`、`claimed`、`reclaimed`、`retry_scheduled`、`succeeded`、`invalid_manifest`、`retry_exhausted`、`fenced` 等结果计数 |
| `simplepoint.ai.skill.publish.stages` | 固定发布阶段转换计数 |
| `simplepoint.ai.skill.publish.duration` | 按成功/失败区分的端到端耗时 |
| `simplepoint.ai.skill.publish.worker.active` | 当前执行中的发布任务数 |
| `simplepoint.ai.skill.publish.worker.accepting` | Worker 是否继续接单（1/0） |

建议告警：5 分钟内 `retry_exhausted` 或 `invalid_manifest` 增量大于 0；连续 10 分钟
`submitted` 增长但 `succeeded` 不增长；`worker.accepting` 为 0 且服务不在计划停机；
P95 发布耗时超过 Registry 的正常基线。终态失败同时写入
`org.simplepoint.audit.ai.skill`/发布 Worker 结构化日志，日志不包含 Manifest、请求
载荷、Registry 凭据或 MCP 输入输出。

## 故障排查

| 现象 | 检查与处理 |
| --- | --- |
| 保存后提示 Revision 冲突 | 保留当前编辑并对照历史；确认无误后显式重新加载或复制变更到最新 Revision |
| 字段映射没有候选项 | 确认 Input Schema 或上游能力 `outputSchema` 已发布；弱类型能力可手填路径并在 MOCK 中覆盖 |
| LIVE 一直等待审批 | 在执行记录中由具备审批权限且符合自审批策略的用户处理；不要重新提交绕过审批 |
| Registry 检查失败 | 检查托管 Registry、允许域名、TLS 信任、Token Host 和 Repository 权限；不要把凭据发到浏览器 |
| 发布任务反复重试 | 查看任务阶段、错误码和 `simplepoint.ai.skill.publish.tasks`；修复 Registry/校验器后从原任务重试 |
| 任务重启后仍为 RUNNING | 等待租约过期自动回收；`reclaimed` 会增加，旧 Worker 的写入会被 fence 拒绝 |
| `SKILL_PUBLISH_MANIFEST_INVALID` | 持久化任务内容损坏；保留任务用于审计，从有效 Draft Revision 创建新发布任务 |
| `SKILL_PUBLISH_RETRY_EXHAUSTED` | 自动重试预算已用尽；先修复根因，再手动重试，不要持续创建新版本号 |

本地验收可运行：

```bash
./scripts/shell/verify_community_mcp_provenance.sh
./scripts/shell/verify_community_mcp_e2e.sh

cd simplepoint-react
pnpm i18n:check
pnpm typecheck
pnpm build
```

社区 E2E 会使用 Docker 并可能拉取固定镜像；PostgreSQL 和 GitHub 场景所需的敏感值
必须通过脚本文档规定的环境变量或用户 Provider Connection 提供，不能提交到仓库。
