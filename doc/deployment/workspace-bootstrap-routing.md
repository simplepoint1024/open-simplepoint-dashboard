# 工作空间首次加载的网关路由

## 2026-10-27 故障与修复

微服务入口登录成功、`/userinfo` 返回 200 后，`/common/tenants/current`、
`/common/tenants/current-roles` 和国际化接口均返回网关 404。使用实际员工账号复现，
同时使用网关自己的 Consul 凭据确认 `common` 有通过健康检查的实例。
因此本次故障发生在公共 HTTP 入口路由，不是个人空间不存在或账号没有权限。

此前 `/common/**` 仅由 Gateway 的 discovery locator 生成。配置启用
`query-passing=true` 后，生成路由时没有健康实例的服务会被排除。路由快照会缓存；
Consul catalog 心跳按 catalog 索引触发刷新，健康状态恢复不能作为路由一定重新生成的保证。
本次启动日志也显示 host 在 common 就绪之前启动。不要把登录用户重试或重建个人空间
作为这种路由问题的修复方式。

`WorkspaceBootstrapRoutes` 为原微服务 host 提供固定的 `common-api` 路由定义：

- `/common/**` → `lb://common`，只去掉第一段路径，查询参数保留。
- 路由定义独立于外部配置中整体替换的 `routes[n]` 列表；Consul 仍负责健康实例选择。
- 使用 `RouteDefinitionLocator` 合入框架定义链，保留默认 `SaveSession` 和 `TokenRelay`。
  直接提供程序式 `RouteLocator` 不会自动获得这些默认过滤器，不可直接替代。
- 没有可用实例时沿用框架的 503；实例恢复后同一缓存路由即可继续转发。
- 不重试业务写入，不更改租户、上下文、认证、CSRF 或权限规则。

这是原微服务 host 的公共服务入口修复。模块化宿主仍通过模块声明和 `ModuleHttpPlan`
挂载 HTTP 接口，不引入第二套单体路由；本次单体镜像无需修改。

## 回归与部署

`WorkspaceBootstrapRoutesTest` 使用真实的 Gateway 路由解析、缓存、路径谓词、
前缀过滤、会话保存、令牌转发和负载均衡过滤器，替换健康实例选择来源，覆盖：

- 无可用实例启动 → 就绪 → 中断 → 再次就绪，同一路由不依赖刷新恢复。
- 租户列表、角色、授权上下文、菜单、国际化和模块资源的路径与查询参数。
- 503 语义、会话保存、Bearer 转发、租户/上下文头保留和相邻路径不误匹配。

后端命令：

```bash
./gradlew --max-workers=2 -Psimplepoint.frontend.skip=true :simplepoint-services:simplepoint-service-host:test
./gradlew --max-workers=2 :simplepoint-services:simplepoint-service-host:installDist
```

测试共 20 项通过。部署只更新 host 镜像，核对完整发行包文件摘要，执行
`verifyFrontendDistribution`，其余九个服务镜像保持不变。
通过正式 home/single 入口完成真实注册、登录、并发加载、退出、再次登录、浏览器刷新、
权限拒绝和已发布前端资源校验。测试账号通过带审计的账号管理接口停用。
详见 [验证记录](../verification/20261027-workspace-bootstrap-routing.json)。
