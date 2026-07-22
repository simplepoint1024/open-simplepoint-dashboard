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

接口支持文本、图片 URL、工具调用、结构化输出参数、Token 用量和 SSE 流式响应。流式响应以 `data: [DONE]` 结束。

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

OpenAI SDK 使用同一个 `base_url` 和 `api_key`，可以直接调用 `client.responses.create(...)`。接口支持：

- 字符串输入和消息数组输入；
- `input_text`、`output_text`、图片 URL、`function_call` 和 `function_call_output`；
- `max_output_tokens`、`temperature`、`top_p`；
- Responses 格式的函数工具，以及 `tool_choice: "auto"`/`"none"`；
- `text.format` 文本、JSON Object 和 JSON Schema 结构化输出；
- 非流式 `response` 对象，以及 `response.created`、`response.output_text.delta`、`response.completed` 等原生 SSE 事件。

网关当前按无状态方式运行，不保存提示词和响应正文，所以响应中的 `store` 固定为 `false`，暂不支持 `previous_response_id`、`conversation`、后台响应、托管 prompt、内置工具和 reasoning 参数。传入这些无法保证语义一致的参数时会返回明确的 `400 invalid_request_error`，不会静默忽略。

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
- 无效、禁用、吊销或过期 Key 返回 `401`；越权返回 `403`；超过每分钟请求上限返回 `429`；上游模型错误返回 `502`。
- 所有成功进入模型运行时的请求都会写入现有 AI 调用台账，但不会保存提示词或模型输出正文。
