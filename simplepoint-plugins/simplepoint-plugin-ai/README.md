# SimplePoint AI plugins

`simplepoint-plugin-ai` 是 AI 领域插件的聚合目录，不直接承载具体业务实现。各领域能力按同级插件族拆分，并在需要时继续保持 `api / repository / service / rest` 分层。

当前结构：

```text
simplepoint-plugin-ai/
├── simplepoint-plugin-ai-core-api
├── simplepoint-plugin-ai-core-repository
├── simplepoint-plugin-ai-core-service
├── simplepoint-plugin-ai-core-rest
├── simplepoint-plugin-ai-knowledge-api
├── simplepoint-plugin-ai-knowledge-repository
├── simplepoint-plugin-ai-knowledge-service
└── simplepoint-plugin-ai-knowledge-rest
```

`ai-core` 维护所有其他 AI 插件都会依赖的基础模型：供应商协议、加密凭证、系统/租户混合作用域、模型定义、模型类型、连接测试和远端模型目录同步。系统供应商由平台管理员维护；租户默认可以维护隔离的 BYOK 供应商和模型目录，也可按部署配置关闭。

`ai-knowledge` 是与 `ai-core` 同级的独立插件族，负责知识库配置、文档解析、分块、Embedding 调用编排，以及 PostgreSQL 全文/pg_trgm/pgvector 混合检索。它只通过 `ai-core-api` 使用模型能力，供应商调用细节仍由 core 封装。

后续能力继续按同样方式扩展，例如：

```text
simplepoint-plugin-ai-tool-*
simplepoint-plugin-ai-skill-*
```

领域插件只能依赖 `ai-core-api` 或确有需要的 core 实现层，`ai-core` 不反向依赖知识库、工具或技能模块。
