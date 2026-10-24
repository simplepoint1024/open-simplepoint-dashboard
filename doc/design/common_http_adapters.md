# Common 浏览器入口的可选装配

日期：2026-10-21。属于模块组合计划 S4。已接入身份/租户/资源授权、OIDC、国际化、个人事项和通知接口，保留旧服务路径。

## 依赖与归属

| 领域模块 | 可选 HTTP 模块 | HTTP 产物 | 公共路径 |
| --- | --- | --- | --- |
| `identity` | `identity-http` | `simplepoint-plugin-rbac-http` | `/common/users`、`/common/tenants`、`/common/resources` 等派生子树 |
| `oidc` | `oidc-http` | `simplepoint-plugin-oidc-rest` | `/common/oidc/clients`、`/common/oidc/external-providers` |
| `i18n` | `i18n-http` | `simplepoint-plugin-i18n-rest` | `/common/i18n/` 下的六个控制器子树 |
| `personal` | `personal-http` | `simplepoint-plugin-personal-rest` | `/common/personal` |
| `notification` | `notification-http` | `simplepoint-plugin-notification-rest` | `/common/notifications`、`/common/system/notifications` |

领域聚合包不再依赖 REST 包；它们的扫描范围明确覆盖 API、实现、仓储及初始化包，排除 REST 包。
HTTP 包依赖自己的领域聚合包，并以 `requires` 声明本地依赖；选择 HTTP 模块会自动包含领域模块。
仅选择领域模块时不扫描或开放浏览器控制器，即使 HTTP JAR 同时存在于 classpath 中。
OIDC/国际化 REST 模块保留既有产物名与控制器包名。身份的 20 个控制器从 Common 原样移入新的 HTTP 工程，保留原包名；领域不反向依赖该工程。

HTTP 模块使用 `ControllerHttpRoutes` 从真实控制器映射派生共享 `/common` 子树，声明 `ownsSchema=false`。
表、事务、迁移、RPC 提供方、领域翻译和启动贡献仍由原领域拥有。模块重定位不改逻辑 URL。
构建门禁同时检查领域不依赖已登记的可选 HTTP 适配器、HTTP 适配器不依赖可运行服务。

## 原 Common 服务兼容

Common 服务改为显式依赖五个 HTTP 适配产物，并通过它们取得同一领域实现。
原服务不启用模块前缀挂载，仍提供网关去掉 `/common` 后的内部路径；组合 Servlet 宿主提供带 `/common` 的路径。
上述适配器的 32 个控制器继续复用，方法、参数绑定、业务服务及响应结构不复制。

OAuth 客户端管理同时补齐七个已有接口的操作权限：列表/结构化配置读取使用 `oauthClients.view`，
两种新增入口使用 `.create`，两种修改入口使用 `.edit`，删除使用 `.delete`；保留 Administrator 例外。
这些权限代码原本已在 Common 资源声明中存在，不创建默认授权，也不改变已有普通用户的授予记录。
未取得相应操作权限的用户现在会被方法权限拒绝，查看权限不能用于修改客户端。

## 个人事项与通知

两个领域的扫描仅包含自己的 API、服务、仓储和模块包；REST 工程反向依赖领域聚合，声明本地依赖，
不会被领域聚合的依赖或包扫描隐式带入。`notification` 对 `personal` 的既有必要本地依赖保留；
选择 `notification-http` 会装配两个领域，但不会自动开放 `personal-http`。
因此目前不能将通知与个人事项领域强行拆到不同宿主，HTTP 可选并不代表所有业务依赖已经远程化。

通知的 SSE broker、心跳调度、刷新提示分发和可选 Redis 订阅全部归 `notification-http`；
仅加载领域模块时不创建这些浏览器基础设施。关闭 HTTP 上下文时终止连接、停止心跳，
broker 关闭后拒绝新连接。既有 Redis Pub/Sub 仅提示浏览器重新查询持久收件箱，
不是可靠业务事件或生命周期完成证明；本轮未增加跨服务业务事件，也未替换平台 RabbitMQ/outbox 约定。

通知管理的操作权限、个人数据的身份校验和领域内数据范围检查继续复用原实现。
本轮新增测试覆盖装配与方法授权、原路径等价及真实 MVC SSE 生命周期；没有关闭鉴权来使聚合装配通过。

## 尚待接入的完整运行边界

Common 的菜单和权限资源仍由历史完整拥有方 `common` 注册。此次未将它拆成多个模块提交的片段，
也未重命名资源或恢复授权。生产模块资源/请求范围目录、历史拥有方接管、其他领域 HTTP 入口、
集中字典初始化、前端 remote 和自动网关配置发布继续由总体计划完成。
不能只选择这些 HTTP 模块就宣称已经具备完整的独立管理服务；宿主仍须提供认证、请求范围权限、
服务身份、数据库与启动注册等依赖。OIDC 管理 API 也不等于 OAuth 授权服务器的登录/回调入口。

## 验证

验证使用实际领域/HTTP 产物进行模块、仓储、持久化和契约归属检查。
HTTP 装配测试使用实际控制器与 Spring 方法权限，领域服务显式替换为 mock：
验证领域模式没有控制器、可选模式保留全部方法/路径、单独启用一个适配器、代表性请求，以及权限拒绝和放行。
这些用例不启动真实 OAuth 或业务数据库，不代表完整单体业务验收。

OIDC/国际化验证见[首批记录](../verification/20261021-composition-optional-common-http.json)，身份入口提取及 Common 整体回归见[后续记录](../verification/20261021-composition-identity-http.json)。

个人事项/通知可选装配及生命周期验证见[后续记录](../verification/20261021-composition-personal-notification-http.json)。

## 完整资源贡献接入（2026-10-21）

`common-ui` 接管原 Common 服务的完整 `common` 清单和服务级国际化声明，资源拥有方未更名。
它要求 identity-http、oidc-http、i18n-http、personal-http、notification-http、storage-http 同进程，
因为现有权限描述从这些适配器的真实 MVC 映射生成，完整快照不能由多个宿主各自提交片段。
领域或单个 HTTP 适配器仍可独立选择；这种选择不等于已发布完整 Common 菜单资源。
需要跨宿主发布同一历史快照时，先引入可信权限描述贡献协议或执行显式拥有方迁移，不能用空描述覆盖原字段权限。
对象存储领域不再携带控制器，storage-http 保留全部上传、别名、管理和源对象读取路径。
