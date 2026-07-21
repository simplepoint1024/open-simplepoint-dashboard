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
- 独立知识库模块，支持常见办公文档、PDF、OpenDocument、文本和网页文档解析；
- PostgreSQL pgvector 向量索引、全文检索、pg_trgm 与归一化 RRF 混合检索。

模型基础能力由 `simplepoint-plugin-ai-core-{api,repository,service,rest}` 提供，知识库由独立的 `simplepoint-plugin-ai-knowledge-{api,repository,service,rest}` 提供。AI 服务只负责组合这些插件族，后续可继续装配工具和技能模块。

## 系统与租户作用域

模型供应商和模型目录支持两类归属范围：

- `SYSTEM`：由平台管理员维护，可作为所有组织租户的共享模型；
- `TENANT`：由当前组织租户维护，凭证和模型仅对该租户可见。

租户默认可以维护自己的 BYOK（Bring Your Own Key）供应商和模型目录。如果部署方需要统一由平台托管，可以关闭该能力：

```bash
export SIMPLEPOINT_AI_TENANT_PROVIDER_MANAGEMENT_ENABLED=false
```

个人空间不能维护租户 AI 供应商。平台接口使用 `/platform/ai/**`，租户接口使用 `/tenant/ai/**`，服务端会根据授权上下文强制覆盖资源作用域，客户端不能自行指定 `tenantId`。

知识库同样分为系统和租户作用域，但租户创建知识库不依赖 BYOK 开关：租户可以使用系统共享 Embedding 模型，也可以使用自己的模型；关闭 BYOK 后只保留系统共享模型。知识库、文档和向量分块均校验同一所有权作用域，平台管理员和租户不会通过管理接口互相读取数据。

## 知识库与检索

支持上传 `PDF / DOC(X) / XLS(X) / PPT(X) / ODT / ODS / ODP / RTF / EPUB / TXT / Markdown / CSV / JSON / XML / HTML`，也支持直接录入纯文本。每个知识库可以配置分块大小、重叠字符数、Embedding 模型与输出维度、默认 Top K、最低相关度、向量权重和关键词权重。

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

## 推理接口与出站安全

统一同步接口为 `POST /platform/ai/inference/generate` 和
`POST /tenant/ai/inference/generate`；SSE 接口将路径末尾替换为 `/stream`。
本地模型 ID 使用模型目录记录的 `id`，而不是厂商侧的 `modelId`。

供应商请求默认禁止访问回环、链路本地、私网、组播和其他受限地址，并且不会自动跟随 HTTP 重定向。仅系统级供应商可显式开启“允许访问内网”，用于连接集群内部网关或自托管模型；租户供应商始终不能开启。OpenAI Compatible 供应商允许不设置 API Key，方便接入不鉴权的本地服务。

## 可调参数

| Spring 配置 | 默认值 | 说明 |
| --- | ---: | --- |
| `simplepoint.ai.connect-timeout-seconds` | `10` | 供应商 HTTP 连接超时 |
| `simplepoint.ai.request-timeout-seconds` | `30` | 供应商模型列表与推理请求超时 |
| `simplepoint.ai.model-sync-page-limit` | `1000` | Anthropic 单页模型数量 |
| `simplepoint.ai.model-sync-fixed-delay-ms` | `21600000` | 自动同步间隔（6 小时） |
| `simplepoint.ai.model-sync-initial-delay-ms` | `60000` | 首次自动同步延迟 |
| `simplepoint.ai.tenant-provider-management-enabled` | `true` | 是否允许组织租户维护私有供应商和模型 |
| `simplepoint.ai.generation-max-input-characters` | `1000000` | 单次统一生成请求最大输入字符数 |
| `simplepoint.ai.generation-max-messages` | `200` | 单次统一生成请求最大消息数 |
| `simplepoint.ai.generation-max-tools` | `64` | 单次统一生成请求最大工具数 |
| `simplepoint.ai.generation-max-output-tokens` | `32768` | 统一生成接口允许的最大输出 Token |
| `simplepoint.ai.inference-core-pool-size` | `4` | SSE 推理执行器核心线程数 |
| `simplepoint.ai.inference-max-pool-size` | `32` | SSE 推理执行器最大线程数 |
| `simplepoint.ai.inference-queue-capacity` | `200` | SSE 推理等待队列大小 |
| `simplepoint.ai.streaming-timeout-ms` | `300000` | SSE 连接超时 |
| `simplepoint.ai.knowledge.max-upload-bytes` | `20971520` | 单文档上传大小上限 |
| `simplepoint.ai.knowledge.max-extracted-characters` | `5000000` | 单文档最大提取字符数 |
| `simplepoint.ai.knowledge.embedding-batch-size` | `64` | 文档向量化批大小 |
| `simplepoint.ai.knowledge.stored-vector-dimensions` | `2000` | pgvector 索引存储维度 |
| `simplepoint.ai.knowledge.hybrid-candidate-multiplier` | `5` | 混合检索每个结果的候选召回倍数 |
| `simplepoint.ai.knowledge.hybrid-rrf-k` | `60` | RRF 排名平滑常数 |
| `simplepoint.ai.knowledge.max-retrieval-candidates` | `1000` | 单次检索候选硬上限 |

## 开发启动

```bash
./gradlew :simplepoint-services:simplepoint-service-ai:run
```

服务默认监听 `2888` 端口，开发环境使用 JPA `ddl-auto=update` 自动创建配置与文档表，并通过 `schema.sql` 初始化 pgvector 分块表和索引。
