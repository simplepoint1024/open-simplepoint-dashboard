# AI 工作台 MCP 平台实施进度

> 本文件用于记录 AI 工作台 MCP、Skill、Agent 和 Workflow 建设进度，不是代理编码规则。仓库编码规则仍以 `AGENTS.md` 为准。

## 当前状态

| 项目 | 状态 |
| --- | --- |
| 最后更新 | 2026-07-30 |
| 当前阶段 | Phase 1、Phase 2、Phase 3 已完成；Phase 4 Agent Runtime 已完成记忆、人工介入与持久化可观测执行面 |
| 设计文档 | [AI 工作台 MCP、Skill、Agent 平台设计](doc/design/ai_mcp_agent_skill_platform.md) |
| 代码实施 | 已新增 MCP、Runtime、Skill、Agent 四层模块，独立 Gateway、独立 Tool Runtime 和对应工作台页面 |
| 数据库变更 | 已新增 MCP、Runtime、加密 Secret、Skill Registry/Execution，以及 Agent Registry/Execution/Trace/Event/Memory/Human Intervention |
| 部署变更 | 已新增 OCI 镜像、开发 PKI、受限 Engine Proxy、Egress Proxy、Image Verifier、Tool Runtime 与独立 Agent Runtime |

## 已确认的设计决策

- [x] MCP 以 `2025-11-25` 稳定规范为当前实现基线。
- [x] MCP Gateway 使用独立服务，不与现有模型网关合并。
- [x] 支持接入远程、平台托管和租户 MCP Server。
- [x] 支持将平台或租户能力作为标准 MCP Server 对外开放。
- [x] MCP 原生对象限定为 Tool、Resource、Prompt 等协议对象。
- [x] Skill、Agent、Workflow 是平台声明式资源，不冒充 MCP 原生标准。
- [x] 强制执行 `Agent -> Skill -> Tool` 调用链。
- [x] Tool 使用独立 MCP Server 和 OCI 镜像扩展。
- [x] Skill 使用声明式 OCI Artifact 扩展。
- [x] 新增 Tool、Skill 不修改平台代码。
- [x] `tool-runtime` 继续保持独立进程。
- [x] 第三方代码不进入平台主服务、Gateway 或 Agent Runtime 进程。
- [x] 不依赖 Kubernetes，使用 Docker Swarm、OCI Runtime 和平台节点调度。
- [x] 页面根据当前平台/租户上下文自动确定作用域。
- [x] OAuth 使用 MCP 规范要求的 OAuth 2.1、Protected Resource Metadata 和 Resource Indicator。
- [x] MCP Tasks 仅作为实验性兼容能力，内部工作流使用平台持久化状态机。

## 实施阶段

### Phase 0：协议与落地基线

- [x] 校验现有 Gradle 模块、Java 21 版本和依赖基线。
- [x] 固定 MCP Java SDK `2.0.0` 版本和 BOM。
- [x] 固定内部模块、包名、API 路径和资源编码。
- [x] 固定数据库表、迁移和多租户字段。
- [x] 固定 MCP Server、Skill、Agent Manifest Schema。
- [x] 建立协议一致性和安全测试方案。

### Phase 1：MCP Gateway 与 Registry

- [x] 新增 MCP Gateway 独立服务。
- [x] 新增 MCP Server Registry 和能力快照。
- [x] 接入远程 Streamable HTTP MCP Server。
- [x] 实现 Tool、Resource、Prompt 能力。
- [x] 实现 MCP Publication 对外开放。
- [x] 实现 OAuth 2.1、Publication 会话、分布式限流和通用能力审计。
- [x] 增加 AI 工作台 MCP Gateway、MCP Servers 和工具页面。
- [x] 增加 AI 工作台 MCP Publications 页面。
- [x] 增加能力快照历史/差异查询与页面，以及 Publication 实际生效 Manifest 查询与预览。

当前已完成 `initialize`、Tool、Resource、Resource Template、Prompt 的发现与调用；
北向 Publication 使用官方 SDK 提供 Streamable HTTP `POST/GET/DELETE` 会话。
南向连接已改为有界持久会话池，并完成 Pagination、`resources/subscribe`、
list-changed、Resource Updated、Progress、Cancellation 适配和跨 Gateway 副本会话亲和。
Phase 1 仍保留主流远程 MCP Server 兼容矩阵和 Redis 短暂故障验收。

### Phase 1 已完成的第一批垂直切片

- [x] Gateway 只消费协议 DTO，不加载 AI 控制面、JPA 或数据库驱动。
- [x] AI 控制面和 Gateway 使用内部共享令牌，Gateway 私有 API 不对宿主机发布端口。
- [x] 平台/租户作用域由授权上下文确定，租户注册禁止访问私有网络。
- [x] Bearer 凭证 AES-GCM 加密、只写不回传。
- [x] 能力发现生成不可变快照，Tool 调用只能使用 READY 活跃快照。
- [x] 调用审计只保存参数/结果哈希和执行元数据。
- [x] Gateway 端限制协议、Endpoint、重定向、参数大小、结果大小和 Tool Schema。
- [x] 新增 `somesimpled/open-simplepoint-mcp-gateway:*` OCI 镜像和独立 Dockerfile。
- [x] 本地 `open-simplepoint-mcp-gateway-1`、AI、Common 容器更新并通过健康检查。

### Phase 1 已完成的第二批垂直切片

- [x] 远程 MCP OAuth 2.1：RFC 9728、授权服务器元数据、PKCE S256、
  RFC 8707 Resource Indicator、动态或预注册客户端、授权码与 Refresh Token。
- [x] OAuth state 仅保存 SHA-256，PKCE verifier、Client Secret、Access Token 和
  Refresh Token 使用 AES-GCM 加密；外部 Token 不向上游透传。
- [x] Resource、Resource Template、Prompt 纳入不可变能力快照，并在工作台完成读取测试。
- [x] 新增平台/租户 MCP Publication 管理资源、数据库模块、REST 和管理页面。
- [x] 北向 Gateway 使用官方 Java SDK 暴露标准 Streamable HTTP MCP Server。
- [x] 每个 Publication 使用独立 canonical resource URI、OAuth audience、Scopes 和限流。
- [x] Gateway 使用 PS256、Issuer、Audience、Scope 四层令牌校验，并输出 RFC 9728 元数据。
- [x] Redis Lua 实现跨 Gateway 副本的固定窗口限流。
- [x] Tool、Resource、Prompt 共用元数据审计，仅存请求/结果哈希、主体、客户端和会话。
- [x] Host 公开转发 `/mcp/**` 和 `/.well-known/oauth-protected-resource/**`，
  AI 控制面内部 Publication API 继续使用独立共享令牌保护。
- [x] Publication RFC 9728 元数据使用独立高优先级安全链，避免 Spring Security
  通用元数据过滤器根据请求头生成非 canonical resource URI。
- [x] 授权服务器支持授权请求与 Token 请求的精确 Resource Indicator 匹配，
  并将 canonical resource URI 写入 Access Token audience。

### Phase 1 已完成的第三批协议与横向扩展切片

- [x] 南向 MCP Client 使用按服务定义、端点和凭证指纹隔离的有界持久会话池，
  支持空闲回收、容量淘汰和凭证变化自动换代。
- [x] 官方 SDK 游标链路读取 Tool、Resource、Resource Template、Prompt 全部分页；
  增加多页能力发现和同连接只初始化一次的协议测试。
- [x] 对支持订阅的远端 Server 自动订阅已发现和已读取 Resource，
  接收 Resource Updated 并转发到北向已订阅会话。
- [x] 接收远端 Tool/Resource/Prompt list-changed，控制面生成新的不可变快照，
  Redis 广播到所有 Gateway 副本后原地更新 Publication，不关闭现有客户端会话。
- [x] 北向 Publication 始终声明 list-changed 与 Resource Subscription 能力，
  使用官方 SDK 发送 Tool、Resource、Prompt 和 Resource Updated 标准通知。
- [x] Progress 使用集群 operation ID 关联南北向请求，并恢复外部客户端原始
  progress token。
- [x] 在官方 SDK transport 外围接入标准 `notifications/cancelled`，
  通过 Redis 将取消传播到持有实际南向调用的 Gateway 副本。
- [x] Host 根据 `Mcp-Session-Id` 哈希维护 Redis 会话目录并直达原 Gateway 副本；
  404/502/503/504 或连接失败时清除映射，客户端重新 initialize。
- [x] 会话目录只保存实例地址和 Session ID 哈希，不保存原始 Session ID。

### Phase 1 已完成的第四批 OAuth 与故障验收切片

- [x] 授权服务器声明并支持 OAuth Client ID Metadata Documents；预注册客户端优先，
  未注册的 HTTPS URL Client ID 才执行文档获取、精确 ID/Redirect URI 校验和持久化。
- [x] Client Metadata 获取禁止重定向、限制响应大小和缓存数量，默认拒绝 localhost、
  私网地址、非 HTTPS 地址以及非 public-client 认证方式。
- [x] Gateway 可在固定 `/.well-known/oauth-client/open-simplepoint-mcp-gateway`
  发布自身 Client Metadata；远程 OAuth 选择顺序为预注册、Client Metadata、DCR。
- [x] Consul 实例 ID 纳入容器 Hostname，两个 Gateway 副本不再互相覆盖注册。
- [x] 使用真实 OAuth Token、Publication 和两个 Gateway 容器完成故障注入：
  首次会话固定到副本 A，停止 A 后旧会话返回 502 并清理映射，重新初始化后固定到副本 B。
- [x] 故障验证数据、Redis 测试目录和临时第二副本均已清理，恢复本地单副本基线。

### Phase 2：OCI Runtime 与节点调度

- [x] 新增独立 Go `tool-runtime-node` 和
  `somesimpled/open-simplepoint-tool-runtime:*` OCI 镜像。
- [x] 实现节点侧 digest 固定、Registry Allowlist、OCI/MCP 标签拉取和校验。
- [x] 实现 OCI 实例创建、启动、状态、停止和删除私有 API。
- [x] 实现到期实例自动停止和回收。
- [x] 实现 Runtime Node/Workload/Lease 数据模型。
- [x] 实现节点注册、容量/标签心跳、优雅离线、超时离线和进程代际 fencing。
- [x] 实现容量感知节点选择、工作负载租约、实际调度下发和 fencing 接管。
- [x] 以 TLS 1.3 双向认证和精确 URI SAN 节点身份替换 Runtime 共享 Token。
- [x] Runtime 只通过受限 Docker Socket Proxy 访问 Engine，不再挂载 Docker Socket。
- [x] 实现预热、弹性副本和 scale-to-zero。
- [x] 实现非 root、只读根文件系统、Capability、PID/CPU/内存、tmpfs、
  默认断网和宿主挂载隔离。
- [x] 实现平台/租户 Runtime Secret Broker、调度时 mTLS 传输、短期只读文件挂载和清理。
- [x] 实现独立 Egress Proxy、短期签名策略、DNS allowlist 与无直接 Internet 路由。
- [x] 实现独立 Image Verifier、Cosign 签名、签名 SBOM、Trivy 漏洞和拉取前准入。
- [x] 增加环境级 seccomp/AppArmor 策略。
- [x] 将托管 OCI stdio MCP Server 以标准 Streamable HTTP 会话接入 Gateway。
- [x] 完成托管会话的 Redis 目录、Rendezvous 多副本分配、同 Pool 跨节点优先分散、
  Workload/Lease/fence 重校验、502 失效隔离和安全切换。
- [x] 增加 AI 工作台 Runtime 页面，覆盖 Pool、Workload、平台节点和 Secret 的安全查询
  与现有生命周期操作；平台/租户作用域继续由当前上下文自动确定。
- [x] MCP Servers 页面支持为 `MANAGED_OCI` Server 直接创建或更新 Runtime Pool，
  配置镜像、资源限额、副本、网络白名单和 Secret 引用。
- [x] MCP Servers 页面提供托管部署中心，统一展示镜像校验、节点调度、Runtime
  就绪、能力发现和 Workload 状态，并可直接重新部署、禁用、安全删除和进入工具测试。
- [x] Runtime Pool 镜像、Digest、资源、网络或 Secret 配置变化时自动回收旧副本；
  支持显式重新部署，删除 Server 前强制清理 Publication 与 Runtime Pool。
- [x] 工具测试默认根据 MCP `inputSchema` 生成表单并执行客户端 Schema 校验，
  同时保留高级 JSON 模式。
- [x] 新增 `verify_managed_mcp_e2e.sh`，自动验证注册、Pool 就绪、能力发现、工具调用、
  Workload 替换、重新调用和安全清理。
- [ ] 在至少两个专用 Runtime Worker 的真实 Swarm 环境执行多主机故障验收。

### Phase 3：Skill

- [x] 实现平台/租户统一的 Skill Registry 和不可变版本。
- [x] 实现首版声明式 Skill Manifest Schema、OCI Reference/Digest 与 Content Hash。
- [x] 实现 MCP 能力快照，以及 Tool、Prompt、Resource/Resource Template
  描述符与 Schema Hash 固定。
- [x] 实现发布、激活、废弃生命周期和并发锁。
- [x] 拒绝任意执行字段和未绑定 MCP 能力的 Workflow 引用。
- [x] 增加 AI 工作台技能页面。
- [x] 实现 OCI Registry Artifact 拉取、媒体类型、签名和内容一致性校验。
- [x] 实现首版顺序 Tool Skill Workflow。
- [x] 实现 Workflow/Step 持久化、幂等提交、租约/fencing、检查点恢复和执行页面。
- [x] 完成签名 OCI Artifact、发布、调度、MCP Tool 调用和步骤检查点真实端到端验收。
- [x] 新增可重复运行并验证幂等提交的 `verify_skill_workflow_e2e.sh`。
- [x] 实现短期单次 Capability Token、Gateway 请求精确绑定和 Redis 集群重放防护。
- [x] 实现不可变版本预算、Execution 预算快照、Tool 调用/时长/累计载荷限制。
- [x] 工作台显示版本预算、执行消耗/截止时间和不可逆 Capability Token ID Hash。
- [x] 实现不可变执行审批策略、职责分离、拒绝和审计字段。
- [x] 实现持久化暂停/恢复、Worker 安全检查点和有效时长预算。
- [x] 实现有界条件节点、并行 fork/join、分支隔离、跳过检查点和最坏路径预算。
- [x] 实现 Prompt、Resource/Resource Template 声明式步骤、通用检查点与预算语义。
- [x] 完成 Tool、Prompt、Resource 的签名 OCI Skill 真实端到端验收。

### Phase 4：Agent

- [x] 实现平台/租户 Agent Registry 和不可变版本。
- [x] 实现定义与版本生命周期、活动版本指针和依赖发布期重校验。
- [x] 固定可见且可用的主/回退模型，以及已发布 Skill 精确版本和 Content Hash。
- [x] 实现 Agent Manifest Schema、执行字段拒绝、记忆/预算/审批策略声明。
- [x] 增加 AI 工作台 Agent 定义、版本、发布和废弃页面。
- [x] 新增独立 Agent Runtime 和可横向领取的数据库执行队列。
- [x] 在执行面接入现有模型网关并固定主模型/回退模型。
- [x] 在执行面强制 `Agent -> Skill -> Tool` 和精确 Skill Version/Content Hash。
- [x] 强制执行步骤、循环、并发、Token、费用预算和执行前审批。
- [x] 增加 Agent 执行记录、详情和模型/Skill Trace 页面。
- [x] 实现短期记忆裁剪/摘要、执行快照和摘要完整性哈希。
- [x] 实现 Agent 执行中协作式暂停/恢复和工作台控制页面。
- [x] 实现长期记忆、记忆作用域隔离和受控检索注入。
- [x] 实现人工介入、可恢复等待、结构化输入、取消和超时边界。
- [x] 实现追加式持久化执行事件、排他游标增量读取和有界事件载荷。
- [x] 实现独立 Trace 分页/类型/状态筛选，执行列表不再 N+1 加载完整 Trace。
- [x] 实现数据库时间窗聚合指标和低基数 Micrometer Agent 事件计数。
- [x] 工作台增加运行指标、执行事件时间线和服务端分页 Trace 查询。
- [x] 将 Skill Workflow 隐含的 MCP Resource URI Template 约束增强到模型可见
  输入契约，并在创建子 Skill 前预校验；无副作用的参数拒绝作为可重试 Tool Result
  回传模型，同时保留失败 Trace 和事件。

### Phase 5：Workflow 与生态

- [ ] 实现 Agent Workflow。
- [ ] 实现人工任务、暂停、恢复和补偿。
- [ ] 实现 MCP Tasks 兼容层。
- [ ] 提供 Tool 和 Skill 开发脚手架。
- [x] 提供平台 OCI build/push、Cosign 签名和 SPDX attestation 脚本。
- [ ] 提供 Tool/Skill 开发脚手架和 CI 模板。
- [ ] 完成横向扩展、故障恢复和安全验收。

## 下一步

Phase 2 代码与本地生产化收尾已经完成，Phase 3 Skill Registry、Artifact
供应链校验、持久化 Tool/Prompt/Resource/条件/并行 Workflow、Capability Token、
执行预算、审批、暂停/恢复和真实运行验收已经落地。Phase 4 已完成平台/租户
Agent Registry、不可变版本、固定模型与 Skill Version、独立 Runtime、持久化
Execution/Trace、预算审批、工作台执行页面和真实 `Agent -> Skill -> MCP` 闭环。
短期对话裁剪、确定性有界摘要、Agent + 平台/租户 + 登录主体精确隔离的长期记忆、
不可变检索快照、安全上下文注入，以及模型/Skill 安全检查点上的协作式暂停与恢复
也已完成。人工介入采用独立持久化任务和 `WAITING_HUMAN` 状态，支持运行时重启恢复、
结构化输入后继续、人工取消和版本固定的超时动作。执行生命周期、模型、Skill、
记忆、审批、暂停和人工介入现在统一写入追加式持久事件；工作台通过排他序列游标
增量读取，Trace 独立分页筛选，时间窗指标直接从持久化执行与 Trace 聚合，因此
AI 服务或 Agent Runtime 重启不会清空可观测数据。
下一步按以下顺序推进：

1. 进行 Agent Runtime 多副本并发领取、租约超时和 fencing 故障接管验收；
2. 实现 Agent Workflow、显式补偿节点和长任务编排；
3. 在目标多主机 Swarm 环境运行 `verify_phase2_runtime_failover.sh`，补齐 Runtime
   Worker drain、跨节点重分配、容量恢复和 AppArmor 强制验收。

## 验证记录

- 2026-07-27：新增 MCP 模块和 Gateway 全部通过 Gradle `check`。
- 2026-07-27：使用真实本地 Streamable HTTP 测试 Server 验证
  `initialize`、`tools/list`、Schema 校验和 `tools/call`。
- 2026-07-27：AI 微前端生产构建成功，包含 Gateway、MCP Servers 和 Tools Remote。
- 2026-07-27：Compose、Bake、Shell、JSON 资源与中英文 i18n 校验通过。
- 2026-07-27：本地 OCI 镜像构建并更新容器；内部有令牌状态请求为 200，
  无令牌请求为 401，AI/Common/Gateway 均为 healthy。
- 2026-07-27：OAuth 资源元数据发现、PKCE S256 强制、Resource Indicator Token
  交换、授权阶段/Token 阶段 target 一致性测试通过。
- 2026-07-27：Publication Audience/Scope/主体绑定/Redis 限流安全测试通过。
- 2026-07-27：AI 微前端构建已包含 `workbench/McpPublications` Remote。
- 2026-07-28：重新构建并加载 bootstrap、authorization、common、AI、
  MCP Gateway、host 六个 `somesimpled/open-simplepoint-*` OCI 镜像，
  相关容器镜像 ID 全部对齐且健康。
- 2026-07-28：本地运行验收确认授权服务器仅接受 PKCE S256；
  `/mcp/**` 无令牌返回 401，无效 Publication 的 RFC 9728 元数据返回受控 503，
  不再生成基于 Host 请求头的伪造 resource URI。
- 2026-07-28：数据库确认存在 MCP Server、能力快照、OAuth 状态、
  Publication 和通用能力调用审计五类表。
- 2026-07-28：Gateway/Host/MCP API、Repository、Service、REST 通过相关
  Gradle 编译、测试和 Checkstyle；新增分页、会话复用、Progress、Cancellation
  适配和 Session Affinity 单元测试。
- 2026-07-28：重新构建并更新 bootstrap、AI、MCP Gateway、Host 四个 OCI 镜像；
  容器镜像摘要全部与本地 `somesimpled/open-simplepoint-*` 对齐，11 个常驻容器
  全部 healthy，Gateway 状态为 MCP `2025-11-25` / SDK `2.0.0`。
- 2026-07-28：Consul 已加载 `Mcp-Session-Id` Redis 亲和配置；公开 MCP 无令牌
  返回 401，无效 Publication 元数据返回受控 503。
- 2026-07-28：OAuth Client ID Metadata Documents 的授权服务器包装器、Gateway
  文档端点和客户端选择逻辑通过相关 Gradle `check`；当前动态文档仅接受
  `token_endpoint_auth_method=none` 的 public client。
- 2026-07-28：两个 Gateway 容器在 Consul 以独立实例注册；真实会话故障注入确认
  旧会话返回 502、亲和目录清理且重新初始化切换到存活副本。
- 2026-07-28：独立 Go Tool Runtime 完成镜像校验、容器生命周期和强制沙箱私有 API，
  Go 测试、镜像内测试、Compose/Swarm 配置与本地节点健康/鉴权检查通过。
- 2026-07-28：AI Runtime `api/repository/service/rest` 四层模块完成 Node、Workload、
  Lease 模型及节点注册、容量心跳、失联离线与 generation fencing；相关 Gradle
  `check`、Go 控制面客户端测试、Compose/Swarm 配置均通过。
- 2026-07-28：真实容器完成节点 generation 1 注册、重建后 generation 2 接管、
  旧进程心跳 409 fencing、强制终止后超时 `OFFLINE`、再次恢复为 generation 3
  `READY` 的故障验收；AI 与 Tool Runtime 均恢复 healthy。
- 2026-07-28：完成容量感知选点、`SKIP LOCKED` 并发领取、Workload/Lease 状态机、
  私有节点调度、观测续租、截止时间停止回收和单调 fencing token；真实 OCI 容器
  在租约强制过期后由 token 1 接管为 token 2，旧租约请求返回 409、新租约返回 200，
  节点上仅保留一个实例，STOPPING 后自动进入 CANCELLED、释放租约并删除容器。
- 2026-07-28：根项目 `./gradlew test`（399 tasks）、Runtime 四层与 AI 服务
  Checkstyle/测试、Go `config/controlplane/httpapi/runtime` 测试及前端
  `pnpm typecheck` 通过；AI 与 Tool Runtime 镜像重新构建并更新，本地 12 个常驻
  容器保持 healthy，临时测试 Workload、Lease、容器和镜像已清理。
- 2026-07-28：AI 与 Tool Runtime 控制通道升级为 TLS 1.3 mTLS；AI 使用
  `spiffe://open-simplepoint/ai-control-plane`，节点使用与 `nodeId` 精确绑定的
  `spiffe://open-simplepoint/runtime-node/{nodeId}` URI SAN，旧共享 Token 在
  mTLS 模式下不再生效。
- 2026-07-28：Compose 中只有固定版本的受限 Docker Socket Proxy 挂载宿主 Socket，
  Tool Runtime 以 `65532:65532` 通过内部网络访问代理；`services`、`volumes`
  等非授权 Engine API 返回 403。
- 2026-07-28：Socket Proxy 固定到 `v0.4.2` OCI Index digest；开发 PKI 对节点 ID、
  DNS SAN、密码和剩余有效期执行幂等校验，重复 Compose 启动会复用证书，只有显式
  force-renew 才轮换。
- 2026-07-28：真实 digest 固定 OCI MCP 测试镜像完成调度、启动、观测、停止与删除；
  容器确认非 root、只读根文件系统、无网络、全部 Capability 删除并应用 CPU/内存/PID
  限额，测试 Workload、Lease、容器和镜像已清理。
- 2026-07-28：Runtime 四层与 AI 服务定向 Gradle `check`（112 tasks）、Go
  `config/controlplane/httpapi/runtime/tlsidentity` 测试、Compose/Swarm/Shell 和
  `git diff --check` 通过；13 个常驻服务运行，12 个健康检查全部 healthy。
- 2026-07-28：根项目 `./gradlew test`（385 tasks）、MCP/OAuth/Host 定向
  Gradle `check`、前端 `pnpm typecheck`、Compose/Swarm/Shell/JSON 和
  `git diff --check` 全部通过；12 个常驻容器全部 healthy。
- 2026-07-28：完成平台/租户 Runtime Secret Broker；密文只写保存，调度时经节点
  mTLS 传输，真实 Workload 中以 `0400` 文件和只读 volume subpath 挂载，停止删除后
  节点临时目录同步清理，敏感环境变量与路径穿越请求均被拒绝。
- 2026-07-28：完成独立无状态 Egress Proxy 和短期 HMAC 域名策略；真实 OCI Workload
  只能通过内部网络访问 allowlist 中的 `example.com:443`，未授权 `openai.com`
  返回 403，绕过 Proxy 的直接 Internet 访问失败，同时保持 nonroot、只读根文件系统、
  `cap-drop ALL` 和 `no-new-privileges`。
- 2026-07-28：新增独立 mTLS Image Verifier，固定 Cosign `v3.0.6` 与 Trivy
  `0.70.0` 工具镜像 digest；无节点证书请求返回 401，未签名镜像返回拒绝。
  Runtime 对本地不存在的 digest 镜像在拉取前返回供应链准入失败，验证后镜像仍不存在。
- 2026-07-28：平台镜像发布入口支持 `--push --sign`，对 Registry 返回的不可变
  digest 发布 Cosign 签名和签名 SPDX attestation；Compose 开发环境准入默认关闭，
  Swarm 生产基线默认 fail-closed 开启。
- 2026-07-28：根项目 `./gradlew test`（399 tasks）、Runtime/Egress/Verifier 全部
  Go 测试、前端 `pnpm typecheck`、Compose/Swarm/Shell/JSON 和 `git diff --check`
  通过；本地 15 个常驻容器运行且全部已有健康检查为 healthy，节点 generation 12，
  无测试 Workload 容器残留。
- 2026-07-28：Gradle 前端依赖任务改用 pnpm `.modules.yaml` 作为输出状态，不再递归
  快照整个 symlink `node_modules`；全量测试由原先超过 5 分钟的输出扫描降至 23 秒完成。
- 2026-07-28：Runtime 节点新增有界镜像 digest 缓存上报，控制面完成缓存亲和选点、
  供应链一致的镜像预热和 Runtime Pool；支持 `min/max/desired`、激活副本、空闲回收、
  副本寿命与 `min=0` scale-to-zero，平台/租户作用域仍由授权上下文自动确定。
- 2026-07-28：真实临时 OCI Registry 和带 MCP 标签的 digest 镜像完成 Pool 验收：
  预热后自动达到 `READY(1/1)`，Workload 为 `RUNNING`；推进空闲时间后自动进入
  `IDLE(0/0)`，容器删除。实例保持 nonroot、只读根文件系统、无网络、PID 限额、
  `cap-drop ALL` 和 `no-new-privileges`；临时 Registry、镜像、Pool、Workload、
  Lease 均已清理，Runtime 恢复只允许 `docker.io`。
- 2026-07-28：根项目 `./gradlew test`（399 tasks）、Runtime 四层与 AI 服务
  Gradle `check`（112 tasks）、Runtime Go 全量测试、前端 `pnpm typecheck`、
  Compose/Swarm 配置及 `git diff --check` 通过；AI/Runtime 镜像与容器 ID 对齐，
  本地 15 个常驻容器运行且所有已有健康检查均为 healthy，节点 generation 15。
- 2026-07-29：Runtime 新增环境级 seccomp/AppArmor 策略。自定义 seccomp JSON
  在启动时完成规范化、摘要和 Docker 能力检查，生产配置在策略不可用时 fail-closed；
  Runtime 节点向控制面上报实际强制状态、策略摘要和 AppArmor Profile。Compose
  开发机因宿主内核未启用 AppArmor，仅关闭该项且保持 seccomp 强制；Swarm 基线同时
  要求两项策略，并提供幂等宿主安装脚本。
- 2026-07-29：完成 `MANAGED_OCI + STDIO` MCP Server 注册模型、Runtime Pool
  scale-to-zero 激活、READY Workload 动态端点解析以及 Gateway 专用
  `spiffe://open-simplepoint/mcp-gateway` TLS 1.3 身份。Runtime 将标准
  Streamable HTTP `POST/GET/DELETE` 会话桥接到 OCI 容器 stdio，所有请求继续绑定
  Lease ID 和 fencing token。
- 2026-07-29：真实临时 OCI Registry 和 stdio MCP Server 镜像完成端到端验收：
  Gateway 经 mTLS 初始化容器内 MCP Server，`tools/list` 发现 `echo`，
  `tools/call` 返回 `managed-stdio-ok`。工作负载确认 nonroot、只读根文件系统、
  无网络、CPU/内存/PID 限额、`no-new-privileges` 和自定义 seccomp；临时 Registry、
  镜像、Pool、Workload、Lease 和容器均已清理，Runtime 恢复只允许 `docker.io`。
- 2026-07-29：根项目 `./gradlew test`（399 tasks）、Runtime/MCP/AI 定向
  Gradle `check`（114 tasks）、Runtime Go 镜像内全量测试、前端
  `pnpm typecheck`、Compose/Shell/seccomp JSON 和 `git diff --check` 通过。
  AI、MCP Gateway、Tool Runtime 使用最新 `somesimpled/open-simplepoint-*`
  镜像且均为 healthy；Runtime 节点为 `READY` 并上报 seccomp 策略摘要，冒烟数据
  与受管 Workload 容器残留均为零。
- 2026-07-29：修复 Compose 重建后 Consul 旧实例仍被服务别名健康检查误判为
  passing 的问题。健康检查改为绑定当前容器 `HOSTNAME`，全局只查询 passing 实例并
  在持续 critical 一分钟后自动注销；清理旧 Authorization/Host 注册并重建后，
  登录页、AI 直连健康检查和 Host AI 路由恢复，相关容器均为 healthy。
- 2026-07-29：完成 Phase 2 托管 MCP 会话生产化收尾。Redis 目录只使用外部 Session
  ID 的 SHA-256，保存 Workload/Lease/fence 并设置 TTL；Rendezvous Hash 将新会话
  稳定分布到全部 READY 副本，调度器优先把同一 Pool 副本分散到不同 Runtime 节点。
  每次解析重新校验 Workload、节点心跳和 fence，连接 502 会比较删除目录项并短期隔离
  故障副本，且不会自动重放不确定的 Tool 调用。
- 2026-07-29：新增多主机验收脚本，验证原始 Session ID 不落 Redis 键、会话亲和、
  多 Workload/多节点分布，并可在显式授权后 drain 专用 Runtime Worker、验证 fence
  重分配和自动恢复。当前本机 Swarm 未启用且没有第二个 Worker，因此只完成脚本静态
  校验与本地自动化测试，真实多主机故障注入仍待目标环境执行。
- 2026-07-29：Phase 2 最终回归通过根项目 `./gradlew test`（399 tasks）、
  Runtime/MCP/AI 定向 Gradle `check`、前端 `pnpm typecheck` 与 i18n 同步校验、
  Compose/Swarm/Shell 和 `git diff --check`。修复 Rspack 2.1.4 构建器异常后，
  所有微前端和 AI OCI 镜像成功构建；本地 AI 容器运行最新镜像并为 healthy，
  `http://192.168.145.130:8080/ai/mf/mf-manifest.json` 返回 200。
- 2026-07-29：补齐已实现 Runtime/MCP 能力的工作台查询面。新增 Pool、Workload、
  Node、Secret 页面与安全展示字段，MCP Server 托管 OCI 部署入口、能力快照历史差异、
  Publication 生效 Manifest 预览；新增 Runtime 分级权限并同步中英文资源。
  MCP/Runtime 七个相关模块 Gradle `check`、服务测试、AI 微前端生产构建、全量前端
  `pnpm typecheck` 和 i18n 同步校验通过。
- 2026-07-29：重新构建并更新
  `somesimpled/open-simplepoint-common:local` 与
  `somesimpled/open-simplepoint-ai:local` OCI 镜像；清理重建窗口遗留的旧 Common
  Consul 实例并刷新 Host 路由后，外部 AI Manifest 返回 200，所有常驻服务 healthy，
  Runtime 节点为 `READY` 且心跳有效。
- 2026-07-29：修复 MCP 发布与 Runtime 页面被 Host
  `.nb-inner-content > * { height: 100% }` 放大的布局问题；页面统一为单根
  flex 容器，说明区、页签和表格正确参与剩余高度计算。真实 Chromium 登录回归确认
  发布页、Runtime Pool 页完整可见，AI Manifest 外部地址返回 200。
- 2026-07-29：微前端 Module Federation Manifest 关闭不稳定的深层 assets
  分析，并将各服务 OCI Dockerfile 改为只构建自己的 Gradle Distribution，
  避免每个镜像重复构建全部服务及连续 Rspack 原生进程崩溃；AI OCI 镜像构建成功，
  容器与全部常驻服务 healthy。
- 2026-07-29：通过工作台 API 注册 `OCI Echo MCP E2E`，使用
  `somesimpled/open-simplepoint-mcp-echo:e2e-20260729` 的 digest 固定 OCI 镜像创建
  Runtime Pool，平台调度达到 `READY(1/1)`。Gateway 经 Runtime stdio Bridge 完成
  MCP `2025-11-25` 初始化，`tools/list` 发现 `echo`，`tools/call` 返回
  `Open SimplePoint MCP OCI E2E OK`；Workload 保持 nonroot、只读根文件系统、无网络、
  `cap-drop ALL`、`no-new-privileges`、seccomp 和 CPU/内存/PID 限额。按本轮验收需要，
  Server、Pool、Workload 和镜像保留在本地系统中。
- 2026-07-29：完成托管 MCP 产品化闭环。新增部署中心、Pool/Workload 定向查询、
  配置变更自动替换副本、显式重新部署、安全删除和 Server 依赖保护；工具测试改为
  Schema 表单。Runtime/MCP 定向 Gradle `check`、AI 类型检查、i18n 同步和微前端
  生产构建通过。
- 2026-07-29：`verify_managed_mcp_e2e.sh` 经真实平台登录会话完成临时 Server/Pool
  注册、READY、`echo` 发现与调用、重新部署替换 Workload、二次调用和安全清理；
  真实 Chromium 同时验证部署中心及 Schema 表单调用结果为 `SUCCESS`。AI OCI
  镜像与本地容器已更新，全部常驻健康检查无异常，Host AI Manifest 返回 200。
- 2026-07-29：Phase 3 Skill Registry 首批完成。新增 Skill
  `api/repository/service/rest` 四层模块、三张平台/租户作用域表、首版 Manifest
  JSON Schema、不可变版本、OCI Reference/Digest、Content Hash、MCP Tool
  能力快照与输入/输出 Schema Hash 固定、发布/废弃生命周期和声明式字段安全校验。
  AI 工作台新增技能定义与版本管理页面。
- 2026-07-29：Skill 四层模块 Gradle `check`、服务单元测试、AI 服务整体
  `check`、前端 TypeScript、i18n 同步及生产微前端构建通过。真实管理员会话完成
  Skill 创建、固定现有 OCI Echo MCP 的 `echo` Tool、发布、激活、废弃和删除保护
  验收；Chromium 验证列表、版本抽屉和 Manifest 详情无页面/API 错误，临时数据已清理。
  Common/AI 容器使用最新 OCI 镜像且健康，Host AI Manifest 为 200，
  独立 `tool-runtime` 进程保持运行且健康。
- 2026-07-29：Phase 3 Skill Artifact 供应链切片完成。版本创建会通过有界 OCI
  Distribution 客户端拉取 Manifest、Config 和唯一 Skill Manifest Layer，逐层校验
  原始字节 SHA-256、descriptor size、固定媒体类型和 Config 引用；Registry 内容为
  权威值，管理请求 Manifest 必须与其 canonical JSON 完全一致。
- 2026-07-29：独立 Image Verifier 新增 AI 控制面专用 Artifact Cosign 准入接口，
  精确要求 `spiffe://open-simplepoint/ai-control-plane`，Runtime Node 身份不能调用；
  版本持久化 Config/Manifest Layer Digest、签名策略、策略 Hash 和校验时间，工作台
  同步显示供应链结论与完整追溯字段。
- 2026-07-29：真实临时 OCI Registry 完成端到端验收：错误 Digest 被拒绝，未签名
  Artifact 被拒绝；使用临时 Cosign 公钥签名后成功创建并发布 Skill 版本，数据库字段
  与 Registry 三层 Digest、Verifier 策略 Hash 全部一致。Chromium 验证技能列表、
  版本抽屉和“签名已验证”状态可见且无页面/API 错误。临时 Skill/Version、Registry、
  Cosign 私钥/公钥、登录会话和测试镜像均已清理，Verifier 恢复生产 keyless 基线。
- 2026-07-29：Skill API/Service/REST Gradle `check`、Registry 与供应链单元测试、
  Image Verifier Go 全量测试、AI TypeScript、i18n 同步、AI 微前端生产构建、
  AI 服务整体 `check`、Compose/JSON/Shell 和 `git diff --check` 通过。AI 与
  Image Verifier 容器运行最新 `somesimpled/open-simplepoint-*` 镜像并保持 healthy；
  Host AI Manifest 为 200，独立 Tool Runtime 保持 healthy。
- 2026-07-29：Phase 3 Skill Artifact 供应链最终全量回归通过根项目
  `./gradlew test`（414 tasks）。AI 镜像摘要为
  `sha256:187e8a89988bef8829c7707dedc38eac3471ceb8d2f55cbe0b55a70433c886e2`，
  Image Verifier 镜像摘要为
  `sha256:b890f459ba9287be966ff9ac0b101ab8989a6ad5a5f920ccae6532d386ada2e7`；
  对应容器镜像完全对齐且健康。Verifier 已恢复 keyless、HTTPS Registry 和透明日志
  校验默认策略，AI Manifest 返回 200；现有 Echo 容器均为 Runtime Pool 受管副本，
  未作为临时残留绕过调度器删除。
- 2026-07-29：Phase 3 首版 Skill Workflow 代码完成。新增 Execution/Step
  持久化队列、作用域幂等键 Hash、输入 Hash、`SKIP LOCKED` 横向领取、租约与
  fencing token、短事务步骤检查点、崩溃续跑和固定 MCP
  `serverId/snapshotId/toolName/inputSchemaHash` 调用。参数和输出只允许
  `input.*`/先前 `steps.*` 精确引用；首版 Manifest 只接受顺序 Tool 步骤，
  JSON Schema 子集对未知关键字 fail-closed。
- 2026-07-29：新增 Skill 执行提交、列表和详情 REST API，以及工作台执行输入、
  状态轮询、执行历史、步骤和输出页面；权限资源新增
  `ai.workbench.skills.execute`。Skill Service/REST 与 MCP Service 定向
  Gradle `check`、AI 服务整体 `check`（109 tasks）、AI TypeScript、i18n 和
  微前端生产构建，以及根项目 `./gradlew test`（417 tasks）通过。
- 2026-07-29：本批镜像构建和真实 Skill -> MCP Tool 容器验收暂未执行。宿主
  Docker daemon 的健康检查 exec 持续超时并积累大量不可中断 `runc` 进程，
  所有容器因此被误标 unhealthy，且当前用户无权重启 Docker；需先在宿主执行
  `sudo systemctl restart docker`，恢复后继续镜像更新和端到端验收。
- 2026-07-30：重新构建并更新 Common/Host OCI 镜像，修复工作空间切换时角色与
  授权上下文事件顺序错位导致的首页永久 loading；同时识别 Fetch 跟随 302 后返回
  登录 HTML 的会话失效场景并立即回登录页。真实 Chromium 连续切换两个组织工作空间、
  清除会话和刷新回归全部通过。
- 2026-07-30：使用现有托管 OCI Echo MCP 的固定 Server、能力快照和 `echo` Tool，
  构建符合 SimplePoint 固定媒体契约的 Skill OCI Artifact，经 Cosign 公钥签名和独立
  Image Verifier 准入后创建不可变版本并发布。Artifact Manifest、Config、内容层
  Digest 和策略 Hash 均完成校验，`artifactSignatureVerified=true`。
- 2026-07-30：真实 Skill Workflow 从持久队列进入 Worker，按固定
  `serverId/snapshotId/toolName/inputSchemaHash` 调用 Runtime 中的 MCP Tool，
  Execution 和 Step 均进入 `SUCCEEDED`，输入、步骤输出和最终输出完整可查询。
  新增 `verify_skill_workflow_e2e.sh`，重复验证版本复用、发布、幂等提交、步骤完成和
  预期结果；临时 Registry、公私钥和 Verifier 测试配置已清理，Verifier 恢复
  keyless、HTTPS Registry 和透明日志默认策略。
- 2026-07-30：Phase 3 Capability Token 与执行预算切片完成。Skill Worker 为每个
  未完成步骤签发短期、单次 HMAC 能力，绑定作用域、Skill/版本、Execution/Step、
  固定 Server/Snapshot/Tool、操作和请求/结果上限；Gateway 专用 Workflow 入口
  fail-closed 校验并通过 Redis 原子消费 nonce，原始令牌不持久化、不传给上游。
- 2026-07-30：Skill Version 支持调用次数、时长和累计载荷预算；Execution 提交时
  快照预算与 Deadline，调度器在调用、结果和最终输出边界原子累计。Codec 篡改、
  弱密钥、精确绑定、跨副本重放、预算默认/上限/耗尽测试，以及 AI/Gateway Gradle
  `check`、全前端 TypeScript、AI 微前端生产构建均通过。
- 2026-07-30：重建并更新
  `somesimpled/open-simplepoint-ai:local` 与
  `somesimpled/open-simplepoint-mcp-gateway:local` OCI 镜像；15 个 Compose 服务
  全部运行且健康。最终主体绑定版本的真实 OCI Skill 执行
  `ae7075b7-c400-4391-ad0a-22aed2fe7728` 成功调用托管 Echo MCP Tool，数据库确认
  调用消耗 `1/128`、累计载荷 `242/1048576` bytes、Deadline 和 64 位 nonce Hash，
  Redis 一次性消费键带短 TTL，公开 `/ai/mf/mf-manifest.json` 与 Skill API 均为 200。
- 2026-07-30：Phase 3 Skill 审批与暂停/恢复切片完成。不可变 Manifest 新增
  `spec.approvals.execution`，执行支持 `WAITING_APPROVAL`、`PAUSED`、`REJECTED`
  状态；默认执行申请人与审批人职责分离，只有版本显式允许时才可自批。审批、拒绝、
  暂停和恢复均持久化操作者、时间与原因，并通过独立权限保护。
- 2026-07-30：Worker 实现协作式暂停：排队执行立即暂停，运行中执行在 MCP 调用完成
  后的步骤检查点安全暂停；恢复继续复用既有检查点，审批和暂停等待不消耗有效执行
  时长预算。AI 工作台已补齐审批策略、待审批/已暂停状态、审批/拒绝/暂停/恢复操作
  和执行详情。
- 2026-07-30：使用签名 Skill OCI Artifact 经真实平台完成等待审批、暂停、恢复、
  审批和最终 Tool 调用，执行 `68e0b74e-6f24-4681-a31b-afb37fad2aca` 成功；
  拒绝执行 `b0dfefc7-3954-4b90-be9f-73eddd5e43b2` 保持零 Worker 尝试并以
  `REJECTED` 终止。临时 Registry、Cosign 密钥和登录会话均已清理，Verifier
  恢复 keyless 基线；AI 镜像更新为
  `sha256:3b8eb54f533076bd31ca06864f76ced67f15a1159e04369997c23332a49b9f4c`。
- 2026-07-30：Phase 3 Skill 条件/并行切片完成。`simplepoint.io/v1alpha1` 新增
  `condition` 与 `parallel`，条件仅接受有界声明式运算符；未选分支持久化为
  `SKIPPED`。并行分支使用 Java 21 虚拟线程 fork/join，分支内保持顺序并在编译期
  拒绝跨分支引用；预算按最坏路径计算，暂停只在整个并行组形成检查点后生效。
- 2026-07-30：签名 OCI Skill `1.2.0` 经独立 Image Verifier 准入后完成真实平台
  执行 `43586c40-224b-4d84-8c72-eb6257e425f0`：条件选择 `then`，3 个 Tool 步骤
  `SUCCEEDED`、1 个未选分支步骤 `SKIPPED`，两个并行 Tool 的执行时间重叠；调用预算
  消耗 `3/3`、累计载荷 `480/1048576` bytes，最终输出为
  `{"message":"condition-full-ok","selected":"then"}`。测试版本已废弃并恢复
  `1.0.0` 活动版本，临时 Registry、Cosign 密钥和会话均已清理，Verifier 恢复
  keyless、HTTPS Registry 和透明日志默认策略。AI 容器与
  `somesimpled/open-simplepoint-ai:local` 均运行
  `sha256:7c3dea96f04eb266bd00dae05540102ff327930a4139294c4c9f3fa97fa26940`。
- 2026-07-30：Phase 3 Skill MCP 能力工作流切片完成。`simplepoint.io/v1alpha1`
  新增不可变 Prompt、Resource/Resource Template 绑定和声明式步骤；Execution Step
  统一为通用 MCP 能力检查点模型。Capability Token 改为绑定操作和目标的单次令牌，
  覆盖 `tools/call`、`prompts/get` 和 `resources/read`；MCP Service 在调用前重新
  校验固定描述符 Hash，Resource Template 解析后的 URI 必须仍位于固定模板范围内。
- 2026-07-30：AI 工作台技能页已显示 Tool、Prompt、Resource 绑定数量、固定能力标签
  和通用执行步骤；中英文资源同步。Skill/MCP/Gateway 定向单元测试与 Gradle
  `check`、全前端 `pnpm typecheck`、AI 微前端生产构建和 i18n 校验通过。
- 2026-07-30：托管 OCI MCP 示例镜像
  `somesimpled/open-simplepoint-mcp-echo:e2e-20260730-mcp-all` 同时开放 Tool
  `echo`、Prompt `welcome`、Resource `simplepoint://status` 和 Resource Template
  `document://{documentId}`。平台 Server `959df84c-f1ad-4a0d-acb0-f4019a33a26c`
  与 Pool `df5c5e02-fadd-4c40-9cf5-12e09e9ebca4` 发现不可变快照
  `4c342e4a-a428-4caf-83c0-7d505c98c6d3`，保留为工作台可用示例。
- 2026-07-30：签名 OCI Skill `mcp-capability-workflow-e2e:1.3.0` 经独立 Verifier
  准入，版本 `d9af728e-ae92-46d9-8d1d-cb12e4e3c1a2` 的执行
  `4c8fd359-e128-4944-be5d-d647785640f8` 成功依次调用 Tool、Prompt 和 Resource
  Template，输出 `mcp-tool-ok`、`Welcome SimplePoint to the MCP workbench.` 和
  `Document 42 from the managed MCP runtime.`；旧纯 Tool Skill 的迁移后执行
  `a8279fae-7ac5-473d-b8e0-9a66ffd1b98d` 同样成功。
- 2026-07-30：Skill Step 数据库迁移已兼容旧库与首次初始化并通过重复启动验证，
  旧 Tool 专用列已移除。AI 镜像更新为
  `sha256:df482d0c9d4f5233eacbf9593859112835258d0db1ed078ce8d67f33a1ad034b`，
  MCP Gateway 镜像更新为
  `sha256:07687fb04cd23715cecfcbe1ba53a3ec5b30e3ad0a37f6847f82c12559866afe`；
  15 个 Compose 服务全部运行且健康，Host AI Manifest 返回 200，Image Verifier
  已恢复 keyless、强制 SBOM、HTTPS Registry 和透明日志校验基线。
- 2026-07-30：Phase 4 Agent Registry 第一批完成。新增
  `simplepoint-plugin-ai-agent-api/repository/service/rest` 四层模块和
  `simpoint_ai_agents`、`simpoint_ai_agent_versions`、
  `simpoint_ai_agent_skill_bindings` 三张表；实现平台/租户作用域、不可变版本、
  内容哈希、发布/激活/废弃生命周期、模型可见性校验和已发布 Skill 精确版本固定。
- 2026-07-30：AI 工作台新增 Agent 定义和版本页面，以结构化表单配置 System
  Prompt、主/回退模型、Skill Version、记忆、预算、审批和输入/输出 Schema；
  Agent Manifest 拒绝任意执行字段，发布时重新校验模型与 Skill 状态、作用域及
  Skill Content Hash。Agent 四层模块、AI 服务整体 Gradle `check`，全前端
  `pnpm typecheck`、AI 微前端生产构建和 i18n 校验全部通过。
- 2026-07-30：管理员真实会话创建并发布平台 Agent
  `phase4-agent-registry-e2e:1.0.0`，定义
  `f659b4f3-5b34-4e5c-a795-47164de52d6e` 已进入 `ACTIVE`，活动版本
  `86f30d4d-130f-4f66-b294-3fbf66aec0cb` 固定模型
  `7bc84b11-32f6-404b-8ac0-40517abc0ad1` 和 Skill
  `mcp-capability-workflow-e2e:1.3.0`；Agent/Skill Content Hash 与数据库绑定一致。
  AI 镜像更新为
  `sha256:effd98da94f555bb3470fa31b2755ad11aebc38f3d9a3ac8d95af69830ac38f7`，
  容器健康且公开 `/ai/mf/mf-manifest.json` 返回 200。
- 2026-07-30：Phase 4 Agent Runtime 最小可用闭环完成。新增
  `simpoint_ai_agent_executions`、`simpoint_ai_agent_execution_traces`，
  实现作用域幂等提交、数据库 `SKIP LOCKED` 领取、租约/fencing、模型与 Skill
  检查点、重启恢复、步骤/循环/并发/Token/费用预算和执行前审批。独立
  `simplepoint-service-agent-runtime` 只运行执行 Worker，AI 服务继续承载控制面，
  Skill 发布校验器不会装配到执行进程。
- 2026-07-30：Agent Runtime 通过现有 provider-neutral 模型网关执行固定模型，
  只把不可变 Agent Version 绑定的 Skill 别名暴露给模型；Skill 子执行固定
  Skill Version 与 Content Hash，不能直接调用未绑定 MCP 能力。后台授权上下文
  增加线程级安全载体，工具调用响应中的空文本块在对话检查点被过滤，原始 Trace
  保持不变。
- 2026-07-30：真实平台执行
  `2976b18f-c41e-4a69-a060-70023fb29daa` 成功完成
  `模型 -> mcp_capabilities:1.3.0 -> Tool/Prompt/Resource Template -> 模型总结`；
  Skill 子执行 `331c6105-e45f-494b-a5e8-b6444cf14440` 成功，三段 Agent Trace
  均为 `SUCCEEDED`。AI 镜像为
  `sha256:be76abb3637128c466e97b796d22807b614a454e50355f1ec51d2a3a68268d50`，
  Agent Runtime 镜像为
  `sha256:6975ec69996f1b5beb703e55f68fc4a2fa594dd589d6fe6bf00ac6ae4a819589`；
  本地 16 个常驻 Compose 服务均运行，3 个初始化服务均以 0 退出，已有健康检查
  全部 healthy，Host AI Manifest 返回 200。
- 2026-07-30：Phase 4 Agent 短期记忆与协作式暂停/恢复切片完成。Execution 固定
  记忆开关、消息上限和摘要字符上限；Runtime 使用确定性有界摘要压缩旧对话，
  保持 Tool Call/Result 检查点完整，并只对外暴露压缩数量、修订号、摘要 Hash
  和压缩时间。暂停请求只在模型调用前后或 Skill 等待/结果等安全检查点生效，
  不会中断结果未知的外部调用；恢复继续使用既有子执行和 Trace。
- 2026-07-30：真实 Agent 版本
  `e46db887-a0df-474f-ad70-f2db26ff84bd` 的执行
  `1ecd991f-a1bc-493f-a235-773ce93c2167` 完成
  `PAUSED -> PENDING -> RUNNING -> WAITING_SKILL -> RUNNING -> SUCCEEDED`；
  子 Skill 执行 `bf127948-46f2-49f6-9281-fce112bd2c05` 和三段
  Model/Skill/Model Trace 全部成功。执行最终 `stepCount=2`、`loopDepth=1`，
  压缩消息数为 1、记忆修订号为 1，摘要 Hash 为
  `be4fa241483626f621407568da3214b944028cc39cf3ecebdb4e12c746529c17`。
- 2026-07-30：Agent Execution Schema 迁移兼容旧 PostgreSQL 自动命名状态约束，
  重复启动后只保留包含 `PAUSED` 的新约束，同时将零循环深度与 Manifest 规则对齐。
  最终 AI 镜像为
  `sha256:e0f478ba5d41ce96c757eb333f20e220c0ad333e309296fbe4aff5d27062b68f`，
  Agent Runtime 镜像为
  `sha256:24a9915c803227205d3b841e3e0c1206cffaffef2d2caefd117e75a555c928e6`；
  本地 16 个常驻 Compose 服务全部运行，3 个初始化服务均以 0 退出，已有健康检查
  全部 healthy，Host 服务发现刷新后 AI Manifest 返回 200。
- 2026-07-30：Phase 4 Agent 长期记忆切片完成。新增独立
  `simpoint_ai_agent_memories` 资源和 PostgreSQL FTS/trigram 检索；作用域固定为
  `Agent + SYSTEM/TENANT + tenantId + SUBJECT + authenticated subject`，查询、写入、
  列表和永久删除均使用相同边界。每次执行固定 Top K、相关度、注入字符、单条记录、
  保留天数和最大条数，首轮模型调用前持久化检索结果、字符数、时间与 SHA-256 快照。
  注入内容明确标记为不可信历史数据，不能覆盖 System Prompt 或当前用户请求。
- 2026-07-30：真实 Agent `phase4-agent-registry-e2e:1.4.0`
  (`4854a512-8b31-4c64-9837-8f2b6cc87315`) 开启长期记忆。首次执行
  `198be17f-d283-4ace-8160-3cbdf6ffda68` 检索 0 条并写入记忆
  `c86e557d-7276-4d11-9671-f95b372a8a69`；第二次执行
  `14189f2c-6834-4617-996b-b39cafbaa5e0` 召回 1 条、注入 782 字符，快照 Hash 为
  `83cfa7fa980c74ff68532c69a91aa0c52744a8476daafc62b6042076192eb399`，
  模型 Trace 的实际 instructions 包含不可信边界声明和历史代号
  `LANTERN-ORBIT-731`。管理 API 查询、跨主体不可见和永久删除均经真实容器验证。
- 2026-07-30：Agent 长期记忆四层模块、AI/Agent Runtime Gradle `check`、前端
  TypeScript、i18n 和 AI 生产构建通过；数据库 Schema 重复启动成功。最终 AI 镜像为
  `sha256:1494dabf92b702375f381385ee9bc22abd5447ea404cf469cdcb254935fab9bd`，
  修复 JDBC `Instant` 显式时间戳绑定后的 Agent Runtime 镜像为
  `sha256:7c661443cc33b6000522e22cfb9f957509d54280e500a7f0d454818476038863`；
  两个容器均 healthy，Host AI Manifest 返回 200。
- 2026-07-30：Phase 4 Agent 人工介入切片完成。Agent Version 固定启用状态、
  最大次数、等待时长和 `FAIL|CANCEL` 超时动作；新增独立持久化 Intervention、
  `WAITING_HUMAN`、安全检查点、结构化输入恢复、主动取消和超时终态，工作台可配置
  策略、发起介入、提交输入并查看历史。Agent `phase4-agent-registry-e2e:1.5.0`
  (`48a16761-c7e5-47dc-ba42-dc4f5f5b9ebf`) 的执行
  `328f095c-c21c-4cb6-918d-adbc79a49ba5` 在 Runtime 重启后仍保持等待，提交
  `cn-east / HUMAN-731` 后成功恢复，模型 Trace 保留完整
  `human_intervention_response`；执行
  `6f9a7cb5-3c28-4fca-8ac2-0b7c85f33b8b` 在模型调用前人工取消且当前等待指针清空；
  执行 `acd4a96c-40ca-4735-a5bf-33cec4bbb3b8` 按固定策略进入
  `AGENT_HUMAN_INTERVENTION_TIMEOUT`，Intervention 为 `EXPIRED`，未发生模型调用。
- 2026-07-30：人工介入四层模块、AI/Agent Runtime Gradle `check`、前端
  TypeScript、i18n 和 AI 生产构建通过；Schema 连续重启成功。最终 AI 镜像为
  `sha256:45cfbfb1cfff2fc981a43219fdeb360372f16e283ea244465e4f20aeaeb1afdf`，
  Agent Runtime 镜像为
  `sha256:4e659617dce7c738f6ef7866491b60dc90ceec0e9fffb72b41841a93b82fe20a`；
  本地 16 个常驻服务运行、3 个初始化服务以 0 退出，无 unhealthy，内外网 Host
  AI Manifest 均返回 200。
- 2026-07-30：Phase 4 Agent 持久化可观测执行面完成。新增
  `simpoint_ai_agent_execution_events` 追加式事件表，覆盖 Execution、Approval、
  Pause、Model、Skill、Memory 和 Human Intervention 生命周期；事件使用
  Execution 内单调序列和排他 `after` 游标增量查询，载荷有界且不保存 Token、
  Secret 或完整 Prompt。Trace 新增类型/状态筛选和服务端分页，执行列表改为轻量
  摘要，避免为每行加载全部 Trace 与 Intervention。运行指标按 Agent、作用域和
  时间窗从持久化 Execution/Trace 聚合，并同步低基数
  `simplepoint.agent.events{type,status}` Micrometer 计数。
- 2026-07-30：真实执行 `f925902d-f7d9-4940-8b63-67fba032d117` 在旧 AI 实例产生，
  新实例重启后使用 `limit=2` 首次读取
  `EXECUTION_CREATED, EXECUTION_STARTED`，再从游标 `1` 增量读取至序列 `6`；
  MODEL Trace 类型筛选返回 1 条成功记录，24 小时指标聚合返回 20 次执行和
  4 个 Trace 状态分组，证明事件、Trace 和指标不依赖进程内存。Agent API/Service/
  Repository 四层 Gradle `check`、MockMvc 参数绑定回归测试、前端 TypeScript、
  i18n 和 AI 生产构建均通过。最终 AI 镜像为
  `sha256:f9abf9cc3ade6e52f3a83b2d24cd14f4207de41c65806627f43b124e59f8254e`，
  Agent Runtime 镜像为
  `sha256:72180efd9a7e6dc24607ee3b0654528df7c70a58ce3802a2554edd0fdad45308`；
  两个容器均 healthy，Host 服务发现刷新后 AI Manifest 返回 200。
- 2026-07-30：修复 Agent 调用 Skill 时模型只看到宽泛输入 Schema、实际 Resource
  步骤才校验固定 URI Template，导致前序 Tool/Prompt 已执行后 Skill 失败的问题。
  Runtime 现在从不可变 Skill Manifest 提取直接绑定到 `input.*` 的 Resource Template，
  将格式和示例增强到模型 Tool Schema，并在子执行创建前执行同源模板校验。非法参数
  写入无 `skill_execution_id` 的失败 Skill Trace，生成
  `AGENT_SKILL_ARGUMENTS_INVALID` 可重试 Tool Result，不触发任何 Skill/MCP I/O。
  真实执行 `3426ca9b-0157-4011-b4c0-815956a64d9f` 首次使用 `skill/echo` 被前置拒绝，
  模型根据 `document://{documentId}` 提示改为 `document://echo`，随后子 Skill
  `3a2aa825-a00d-4468-86fe-2f47b38f58ae` 成功，最终 Agent 为 `SUCCEEDED`；执行
  `ca4c1d05-36d6-4afe-bc06-323a31ba592d` 还验证了 Runtime 替换后复用幂等子 Skill
  和持久化 pending call 恢复为完整 `MODEL -> SKILL -> MODEL` 成功 Trace。
  Agent Service/REST/Runtime Gradle `check` 与 AI 微前端生产构建通过。最终 AI 镜像为
  `sha256:487fbb13b874201bd63258230d16a1751078720bb55684bcb6aa1c38220bd995`，
  Agent Runtime 镜像为
  `sha256:9ad01125641c833ae4e0998148d47fdf6a4d817531fcb6679aaffb6fb87348e2`；
  本地 16 个常驻服务全部运行且无 unhealthy，Host AI Manifest 返回 200。
