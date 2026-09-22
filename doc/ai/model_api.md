# 模型兼容 API

SimplePoint AI 服务可以把后台配置的模型统一暴露为 OpenAI Chat Completions、OpenAI Responses 和 Anthropic Messages API。调用方只需要平台签发的模型 API Key，不需要浏览器登录态或 OAuth Token。

## 签发 API Key

- 平台管理员在“AI 系统管理 → 系统 API Key”签发系统 Key。系统 Key 只能访问系统作用域模型。
- 租户管理员在“租户 AI 工作台 → 模型 API Key”签发租户 Key。租户 Key 可以访问共享的系统模型和当前租户自己的模型。
- 完整 Key 仅在创建或轮换成功时显示一次；数据库只保存带服务端 pepper 的 HMAC 校验值。
- Key 可以设置启用状态、过期时间和每分钟请求上限，也可以随时轮换或吊销。

统一网关地址是 `http://<host>:8080/ai/v1`。直接访问 AI 服务时也可以使用 `http://<ai-service>:2888/v1`。

生产环境必须设置一个稳定的高强度 `SIMPLEPOINT_AI_API_KEY_HASH_PEPPER`。更换该值会让已有 Key 全部失效。

## 模型列表

```bash
curl http://localhost:8080/ai/v1/models \
  -H 'Authorization: Bearer spk_xxx.yyy'
```

响应中的 `id` 是调用接口时应传入的模型 ID。供应商模型 ID 在当前 Key 的可见范围内重名时，接口会改用平台内部模型配置 ID，避免路由到错误模型。

## OpenAI 格式

```bash
curl http://localhost:8080/ai/v1/chat/completions \
  -H 'Authorization: Bearer spk_xxx.yyy' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "your-model-id",
    "messages": [{"role": "user", "content": "你好"}],
    "stream": false
  }'
```

OpenAI SDK 只需将 `base_url` 设置为 `http://localhost:8080/ai/v1`，并把平台签发的 Key 作为 `api_key`。

`OPENAI` 和 `OPENAI_COMPATIBLE` 上游的 Chat Completions 请求使用原生 HTTP 转发，保留工具选择、多候选结果、结构化输出、推理、音频等上游支持的字段。流式响应以 `data: [DONE]` 结束。网关会为流式计费向上游请求 usage；只有调用方设置 `stream_options.include_usage=true` 时才向调用方发送额外的 usage chunk。Anthropic 上游仍使用基础协议转换。

## OpenAI Responses 格式

```bash
curl http://localhost:8080/ai/v1/responses \
  -H 'Authorization: Bearer spk_xxx.yyy' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "your-model-id",
    "instructions": "回答要简洁",
    "input": "你好",
    "stream": false
  }'
```

OpenAI SDK 使用同一个 `base_url` 和 `api_key`，可以直接调用 `client.responses.create(...)`。

### 原生 Responses 通道

Codex CLI / ChatGPT Desktop 的本地 Codex 工作流应选用 `OPENAI` 协议供应商（管理界面中选择 OpenAI，Base URL 可指向支持原生 Responses 的服务）。`OPENAI_COMPATIBLE` 类型表示 Chat Completions 上游，不等同于支持 Responses。网关不会把只支持 Chat Completions 的模型自动变成完整的 Responses 服务。

原生通道只替换请求中的 `model` 为模型配置对应的上游 ID，保留其他请求字段和响应 JSON：

- `reasoning`、`include`、推理及 compaction 加密历史、`text.verbosity`、`context_management`；
- function/custom 工具、namespace、grammar、工具结果和 `tool_choice`、`parallel_tool_calls`；
- `phase`、多模态内容、内置工具定义，以及上游以后新增的字段；
- 完整 SSE 事件，包括推理、工具输入增量、未知事件、事件 ID、多行 data、心跳和结束事件；
- 上游 HTTP 错误状态、错误 JSON、`x-request-id`、限流和 `Retry-After` 头。

功能是否可用仍由上游模型、接口版本和账号能力决定。HTTP 错误不会统一改写为 502；非 JSON 错误转换为 OpenAI 错误封套，上游凭证不会回传。客户端断开、队列拒绝、请求/流超时会关闭上游连接；流缺少终止事件时按失败处理。

| 接口 | 用途 |
| --- | --- |
| `POST /v1/responses` | 普通/流式响应、工具往返、原生后台响应 |
| `POST /v1/responses/compact` | 原生上下文压缩，不用普通文本摘要替代加密 compaction |
| `POST /v1/responses/input_tokens` | 原生输入 Token 计数 |
| `GET /v1/responses/{id}` | 查询响应；保留查询参数及 SSE 续读 |
| `GET /v1/responses/{id}/input_items` | 查询响应输入及分页 |
| `POST /v1/responses/{id}/cancel` | 取消上游后台响应 |
| `DELETE /v1/responses/{id}` | 删除上游保存的响应 |

`store` 和 `background` 按请求传给上游。平台仍不保存提示词或响应正文，但上游可能按其默认值或显式 `store=true` 保存数据；需要无状态调用时显式设置 `store=false`，每轮携带完整输入历史。`store=false` 的响应通常不能通过 ID 从上游重新读取，网关不会模拟上游存储。

### 归属与路由

新增 `simpoint_ai_gateway_resources` 表只保存平台 Key、供应商、模型、上游 response/item ID 和台账关联。响应 ID 在返回客户端前持久化，重启及多副本部署后仍可路由。`previous_response_id`、`item_reference` 及响应查询/取消/删除必须属于同一个平台 Key；每次重新检查模型与供应商权限，不能跨供应商续接。平台 Key 的轮换保留 Key 实体 ID，已吊销或过期 Key 无法访问。

关闭 Hibernate 自动更新的环境，部署前执行 [`scripts/sql/ai-openai-gateway-resources.sql`](../../scripts/sql/ai-openai-gateway-resources.sql)。该脚本可重复执行，不迁移或删除现有业务数据。

平台不向上游透传调用者的 Authorization、Cookie、OpenAI-Project 或 OpenAI-Organization；使用供应商已配置的凭证。客户端幂等键和会话标识按平台 Key 隔离。后台响应保持台账 RUNNING，后续查询得到终态时更新用量；没有查询终态时不会自动轮询上游。

本次范围为本地 Codex 工作流，以下入口未提供：Conversations API、Files/Vector Stores/Containers 管理，以及 Responses WebSocket。调用者不能直接引用共享上游凭证下的任意 `file_id`、vector store 或已有 container；文件/图片可使用上游支持的内联内容或 URL。设置 `supports_websockets=false` 使用 HTTP/SSE。ChatGPT 账号、云任务或插件连接能力不由模型网关替代。

### 非原生上游

非原生上游的 Responses 转换通道仍可使用无状态的基础文本、图片 URL、函数及纯文本 custom 工具转换。无法等价转换的 namespace、grammar、内置工具、推理、上下文管理、存储等选项会返回 `400`。不再静默丢弃内置工具，也不再接受推理配置后假装生效。

### Codex CLI 配置

将以下内容合并到**用户级** `~/.codex/config.toml`，不要放到仓库的 `.codex/config.toml`。`model` 必须是当前平台 Key 调用 `/ai/v1/models` 得到的 ID。

```toml
model = "your-model-id"
model_provider = "simplepoint"

[model_providers.simplepoint]
name = "SimplePoint"
base_url = "https://your-host/ai/v1"
env_key = "SIMPLEPOINT_MODEL_API_KEY"
wire_api = "responses"
supports_websockets = false
stream_idle_timeout_ms = 300000
```

在运行 Codex 的环境中配置 `SIMPLEPOINT_MODEL_API_KEY` 为平台签发的 `spk_...`。桌面端使用同一套自定义供应商配置时，启动桌面进程的环境也必须包含该变量；终端临时 export 不一定会传入从桌面图标启动的程序。也可以按官方认证文档使用 `requires_openai_auth=true` 配合 API Key 登录，此时移除 `env_key`，避免两种鉴权配置混用。

官方依据：[自定义供应商配置](https://learn.chatgpt.com/docs/config-file/config-advanced)、[配置字段](https://learn.chatgpt.com/docs/config-file/config-reference)、[桌面/CLI API Key 认证](https://learn.chatgpt.com/docs/auth)。

### 回归验证

使用 JDK 21：

```bash
./gradlew :simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-implementation:test \
  :simplepoint-services:simplepoint-service-ai:test -Psimplepoint.frontend.skip=true
```

本地 HTTP 上游测试覆盖原生字段、SSE、压缩、错误/限流、Key 隔离、后台用量、取消及断流；H2 测试执行建表脚本并通过生产仓库基类验证跨 Session 的持久化。

可选真实 Codex CLI 回归（使用本地模拟模型和临时配置，不读取真实 Key、不调用计费模型）：

```bash
SIMPLEPOINT_CODEX_BINARY="$(command -v codex)" ./gradlew \
  :simplepoint-services:simplepoint-service-ai:test \
  --tests '*NativeOpenAiClientTest' -Psimplepoint.frontend.skip=true
```

此测试启动真实 CLI，经过实际控制器和原生传输，执行本地只读命令、回传工具结果并接收最终答复。桌面 UI 和生产上游仍需独立联调，不能用模拟测试替代其验收。

## Anthropic 格式

```bash
curl http://localhost:8080/ai/v1/messages \
  -H 'x-api-key: spk_xxx.yyy' \
  -H 'anthropic-version: 2023-06-01' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "your-model-id",
    "max_tokens": 1024,
    "messages": [{"role": "user", "content": "你好"}],
    "stream": false
  }'
```

Anthropic SDK 的 `base_url` 设置为 `http://localhost:8080/ai`，SDK 会请求 `/v1/messages`。接口支持文本、URL/base64 图片、工具调用、Token 用量和 Anthropic 原生 SSE 事件。

## 错误与限流

- OpenAI 路径返回 `{ "error": { ... } }` 格式。
- Anthropic 路径返回 `{ "type": "error", "error": { ... } }` 格式。
- 无效、禁用、吊销或过期 Key 返回 `401`；越权返回 `403`；超过每分钟请求上限返回 `429`。原生通道保留上游 HTTP 错误状态；网络或转换通道的上游故障返回网关错误。
- 所有成功进入模型运行时的请求都会写入现有 AI 调用台账，但不会保存提示词或模型输出正文。
