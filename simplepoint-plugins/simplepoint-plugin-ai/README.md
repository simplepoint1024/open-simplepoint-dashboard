# SimplePoint AI plugins

`simplepoint-plugin-ai` 是 AI 领域插件的聚合目录，不直接承载具体业务实现。各领域能力按同级插件族拆分，并在需要时继续保持 `api / repository / service / rest` 分层。

当前结构：

```text
simplepoint-plugin-ai/
├── simplepoint-plugin-ai-core-api
├── simplepoint-plugin-ai-core-implementation
├── simplepoint-plugin-ai-core-implementation
├── simplepoint-service-ai
├── simplepoint-plugin-ai-knowledge-api
├── simplepoint-plugin-ai-knowledge-implementation
├── simplepoint-plugin-ai-knowledge-implementation
└── simplepoint-service-ai
```

`ai-core` 维护所有其他 AI 插件都会依赖的基础模型：供应商协议、加密凭证、系统/租户混合作用域、模型定义、模型类型、连接测试和远端模型目录同步。它同时提供 OpenAI Responses、Anthropic Messages 和 OpenAI Compatible 的统一生成协议、同步/SSE 调用、工具与结构化输出、Embedding，以及不保存请求/响应正文的调用台账。供应商 HTTP 出站默认执行 SSRF 防护，只有系统级配置可以显式访问内网。系统供应商由平台管理员维护；租户默认可以维护隔离的 BYOK 供应商和模型目录，也可按部署配置关闭。

供应商配置只需选择一个内置云厂商、本地运行时或 `CUSTOM`，调用协议由供应商自动确定，Base URL 和模型发现 URL 均可独立覆盖。模型发现会逐个读取能力元数据并结合发现端点语义解析类型，不根据供应商名称或模型 ID 猜测；无法确定的模型保存为 `OTHER`，可在模型配置中手动修正。发现响应包含价格时会归一化为每百万 Token/每请求价格并自动同步；一旦人工修改类型或价格，后续同步不会覆盖对应人工值。

`ai-knowledge` 是与 `ai-core` 同级的独立插件族，负责知识库配置、文档解析、分块、Embedding 调用编排，以及 PostgreSQL 全文/pg_trgm/pgvector 候选召回和 RRF 混合检索。它只通过 `ai-core-api` 使用模型能力，供应商调用细节仍由 core 封装。

后续能力继续按同样方式扩展，例如：

```text
simplepoint-plugin-ai-tool-*
simplepoint-plugin-ai-skill-*
```

领域插件只能依赖 `ai-core-api` 或确有需要的 core 实现层，`ai-core` 不反向依赖知识库、工具或技能模块。
