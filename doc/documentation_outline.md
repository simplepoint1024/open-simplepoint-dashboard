# open-simplepoint-dashboard 文档补全大纲

本文档用于规划 `open-simplepoint-dashboard` 的文档补全范围、优先级与推荐结构，目标是降低项目上手门槛，帮助使用者、二开开发者、贡献者和运维人员快速理解并正确使用本项目。

## 1. 文档目标

1. 让新用户可以在最短时间内完成本地启动、登录和基本功能验证。
2. 让开发者理解项目定位：它不是传统后台模板，而是偏平台内核、插件化、可扩展的企业应用框架。
3. 让二开开发者能快速定位服务边界、权限模型、插件机制、Schema 驱动前端和多租户能力。
4. 让运维人员能理解配置中心、依赖中间件、部署方式和故障排查路径。
5. 让贡献者清楚模块分层、编码约定、测试边界和提交流程。

## 2. 目标读者

- **使用者**：希望快速跑起来并体验系统功能的人。
- **业务开发者**：希望基于现有模块做二次开发的人。
- **平台开发者**：希望扩展插件、权限、数据能力和前端模块联邦的人。
- **运维 / DevOps**：负责部署、配置、排障和发布的人。
- **开源贡献者**：准备提交 PR、补测试、修文档的人。

## 3. 建议阅读顺序

1. `README.md`：项目定位、能力概览、入口说明。
2. `doc/quick_start.md`：最小可运行路径、依赖、默认账号与端口。
3. `doc/architecture/system_overview.md`：系统目标、角色和核心能力。
4. `doc/architecture/project_structure_diagram.md`：模块分层与源码入口。
5. `doc/architecture/service_topology.md`：核心服务职责与调用关系。
6. `doc/permission/permission_model.md`：权限、角色、资源、菜单关系。
7. `doc/design/plugin_architecture.md`：插件加载、依赖与生命周期。
8. `doc/deployment/local_development.md` / `docker_swarm_deployment.md`：本地与部署说明。
9. `doc/troubleshooting/common_issues.md`：常见问题与排障路径。

## 4. 文档结构建议

下面按目录列出建议保留、完善或新增的文档。

| 目录 | 文档 | 状态建议 | 说明 | 优先级 |
| --- | --- | --- | --- | --- |
| 根目录 | `README.md` | 完善 | 明确定位、核心特性、适用场景、与常见 admin 框架的区别、快速入口 | P0 |
| `doc/` | `documentation_outline.md` | 已新增 | 文档补全总纲与优先级说明 | P0 |
| `doc/` | `quick_start.md` | 新增 | 单机最小启动路径、依赖服务、启动顺序、默认访问地址、最小验证步骤 | P0 |
| `doc/` | `glossary.md` | 新增 | 统一术语：Tenant、Context、Plugin、Schema、Authority、Resource 等 | P1 |
| `doc/architecture/` | `system_overview.md` | 完善 | 项目目标、边界、角色、能力地图 | P0 |
| `doc/architecture/` | `project_structure_diagram.md` | 完善 | 模块职责、源码定位、当前真实目录结构 | P0 |
| `doc/architecture/` | `component_design.md` | 完善 | 核心组件职责、时序、交互边界 | P1 |
| `doc/architecture/` | `deployment_diagram.md` | 完善 | 运行时组件、依赖中间件、服务拓扑 | P1 |
| `doc/architecture/` | `service_topology.md` | 新增 | `host`、`common`、`authorization`、`auditing`、`dna` 的边界、职责与调用关系 | P0 |
| `doc/architecture/` | `plugin_architecture.md` | 新增 | 插件 classloader、安装/卸载、依赖关系、生命周期与扩展点 | P0 |
| `doc/architecture/` | `multi_tenant_model.md` | 新增 | 多租户隔离、上下文传递、实体与仓储层约定 | P0 |
| `doc/architecture/` | `schema_driven_ui.md` | 新增 | `/schema` 接口、按钮声明、字典选项、前端渲染流程 | P0 |
| `doc/architecture/` | `frontend_microfrontend.md` | 完善 | Nx 工作区、Module Federation、打包到后端静态资源、远程模块约定 | P1 |
| `doc/architecture/` | `dev_environment.md` | 完善 | JDK/Node/Gradle/pnpm 版本、开发依赖、推荐 IDE 配置 | P1 |
| `doc/design/` | `base_abstractions.md` | 新增 | `BaseController`、`BaseServiceImpl`、`BaseRepository` 等基础抽象说明 | P0 |
| `doc/design/` | `authorization_flow.md` | 新增 | 登录、鉴权、权限解析、菜单过滤、按钮显示链路 | P0 |
| `doc/design/` | `data_initialization.md` | 新增 | 初始化数据、种子数据、i18n 初始化与执行顺序 | P1 |
| `doc/design/` | `amqprpc_design_usage.md` | 完善 | AMQP RPC 使用场景、模块位置、调用约定、示例 | P1 |
| `doc/design/` | `extending_a_business_module.md` | 新增 | 新增一个业务能力时 API / repository / service / rest / frontend 的完整路径 | P0 |
| `doc/api/` | `api_conventions.md` | 新增 | 统一响应体、分页、错误码、认证头、租户头、OpenAPI 约定 | P0 |
| `doc/api/` | `authentication_api.md` | 新增 | 登录、令牌、OIDC / OAuth2 相关接口说明 | P1 |
| `doc/api/` | `permission_api.md` | 新增 | 角色、权限、菜单、资源相关接口说明 | P1 |
| `doc/api/` | `schema_api.md` | 新增 | `/schema` 的请求、返回结构、字段约定、前端使用方式 | P0 |
| `doc/api/` | `examples.http.md` | 新增 | 典型 curl / HTTP 示例，便于联调和回归验证 | P1 |
| `doc/database/` | `core_domain_model.md` | 新增 | 核心实体、关联关系、基础审计字段、软删除字段说明 | P1 |
| `doc/database/` | `tenant_and_security_schema.md` | 新增 | 租户、安全、角色、用户、权限相关表说明 | P1 |
| `doc/database/` | `audit_schema.md` | 新增 | 审计日志与留存相关模型说明 | P2 |
| `doc/database/` | `migration_strategy.md` | 完善 | 版本升级、数据迁移与兼容策略 | P2 |
| `doc/permission/` | `permission_model.md` | 新增或完善 | RBAC、资源、菜单、按钮、数据权限之间的关系 | P0 |
| `doc/permission/` | `authorization_context.md` | 新增 | `AuthorizationContext` 的解析来源与生效范围 | P0 |
| `doc/permission/` | `resource_menu_mapping.md` | 新增 | 资源、菜单、按钮、路由映射规则 | P1 |
| `doc/permission/` | `best_practices.md` | 新增 | 权限命名规范、菜单设计规范、常见误区 | P1 |
| `doc/deployment/` | `local_development.md` | 新增 | 本地依赖、启动顺序、联调方式、最小 smoke check | P0 |
| `doc/deployment/` | `docker_swarm_deployment.md` | 完善 | 当前仓库已有能力的推荐部署文档 | P0 |
| `doc/deployment/` | `docker_compose_deployment.md` | 新增 | 适合开发和演示环境的轻量部署方式 | P1 |
| `doc/deployment/` | `configuration_matrix.md` | 新增 | 各服务关键配置项、环境变量、默认值与依赖关系 | P0 |
| `doc/deployment/` | `production_checklist.md` | 新增 | 生产发布前检查项：安全、日志、备份、监控、扩缩容 | P1 |
| `doc/troubleshooting/` | `common_issues.md` | 新增 | 启动失败、连接中间件失败、权限异常、前端加载失败等问题 | P0 |
| `doc/troubleshooting/` | `debug_guide.md` | 新增 | 如何定位插件、权限、Schema、租户上下文问题 | P1 |
| `doc/troubleshooting/` | `faq.md` | 新增 | 新用户高频问题与简答 | P1 |
| `doc/changelog/` | `release_process.md` | 新增 | 版本发布规范、兼容说明、变更记录要求 | P2 |
| `doc/examples/` | `create_a_plugin_walkthrough.md` | 新增 | 从零实现一个插件的完整教程 | P1 |
| `doc/examples/` | `create_a_crud_module_walkthrough.md` | 新增 | 从实体到前端页面的完整 CRUD 例子 | P0 |
| `doc/examples/` | `frontend_remote_module_walkthrough.md` | 新增 | 新增一个微前端 remote 的完整教程 | P1 |

## 5. 文档补全优先级

### P0：最先补齐

这些文档直接决定外部开发者能否理解项目并跑起来：

- `README.md`
- `doc/quick_start.md`
- `doc/architecture/service_topology.md`
- `doc/architecture/plugin_architecture.md`
- `doc/architecture/multi_tenant_model.md`
- `doc/architecture/schema_driven_ui.md`
- `doc/design/base_abstractions.md`
- `doc/design/authorization_flow.md`
- `doc/design/extending_a_business_module.md`
- `doc/api/api_conventions.md`
- `doc/api/schema_api.md`
- `doc/permission/permission_model.md`
- `doc/permission/authorization_context.md`
- `doc/deployment/local_development.md`
- `doc/deployment/configuration_matrix.md`
- `doc/troubleshooting/common_issues.md`
- `doc/examples/create_a_crud_module_walkthrough.md`

### P1：第二阶段补齐

这些文档决定二开效率和平台理解深度：

- `doc/glossary.md`
- `doc/architecture/component_design.md`
- `doc/architecture/deployment_diagram.md`
- `doc/architecture/frontend_microfrontend.md`
- `doc/architecture/dev_environment.md`
- `doc/design/data_initialization.md`
- `doc/design/amqprpc_design_usage.md`
- `doc/api/authentication_api.md`
- `doc/api/permission_api.md`
- `doc/api/examples.http.md`
- `doc/database/core_domain_model.md`
- `doc/database/tenant_and_security_schema.md`
- `doc/permission/resource_menu_mapping.md`
- `doc/permission/best_practices.md`
- `doc/deployment/docker_compose_deployment.md`
- `doc/deployment/production_checklist.md`
- `doc/troubleshooting/debug_guide.md`
- `doc/troubleshooting/faq.md`
- `doc/examples/create_a_plugin_walkthrough.md`
- `doc/examples/frontend_remote_module_walkthrough.md`

### P2：后续完善

这些文档更偏治理、深水区设计与长期维护：

- `doc/database/audit_schema.md`
- `doc/database/migration_strategy.md`
- `doc/changelog/release_process.md`

## 6. 每篇文档建议模板

为保持一致性，建议每篇文档至少包含以下结构：

```md
# 标题

## 1. 背景与目标
## 2. 适用范围
## 3. 关键概念
## 4. 设计 / 用法 / 流程
## 5. 配置项 / 约束
## 6. 示例
## 7. 常见问题
## 8. 关联文档
```

## 7. 编写规范

- 优先使用中文说明，必要时补充英文术语。
- 图表优先使用 Mermaid，保持可在代码仓库中直接预览。
- 示例优先提供可复制的命令、配置片段和最小代码片段。
- 文档中所有路径、模块名、服务名必须与仓库实际结构一致。
- 对“规划中 / 未实现 / 实验性”能力明确标识，避免误导使用者。
- 同一概念不要在多个文档中重复定义，重复内容使用链接跳转。

## 8. 推荐补文档顺序

如果按投入产出比排序，建议按下面顺序推进：

1. 先补 `README.md` 和 `doc/quick_start.md`，解决“看不懂、跑不起来”的问题。
2. 再补服务拓扑、插件机制、多租户、权限链路、Schema 驱动等核心设计文档。
3. 然后补 API 约定、部署矩阵、排障文档，解决“能跑但不会改”的问题。
4. 最后补完整示例和数据库设计，解决“能改但不敢扩展”的问题。

## 9. 完成标准

当文档补全到一个可对外推荐的程度时，建议至少满足以下标准：

- 新用户按照文档可在本地完成最小启动并看到页面。
- 开发者可以根据文档新增一个后端模块并接入前端页面。
- 开发者可以根据文档理解插件是如何安装、加载、卸载和扩展的。
- 使用者可以根据文档理解权限、菜单、资源和按钮之间的关系。
- 运维可以根据文档完成基础部署并定位常见启动问题。

