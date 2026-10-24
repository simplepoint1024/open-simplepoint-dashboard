# 模块 HTTP 命名空间

此适配器让同一套 MVC 控制器在组合宿主中使用稳定逻辑 URL。它不拥有登录、权限、数据库或领域业务，也不把旧微服务启动工程引入聚合进程。

## 声明与选择

HTTP 适配工程拥有自己的 `@PlatformModule`，通过 `requires` 引用必须同进程的领域模块：

```java
@PlatformModule(
    id = "workflow-http",
    packages = {"org.simplepoint.workflow.http", "org.simplepoint.workflow.rest"},
    requires = @ModuleDependency(id = "workflow"))
@ModuleHttp(namespace = "/workflow")
@ModuleMigrations(ownsSchema = false)
@Configuration(proxyBeanMethods = false)
public class WorkflowHttpModule {}
```

宿主选择 `workflow-http` 后自动包含 `workflow`，并使用已存在的 `@EnablePlatformModules` 选择扫描；仅选择 `workflow` 不激活 HTTP 适配器。控制器仍声明领域内部路径，例如 `/definitions`，组合宿主实际暴露 `/workflow/definitions`。宿主仍须装配认证、方法权限、资源范围拦截和运行依赖；引入此包不是完整安全配置。

`ModuleCatalog.plan/deploy` 在启动之前拒绝非法、重复及父子重叠命名空间。`DeploymentPlan.httpRoutes()` 从选中的声明派生逻辑路径到物理宿主的映射，拆分或聚合不要求前端改 URL。该映射目前是安装/网关使用的规划结果，不会自行修改现有 Consul 或网关配置。

## 共享历史前缀

不同领域共用 `/common` 等历史前缀时，使用：

```java
@ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)
```

读取器只检查该模块拥有包中的控制器元数据，从类型级 `@RequestMapping`（包含组合注解及接口继承）
读取全部路径别名。取第一个动态段之前的完整字面段作为拥有的子树：
`/users`、`/platform/users` 分别产生 `/common/users`、`/common/platform/users`；
`/role-resource-configuration/roles/{roleId}` 产生 `/common/role-resource-configuration/roles`。
同一拥有方的子路径被父路径覆盖时自动合并；不同拥有方的相同或父子子树无论部署在何处都拒绝。
HTTP 方法、查询参数或请求头不作为拆分同一子树的依据。

控制器可以声明类型级路径；没有类型级路径时，读取各方法的完整映射（包括组合注解及继承）。每条映射必须至少包含一个静态段，`/{resource}` 等根变量仍拒绝。
不解析 `${...}` / `#{...}`，不接受路径跳转段或重复斜线。单模块扫描至多 10000 个类资源，读取器返回至多 256 个根。
声明为可选条件的控制器仍保留路径，避免不同环境生成不同归属；只读类元数据，不构造 Bean 或初始化控制器。
重复类资源和与所属配置不同 ClassLoader 的控制器会被拒绝。动态插件不能借用已有核心模块的包名取得路径。

同前缀的模块在 MVC 中只挂载一次前缀，各自的实际映射仍须处于所拥有的子树。
未拥有的控制器不能以 `/common/{name}` 或 `/**` 等通配映射捕获已拥有的路径。
`ModuleHttpPlan.namespaces()` 只用于挂载；安装器和网关必须消费 `routes()` 或 `DeploymentPlan.httpRoutes()`。
生产 Common 的控制器/领域扫描归属、完整资源拥有方和启动贡献仍需逐项提取接入；本机制不会自动拆分原宿主。

## MVC 挂载

仅 Servlet 宿主存在 `ModulePlan` 时启动自动配置。框架在每个 `RequestMappingHandlerMapping` 初始化之前设置前缀，包含额外的平台映射器；不只修改 Boot 默认映射器。初始化后检查：

- 领域拥有的控制器必须来自声明 HTTP 的模块。
- 已拥有的路由必须保留声明前缀；其他路径配置不能静默改变公共 URL。
- 其他控制器不能占用已声明命名空间。
- 归属同时核对包边界和配置类 ClassLoader，动态插件的同名类不能借用核心模块归属。

框架自身的 RPC/健康等入口保留原路径。相同控制器内部路径可以出现在不同命名空间，Spring 继续检查各命名空间内的方法映射冲突。完整动态插件安装/卸载与运行期间新映射的边界仍属总体集成验收；本模块的启动校验不能代替该验收。

没有 `ModulePlan` 的原微服务保留原路径，由现有网关去掉 `/workflow` 前缀后转发。新的组合路径已含逻辑前缀，后续网关计划不得再次去掉它；正式网关/安装接入尚待完成。

## 当前范围

Common 的 20 个身份/租户/资源控制器已提取到 `identity-http`，OIDC 和国际化的既有 REST 产物分别声明 `oidc-http` / `i18n-http`，领域聚合包与扫描范围排除控制器，
Common 服务显式依赖这些适配产物。共享 `/common` 子树由控制器声明派生。
其历史完整资源拥有方仍为 `common`，相关资源贡献与请求范围目录的模块化接入尚待完成；
不能把可选 HTTP 装配当作完整独立管理服务。详见[Common 可选 HTTP 适配](../../doc/design/common_http_adapters.md)。

工作流的八个控制器/错误适配类和原 HTTP 测试已迁至 `simplepoint-plugin-workflow-http`，原服务依赖该产物。领域实现不反向依赖 HTTP，HTTP 不依赖可运行宿主，构建边界会检查这两项约束。

资源、菜单和翻译声明位于 `META-INF/simplepoint/modules/workflow-http/`，历史资源拥有方 `workflow` 不变。
适配模块声明 `@ModuleResourceOwner("workflow")` 并接入资源/翻译初始化器，注册依赖自动进入消费与凭据计划。
组合模式按选中声明注册并建立范围目录；旧宿主兼容新路径，不保留重复声明。
完整资源清单不能由两个模块/宿主分别提交片段，规划阶段拒绝共用拥有方。
前端暂仍由原服务打包，多个 remote 的组合发行与挂载仍待 S4 完成。

测试覆盖真实模块选择、两个同名内部路径、未选模块不激活、额外映射器、旧宿主路径、前缀冲突、不同 ClassLoader 归属；实际工作流适配器验证全部映射对等、方法权限、租户范围、稳定错误和声明唯一性。HTTP 用例使用 Spring MVC 测试上下文及显式领域替身，不能视为正式宿主 OAuth/数据库/浏览器验收。


## 根路径协议模块

OAuth/MCP 等协议需要保留 `/.well-known/...`、`/mcp/...` 固定地址时，可使用
`@ModuleHttp(namespace = "/", routes = ...)`。根挂载必须有显式元数据读取器，不能独占整个宿主；
读取器返回有界路径，`/`、通配根、跳转路径或与其他拥有方相交的路径会在规划阶段失败。
MVC 不再添加前缀，实际映射仍执行相同拥有方检查。`.well-known` 是允许的字面路径段，不放开任意点路径。
混合 MVC/native servlet 的适配器可组合控制器元数据与原生 servlet 的权威路径常量；
注册、规划和安全链复用同一常量，不另写一份平行的路由清单。

安全过滤器通过 `ModuleHttpPaths.prefix` 或 `roots` 读取选中模块的实际边界；
仅旧宿主允许没有模块规划。组合宿主中的未选中、未声明 HTTP 或无归属适配器明确失败。
MCP 的 JWT decoder 只服务其专用安全链，不注册成宿主全局 decoder；异常处理器也限定拥有方。


## 模块授权与 Servlet 浏览器

业务 `@ModuleHttp` 默认要求 `ModuleAuthorizationInitializer`，并依赖资源服务器适配库。
初始化器从框架绑定的上下文取得本模块策略客户端；共享资源服务器不得直接注入未归属的 RPC 客户端。
专用协议模块显式设置 `scopedAuthorization=false`，由其边界验证协议身份。

单体可显式开启 `simplepoint.security.browser.enabled=true`；须同时安装 authorization-http 和资源服务器适配。
其浏览器链复用登录/MFA 会话、CSRF、主体撤销检查和模块策略。无 Authorization 请求的 UserInfo
返回最小个人资料；带 Authorization 的请求保留 OIDC 语义。机器协议链顺序在浏览器业务链之前；
无状态资源 API 不读取登录会话。权限增强使用独立请求上下文，不能污染或并发修改会话 SecurityContext。
完整部署、实际浏览器导航与业务验收仍由单体交付计划执行。
