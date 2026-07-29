# SimplePoint AI service

`simplepoint-service-ai` 提供统一的模型供应商与模型目录管理，当前支持：

- OpenAI 官方 API；
- Anthropic Claude API；
- 采用 OpenAI `/models` 协议的兼容服务；
- LLM、Embedding、Rerank、图像、音频、审核和多模态模型分类；
- 连接测试、在线模型预览、手动同步，以及默认每 6 小时执行一次的自动同步；
- 统一生成协议：OpenAI Responses、Anthropic Messages 与 OpenAI Compatible Chat Completions；
- 同步生成与 SSE 流式生成、工具调用、严格 JSON Schema 输出和统一 Token 用量；
- 按系统/租户/用户隔离的元数据调用台账，默认不保存提示词与模型输出；
- 按模型配置输入、缓存输入、输出 Token 及单次请求价格，生成不可变费用快照与分币种计费汇总；
- 独立知识库模块，支持常见办公文档、PDF、OpenDocument、文本和网页文档解析；
- 基于 PostgreSQL 租约队列的持久化异步索引，支持多实例领取、失败重试与重启恢复；
- PostgreSQL pgvector 向量索引、全文检索、pg_trgm 与归一化 RRF 混合检索；
- 独立 MCP Gateway 状态、远程 Streamable HTTP MCP Server 注册、能力快照和工具调用；
- 独立 OCI Runtime 节点的注册、容量心跳、进程代际 fencing、离线与超时失联管理；
- OCI Workload 的容量感知选点、租约、实际下发、状态观测、过期接管和停止回收；
- OCI 镜像缓存上报与亲和调度、Runtime Pool 预热、弹性副本和 scale-to-zero；
- 托管 OCI stdio MCP Server 的按作用域激活、动态端点解析和 Gateway 标准会话桥接；
- Runtime 环境 seccomp/AppArmor 状态、策略摘要和 fail-closed 安全基线；
- 平台/租户作用域 Runtime Secret 的加密保存、只写管理和调度时短期文件注入；
- 工作负载域名 Egress Policy、独立 Proxy 以及 Cosign/SBOM/漏洞供应链准入。

模型基础能力由 `simplepoint-plugin-ai-core-{api,repository,service,rest}` 提供，知识库由独立的 `simplepoint-plugin-ai-knowledge-{api,repository,service,rest}` 提供，MCP 控制面由 `simplepoint-plugin-ai-mcp-{api,repository,service,rest}` 提供，OCI Runtime 控制面由 `simplepoint-plugin-ai-runtime-{api,repository,service,rest}` 提供。MCP 协议执行位于独立的 `simplepoint-service-mcp-gateway` 进程，OCI 容器执行位于独立的 `simplepoint-service-tool-runtime-node` 进程。

## 系统与租户作用域

模型供应商和模型目录支持两类归属范围：

- `SYSTEM`：由平台管理员维护，可作为所有组织租户的共享模型；
- `TENANT`：由当前组织租户维护，凭证和模型仅对该租户可见。

租户默认可以维护自己的 BYOK（Bring Your Own Key）供应商和模型目录。如果部署方需要统一由平台托管，可以关闭该能力：

```bash
export SIMPLEPOINT_AI_TENANT_PROVIDER_MANAGEMENT_ENABLED=false
```

个人空间不能维护租户 AI 供应商。管理接口统一使用 `/workbench/**`（经服务路由访问时为 `/ai/workbench/**`）；服务端从当前授权上下文自动确定平台或租户作用域，并覆盖客户端传入的作用域字段，客户端不能自行指定 `tenantId`。

知识库同样分为系统和租户作用域，但租户创建知识库不依赖 BYOK 开关：租户可以使用系统共享 Embedding 模型，也可以使用自己的模型；关闭 BYOK 后只保留系统共享模型。知识库、文档和向量分块均校验同一所有权作用域，平台管理员和租户不会通过管理接口互相读取数据。

## OCI Runtime 控制面

Runtime 节点是平台级基础设施，通过 `/internal/runtime/nodes/**` 主动注册、续租和
报告离线，接口使用 TLS 1.3 双向认证保护。稳定 `nodeId` 是业务标识，数据库主键仍使用
内部 UUID；每次节点进程启动都会生成新的进程实例 ID，控制面递增 generation 并拒绝
旧进程的心跳。超过心跳租约的节点会被定时标记为 `OFFLINE`。

平台管理员可以通过 `/workbench/runtime/nodes` 查询节点与容量。平台或租户上下文
可以通过 `/workbench/runtime/workloads` 提交、分页查询和停止自己的 Workload；
作用域仍由当前授权上下文确定。控制面使用行锁与 `SKIP LOCKED` 并发领取任务，综合
节点心跳、单任务限制、聚合 CPU/内存和并发数选择节点，创建 Lease 后调用节点私有
API。每次重新分配递增 fencing token，旧租约不能再查询、停止或删除新容器。

调度器持续观测容器并续租；租约过期、节点进程变化或节点失联时重新分配，达到执行
截止时间或用户请求停止时会停止并删除容器、释放 Lease，并把最终状态保存在 Workload
审计记录中。

平台或租户可通过 `/workbench/runtime/pools` 声明可复用 OCI MCP 副本池。Pool
支持最小/最大/目标副本、激活副本、预热节点数、空闲超时和单副本寿命；`min=0`
时会在空闲后自动回收到零。节点心跳只上报有界的镜像 digest 集合，调度器优先选择
已有目标镜像的节点；预热仍调用相同的 Image Verifier 供应链准入。

MCP Server 可选择 `REMOTE + STREAMABLE_HTTP` 或
`MANAGED_OCI + STDIO`。托管定义不接收 Endpoint、Bearer/OAuth 凭证或私网开关；
控制面按当前平台/租户作用域找到唯一 Pool，必要时激活 scale-to-zero 副本，再把
RUNNING Workload 的 Runtime 地址、Lease ID 和 fencing token 交给 Gateway。
Gateway 使用专用 SPIFFE/mTLS 身份连接 Runtime，Runtime 将标准 Streamable HTTP
会话桥接到 OCI 容器 stdio，平台服务和 Gateway 都不加载 Tool 代码。

托管会话使用 Redis 集群目录绑定到 Runtime 副本。目录键只包含外部
`Mcp-Session-Id`、Server 和作用域的 SHA-256，不保存原始 Session ID；目录值只保存
Workload ID、Lease ID 和 fencing token，并有固定 TTL。新会话通过 Rendezvous Hash
分布到全部 READY 副本，同一健康会话保持稳定亲和。每次解析都会重新校验 Workload
状态、节点心跳和 fence；节点失联或连接返回 502 时删除匹配目录项并短暂隔离故障副本，
下一次请求重新分配。平台不会自动重放失败的 Tool 调用，避免非幂等副作用被执行两次。
Runtime 调度器会先减少同一 Pool 在目标节点上的已有副本数，再比较镜像缓存和节点
利用率，因此只要容量允许，Pool 副本会优先跨节点分散。

Runtime 节点注册会携带实际 seccomp/AppArmor 强制状态、seccomp 策略摘要和
AppArmor Profile。生产节点必须在启动前加载版本化策略并验证 Docker 安全能力；
必需策略不可用时节点拒绝注册和执行，不能静默降级。

Workload 只保存当前平台或租户作用域的 Secret 引用。控制面在实际下发前解密并通过
mTLS 发送给节点，节点写成有数量和大小上限的 `0400` 文件、只读挂载到容器，并在
Workload 删除时清理。允许出站的 Workload 必须声明 DNS allowlist，只能通过独立
Egress Proxy 使用 HTTP/HTTPS，不能直接访问互联网。生产 Runtime 在检查或拉取镜像前
调用独立 Image Verifier，按不可变 digest 校验 Cosign 签名、签名 SBOM 和漏洞策略；
任何超时、服务不可用或校验失败都会拒绝执行。

AI 控制面使用独立 PKCS12 身份库和信任库：

```bash
export SIMPLEPOINT_TOOL_RUNTIME_MTLS_ENABLED=true
export SIMPLEPOINT_TOOL_RUNTIME_MTLS_KEY_STORE=/run/simplepoint/runtime-pki/ai/identity.p12
export SIMPLEPOINT_TOOL_RUNTIME_MTLS_KEY_STORE_PASSWORD='replace-with-store-password'
export SIMPLEPOINT_TOOL_RUNTIME_MTLS_TRUST_STORE=/run/simplepoint/runtime-pki/ai/trust.p12
export SIMPLEPOINT_TOOL_RUNTIME_MTLS_TRUST_STORE_PASSWORD='replace-with-store-password'
export SIMPLEPOINT_AI_RUNTIME_MCP_SESSION_DIRECTORY_TTL=10m
export SIMPLEPOINT_AI_RUNTIME_MCP_FAILURE_QUARANTINE=10s
```

AI 身份 URI SAN 固定为 `spiffe://open-simplepoint/ai-control-plane`；节点身份必须
精确等于 `spiffe://open-simplepoint/runtime-node/{nodeId}`。mTLS 控制连接器默认监听
内部端口 `2889` 并强制客户端证书，节点的 `2891` 私有端口同样不得对公网开放。
关闭 mTLS 时仍保留共享 Token 兼容模式，但不应在生产部署中使用。

## 知识库与检索

支持上传 `PDF / DOC(X) / XLS(X) / PPT(X) / ODT / ODS / ODP / RTF / EPUB / TXT / Markdown / CSV / JSON / XML / HTML`，也支持直接录入纯文本。每个知识库可以配置分块大小、重叠字符数、Embedding 模型与输出维度、默认 Top K、最低相关度、向量权重和关键词权重。

上传文件时，接口只校验文件名、类型和大小，将原文件保存到统一对象存储并返回 `PENDING` 文档；下载原文件、Tika 文本解析、分块、Embedding 和索引替换全部由后台 Worker 完成。直接录入的文本会随文档记录持久化后进入同一任务链路。后台通过 OAuth2 服务凭证和 Service Router 下载原文件，并同时校验对象 ID、实际存储租户、来源服务和大小上限，不复用已经结束的用户请求上下文。

任务保存在 PostgreSQL 中，通过租约和 `SKIP LOCKED` 支持多个 AI 服务实例并行处理；失败任务按指数退避重试，服务重启后会继续领取。重新索引期间旧索引仍可检索，只有新一代索引完整生成后才会原子替换。

检索模式包括：

- `VECTOR`：pgvector 余弦相似度；
- `KEYWORD`：PostgreSQL 全文排名与 pg_trgm 字符相似度；
- `HYBRID`：向量与关键词分别召回候选，再按知识库权重执行归一化 RRF 融合。

PostgreSQL 必须包含 `vector` 和 `pg_trgm` 扩展。Docker Compose 会基于 `postgres:16-alpine` 构建轻量的 `simplepoint/postgres-pgvector:16`，Swarm 使用 `pgvector/pgvector:0.8.1-pg16`。AI 服务启动时会幂等创建扩展、分块表、GIN 索引和 HNSW 索引。向量索引存储上限为 2000 维；对于原生输出超过 2000 维的模型，应在知识库中设置不超过 2000 的输出维度。

## 凭证加密

供应商 API Key 使用 AES-GCM 加密后落库，接口只接收、不回传明文。启动服务前必须通过环境变量设置稳定且足够随机的主密钥：

```bash
export SIMPLEPOINT_AI_CREDENTIAL_ENCRYPTION_KEY='replace-with-a-long-random-secret'
```

服务在没有主密钥时仍可启动和查看不含凭证的配置，但不能新增凭证、测试连接或同步远端模型。生产环境应从密钥管理系统注入该值；更换主密钥前需要迁移已有密文。

MCP Server 的 Bearer Token 使用同一 AES-GCM 凭证组件加密，且不会从管理接口回传。AI
控制面和独立 Gateway 之间还必须配置相同的内部服务令牌：

```bash
export SIMPLEPOINT_MCP_GATEWAY_INTERNAL_TOKEN='replace-with-a-long-random-token'
```

租户 MCP Server 禁止访问私有网络；只有系统级注册可以显式开启内网访问。能力发现会生成
不可变快照，工具调用只能使用当前 READY 快照中的工具，并且审计台账只保存参数和结果哈希，
不保存业务正文。

## 模型调试、兼容 API 与出站安全

模型管理页通过 `POST /workbench/models/{modelDefinitionId}/debug/stream` 打开纯对话调试。
该接口只接受消息列表，模型由路径固定，不接受系统提示词、采样参数、工具或 JSON Schema
等调试设置。

使用平台签发的模型 API Key 还可以通过 `/v1/chat/completions`、`/v1/responses`
和 `/v1/messages` 调用 OpenAI Chat Completions、OpenAI Responses 与 Anthropic
Messages 兼容接口；详细请求格式和无状态能力边界参见 `doc/ai/model_api.md`。

供应商请求默认禁止访问回环、链路本地、私网、组播和其他受限地址，并且不会自动跟随 HTTP 重定向。仅系统级供应商可显式开启“允许访问内网”，用于连接集群内部网关或自托管模型；租户供应商始终不能开启。OpenAI Compatible 供应商允许不设置 API Key，方便接入不鉴权的本地服务。

## 模型计费

模型目录可按 ISO 4217 币种配置每百万输入 Token、缓存输入 Token、输出 Token
价格和单次成功请求固定价格。启用计费后，调用开始时会把当时价格复制到调用台账，
调用成功后根据供应商返回的 Token 用量计算费用；后续修改模型价格不会改变历史费用。
失败或取消的调用标记为不计费，成功但未配置价格的调用会在计费看板单独统计。

平台与租户都通过 `/workbench/billing/summary` 查询最长 366 天的汇总，通过
`/workbench/billing/invocations` 查看调用明细；结果作用域由当前授权上下文确定。
不同币种始终分别展示，不进行无汇率依据的跨币种相加。

## 可调参数

| Spring 配置 | 默认值 | 说明 |
| --- | ---: | --- |
| `simplepoint.ai.connect-timeout-seconds` | `10` | 供应商 HTTP 连接超时 |
| `simplepoint.ai.request-timeout-seconds` | `30` | 供应商模型列表与推理请求超时 |
| `simplepoint.ai.model-sync-page-limit` | `1000` | Anthropic 单页模型数量 |
| `simplepoint.ai.model-sync-fixed-delay-ms` | `21600000` | 自动同步间隔（6 小时） |
| `simplepoint.ai.model-sync-initial-delay-ms` | `60000` | 首次自动同步延迟 |
| `simplepoint.ai.tenant-provider-management-enabled` | `true` | 是否允许组织租户维护私有供应商和模型 |
| `simplepoint.ai.mcp.gateway-base-url` | `http://mcp-gateway:2890` | 独立 MCP Gateway 内部地址 |
| `simplepoint.ai.mcp.gateway-request-timeout` | `45s` | Gateway 控制请求超时 |
| `simplepoint.ai.runtime.heartbeat-interval` | `10s` | Runtime 节点建议心跳间隔 |
| `simplepoint.ai.runtime.heartbeat-timeout` | `35s` | Runtime 节点心跳租约时长 |
| `simplepoint.ai.runtime.stale-scan-interval` | `10s` | 失联节点扫描间隔 |
| `simplepoint.ai.runtime.dispatch-timeout` | `30s` | AI 控制面对节点私有生命周期请求的超时 |
| `simplepoint.ai.runtime.mtls-enabled` | `false` | 启用 Runtime TLS 1.3 双向认证 |
| `simplepoint.ai.runtime.mtls-control-port` | `2889` | Runtime 节点注册与心跳的内部 HTTPS 端口 |
| `simplepoint.ai.runtime.mtls-node-identity-prefix` | `spiffe://open-simplepoint/runtime-node/` | 节点证书 URI SAN 前缀 |
| `simplepoint.ai.runtime.workload-lease-duration` | `45s` | Workload 活跃租约时长 |
| `simplepoint.ai.runtime.workload-dispatch-retry-interval` | `5s` | 调度下发失败后的重试间隔 |
| `simplepoint.ai.runtime.workload-observation-interval` | `5s` | 运行中 Workload 状态观测间隔 |
| `simplepoint.ai.runtime.workload-scheduler-interval` | `1s` | 调度器轮询间隔 |
| `simplepoint.ai.runtime.workload-scheduler-batch-size` | `16` | 单轮领取的最大 Workload 数 |
| `simplepoint.ai.generation-max-input-characters` | `1000000` | 单次统一生成请求最大输入字符数 |
| `simplepoint.ai.generation-max-messages` | `200` | 单次统一生成请求最大消息数 |
| `simplepoint.ai.generation-max-tools` | `64` | 单次统一生成请求最大工具数 |
| `simplepoint.ai.generation-max-output-tokens` | `32768` | 统一生成接口允许的最大输出 Token |
| `simplepoint.ai.provider-max-response-bytes` | `10485760` | 供应商同步响应最大字节数 |
| `simplepoint.ai.provider-max-stream-bytes` | `20971520` | 供应商单次流式响应累计最大字节数 |
| `simplepoint.ai.provider-max-stream-line-characters` | `1048576` | 供应商流式响应单行最大字符数 |
| `simplepoint.ai.inference-core-pool-size` | `4` | SSE 推理执行器核心线程数 |
| `simplepoint.ai.inference-max-pool-size` | `32` | SSE 推理执行器最大线程数 |
| `simplepoint.ai.inference-queue-capacity` | `200` | SSE 推理等待队列大小 |
| `simplepoint.ai.streaming-timeout-ms` | `300000` | SSE 连接超时 |
| `simplepoint.ai.knowledge.max-upload-bytes` | `20971520` | 单文档上传大小上限 |
| `simplepoint.ai.knowledge.max-extracted-characters` | `5000000` | 单文档最大提取字符数 |
| `simplepoint.ai.knowledge.embedding-batch-size` | `64` | 文档向量化批大小 |
| `simplepoint.ai.knowledge.max-chunks-per-document` | `5000` | 单文档允许生成的最大分块数 |
| `simplepoint.ai.knowledge.stored-vector-dimensions` | `2000` | pgvector 索引存储维度 |
| `simplepoint.ai.knowledge.index-worker-concurrency` | `2` | 单实例异步索引并发数 |
| `simplepoint.ai.knowledge.index-claim-batch-size` | `2` | 每次轮询领取的任务数 |
| `simplepoint.ai.knowledge.index-poll-delay-ms` | `1000` | 索引任务轮询间隔 |
| `simplepoint.ai.knowledge.index-lease-seconds` | `300` | Worker 任务租约时长，Embedding 批次间会续租 |
| `simplepoint.ai.knowledge.index-max-attempts` | `3` | 索引任务最大执行次数 |
| `simplepoint.ai.knowledge.index-retry-initial-delay-seconds` | `10` | 首次失败重试延迟，后续指数退避 |
| `simplepoint.ai.knowledge.hybrid-candidate-multiplier` | `5` | 混合检索每个结果的候选召回倍数 |
| `simplepoint.ai.knowledge.hybrid-rrf-k` | `60` | RRF 排名平滑常数 |
| `simplepoint.ai.knowledge.max-retrieval-candidates` | `1000` | 单次检索候选硬上限 |

## 开发启动

```bash
./gradlew :simplepoint-services:simplepoint-service-ai:run
```

服务默认监听 `2888` 端口，开发环境使用 JPA `ddl-auto=update` 自动创建配置与文档表，并通过 `schema.sql` 初始化 pgvector 分块表和索引。
