# API 文档指引

本目录用于存放后端 API 相关文档与契约（OpenAPI/接口说明）。

## 1. 组织结构建议
- `rest/`：REST 接口说明（分服务/领域）。
- `openapi/`：OpenAPI 规范文件（YAML/JSON）。
- `webhook/`：Webhook 事件、回调规范。
- `sdk/`：SDK 约定、示例请求。

## 2. 描述内容要点
- 接口摘要：用途、所属服务/领域。
- 请求定义：HTTP 方法、URL、Query/Header/Body 参数、示例请求。
- 响应定义：字段说明、示例响应、错误码表。
- 安全与鉴权：OAuth2/OIDC、签名/Token 传递方式、权限点说明。
- 幂等与限流：幂等键、重试策略、限流规则。
- 版本与兼容：API 版本号、兼容策略、废弃计划。

## 3. 项目约定（推荐）
- 服务分域：与后端微服务模块一致（如 `simplepoint-services` 内的服务，或示例工程）。
- Base Path：`/{service}/api/{version}/...`（示例）以便网关路由；若有网关统一前缀，保持对齐。
- 鉴权：默认走 OAuth2/OIDC，Token 通过 `Authorization: Bearer <token>` 传递；需在接口文档标明权限点/角色要求。
- Idempotency：变更类接口建议支持幂等键（如 `Idempotency-Key`）。
- 错误码：
  - HTTP 层：2xx/4xx/5xx 规范化。
  - 业务层：统一错误码表（在 `rest/` 里维护），描述 code、含义、解决方案。
- 兼容性：新增字段向后兼容；删除/变更字段需标记 deprecated 并给出迁移期。

## 4. OpenAPI 生成与校验（示例）
- 如使用 Springdoc/Swagger，可在应用运行后访问 `/v3/api-docs` 获取 JSON。
- 建议将生成的 OpenAPI 文件导出至 `doc/api/openapi/`，并在 CI 校验：
```shell
# 示例：导出 OpenAPI（按项目实际命令调整）
curl http://localhost:8080/v3/api-docs > doc/api/openapi/service.json
```
- 多服务场景：按服务分别导出，例如：
```shell
curl http://localhost:8081/v3/api-docs > doc/api/openapi/auth.json
curl http://localhost:8082/v3/api-docs > doc/api/openapi/permission.json
```
- 可选：使用 `swagger-cli`/`speccy`/`openapi-diff` 做契约校验与兼容性检查。

## 5. 变更流程
- 新增/变更接口需同步更新本目录文档与 OpenAPI 文件。
- PR 中说明接口变化、影响范围与兼容性；必要时附回滚方案。
- 若涉及权限/菜单/前端调用，请同步更新相关文档与示例。

## 6. 测试与示例
- 为新增接口提供最小调用示例（cURL/Postman/HTTPie）。
- 校验鉴权与权限点，覆盖典型失败场景（未授权/权限不足/幂等冲突）。
- 需要时提供 Mock 数据或契约测试，保证前后端联调稳定。

## 7. 关联文档
- 系统概览：`doc/architecture/system_overview.md`
- 组件设计：`doc/architecture/component_design.md`
- 项目结构：`doc/architecture/project_structure_diagram.md`
- 前端微前端：`doc/architecture/frontend_microfrontend.md`
- 权限模型：`doc/permission/`
- 部署：`doc/deployment/`
