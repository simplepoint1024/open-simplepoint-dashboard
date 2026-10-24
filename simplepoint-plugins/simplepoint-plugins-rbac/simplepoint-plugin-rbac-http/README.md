# Identity HTTP 适配模块

`identity-http` 提供身份、租户和资源授权领域的浏览器入口。原 Common 服务中的 20 个控制器原样移入本工程，
保持包名、类型名、接口映射、响应和权限声明；不复制业务实现、不注入领域仓储，也不拥有数据库表。
身份、租户、资源授权目前仍共享同一个本地领域数据边界，不能仅按 Gradle 子工程拆成独立服务。

## 选择与依赖

- 需要浏览器入口时依赖本产物并选择 `identity-http`，自动包含本地 `identity` 依赖。
- 只选择 `identity` 时，领域扫描范围不含 `core.rest`、`tenant.rest`、`resource.rest` 或 HTTP 配置包。
- 接口使用 `@ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)`，从真实控制器声明派生子树。
- 与 `oidc-http`、`i18n-http` 聚合时共用 `/common` 前缀；不同拥有方子树冲突在规划时拒绝。
- HTTP 适配器不依赖任何可运行服务。领域聚合包不反向依赖 HTTP 适配器，构建门禁检查这两个边界。

Common 旧宿主显式依赖本产物，继续使用去掉 `/common` 的内部路径；组合 Servlet 宿主保留逻辑公共前缀。
`/tenants` / `/platform/tenants` 等原有别名、当前用户偏好与目录路径继续由同一控制器处理。

## 运行边界

此模块是可选入口，不是完整可独立启动的管理服务。宿主仍需装配认证、方法权限、请求范围权限、
领域存储和启动基础设施。历史完整菜单/权限资源仍由 `common` 拥有，本次没有拆分其快照或改写资源 ID。
资源贡献模块化、字典初始化、前端产物和正式宿主接入由[总体计划](../../../doc/design/modular_service_composition_plan.md)跟踪。

验证包含实际模块/仓储/持久化/契约归属、28 个 Common 相关控制器的原路径与组合路径对照，
以及 Common 完整业务 HTTP 回归；新的组合 MVC 用例使用真实控制器和显式领域服务 mock。
原文件搬迁前后的哈希及发行包唯一性记录在[验证记录](../../../doc/verification/20261021-composition-identity-http.json)。
