# 常见问题（Common Issues）

## 1. 背景与目标

本文收敛当前仓库最常见、最容易踩坑的启动和联调问题，重点覆盖：

1. 本地依赖没起全
2. OIDC 登录链路不通
3. 菜单 / remote / Schema 按钮异常
4. 租户与权限上下文异常

本文只记录**当前实现里真实存在的高频问题**，不追求列出所有理论故障。

## 2. 先看这几个入口

遇到问题时，先确认下面这些地址是否正常：

| 项目 | 默认地址 |
| --- | --- |
| Host UI | `http://127.0.0.1:8080` |
| Authorization | `http://127.0.0.1:9000` |
| Consul UI | `http://127.0.0.1:8500` |
| Vault UI | `http://127.0.0.1:8200/ui` |
| RabbitMQ 管理台 | `http://127.0.0.1:15672` |

如果这些基础入口都不通，先不要排菜单或按钮问题。

## 3. 启动类问题

### 3.1 `authorization` / `common` 一启动就报 Consul 或 Vault 连接失败

**典型现象**

- 启动日志里直接出现 Consul 配置导入失败
- Vault 不可达
- 认证相关配置或密钥读取失败

**常见原因**

1. 只起了数据库 / Redis / RabbitMQ，没有起 Consul / Vault
2. 起了 Consul / Vault，但没有执行 `init_profile.sh`
3. 使用了 `docker-compose`，但起完依赖后没有继续执行 `init_profile.sh`

**建议处理**

如果你走本机 CLI 路径，优先执行：

```bash
./scripts/shell/start_developer.sh
```

它会顺序完成：

1. 启动本地 Vault dev server
2. 启动本地 Consul dev agent
3. 通过 Terraform 把开发配置写入 Consul，并在 Vault 初始化 key

如果你走容器路径，则先执行：

```bash
docker compose -f docker/docker-compose.yaml up -d
./scripts/shell/init_profile.sh
```

不要在 compose 已经占用 `8500` / `8200` 端口时，再执行 `start_dev_consul.sh` 或 `start_dev_vault.sh`。

### 3.2 PostgreSQL 端口通了，但 JPA 仍然连库失败

**典型现象**

- `authorization` / `common` 启动时出现数据库认证失败
- `password authentication failed`

**常见原因**

当前仓库默认的 `docker/docker-compose.yaml` 已经使用 `postgres/postgres`，但如果你复用了旧容器、旧数据卷，或连接了另一套本地 PostgreSQL，依然可能和 Consul 开发配置不一致。

**建议处理**

建议处理：

1. 确认当前实际数据库账号密码和 `infrastructure/consul/config/simplepoint/config/application/application.properties` 一致
2. 如果你沿用了旧的 PostgreSQL 数据，删除旧容器 / 数据卷后按当前 compose 重新创建，或同步修改 Consul 开发配置后再执行一次 `init_profile.sh`

## 4. 登录与认证问题

### 4.1 打开 host 后不断跳登录，或登录后又回到登录页

**典型现象**

- 访问 host 后进入 `/login`
- 登录成功后又回到登录页
- OIDC 回调后会话没有稳定建立

**常见原因**

1. `authorization` 服务尚未启动完成
2. 你没有按当前开发配置推荐地址访问
3. redirect URI / issuer 与当前访问域名不一致

**当前实现的关键点**

开发配置里，host / common 的 OIDC 相关地址默认写的是：

- issuer：`http://127.0.0.1:9000`
- redirect：`http://127.0.0.1:2555/login/oauth2/code/oidc`

所以本地开发时，优先使用：

```text
http://127.0.0.1:8080
```

而不是随意换成其他 IP / 域名。

### 4.2 直接访问资源服务接口返回 401

**典型现象**

- 直接请求 `http://127.0.0.1:7000/roles`
- 明明已经在 host 登录过，还是 401

**原因**

host 登录态是 host 自己的 cookie。  
如果你绕过 host 直接打资源服务，资源服务并不会自动继承这层 host 会话。

**建议处理**

直接调资源服务时请自己带上：

```http
Authorization: Bearer <token>
X-Tenant-Id: <tenantId>
X-Context-Id: <contextId>
```

## 5. 菜单与微前端问题

### 5.1 登录成功了，但左侧菜单是空的

**典型现象**

- 页面能进
- 但 `/common/menus/service-routes` 返回空 routes

**常见原因**

1. 当前租户下没有有效角色 / 权限 / 功能链路
2. `X-Tenant-Id` / `X-Context-Id` 不正确
3. 当前用户不属于指定租户
4. 菜单和功能绑定关系本身没配好

**建议排查**

1. 先查当前租户是否正确
2. 再看 `/common/tenants/permission-context-id` 是否拿到了新的 `contextId`
3. 再看 `/common/menus/service-routes` 返回结果
4. 最后回看菜单 - 功能 - 权限绑定链路

### 5.2 菜单有了，但 remote 页面加载失败 / `mf-manifest.json` 404

**典型现象**

- 菜单能点开
- 浏览器 Network 里 `/{service}/mf/mf-manifest.json` 返回 404

**常见原因**

1. 对应 remote 前端资源没有构建 / 发布到服务静态目录
2. 菜单 `component` 的首段服务名和 remote 名称不一致
3. host 根据约定路径拼出的 remote 入口不正确

**建议排查**

当前 host 默认按：

```text
/{name}/mf/mf-manifest.json
```

拼 remote 入口。  
因此要确认：

1. `/common/mf/mf-manifest.json`
2. `/auditing/mf/mf-manifest.json`
3. `/dna/mf/mf-manifest.json`

这些资源是否真的存在。

## 6. 权限与按钮问题

### 6.1 能看到页面，但很多按钮不见了

**典型现象**

- 页面能打开
- 但“新增 / 编辑 / 删除 / 配置”按钮缺失

**原因**

`/schema` 返回的 `buttons` 会被后端按当前 `AuthorizationContext` 过滤。  
因此按钮缺失通常说明：

1. 当前用户确实没有这个 authority
2. 当前 `tenantId / contextId` 不对
3. 权限刚改过，但前端还在使用旧上下文

**建议处理**

1. 重新获取 `contextId`
2. 切换一次租户，或重新登录
3. 直接打开对应资源的 `/schema`，看按钮是不是已经在后端响应里缺失

### 6.2 登录后访问接口返回 403

**典型现象**

- 已认证
- 但接口返回 403

**常见原因**

1. `@PreAuthorize` 要求的 authority 不满足
2. 当前角色属于其他租户
3. 当前操作需要租户所有者，而你只是普通成员

特别是：

- 角色授权
- 用户分配角色
- 租户配置套餐

这类操作，当前服务层会再次校验“是不是当前租户所有者或管理员”。

## 7. Schema 与字典问题

### 7.1 `/schema` 能返回，但字典下拉没有选项

**典型现象**

- 表单字段正常显示
- 但 select 选项为空

**原因**

前端会根据字段里的：

```text
x-ui.dictCode
```

额外请求：

```text
/common/platform/dictionaries/options?dictionaryCode=...
```

所以问题可能出在：

1. 字段没有正确声明 `dictCode`
2. 字典接口不可用
3. 当前租户 / 权限下字典结果为空

### 7.2 页面字段顺序和预期不一样

**建议排查**

先看实体字段上是否有 `@Order`。  
当前 schema 字段顺序主要来自 `x-order`，它又来自实体字段上的 `@Order` 注解。

## 8. 上下文与租户问题

### 8.1 切换租户后菜单 / 按钮还是旧的

**常见原因**

- 前端缓存里的 `contextId` 还没刷新
- 当前权限变更后 `permissionVersion` 已变化，但浏览器还在沿用旧上下文

**建议处理**

1. 重新调用 `/common/tenants/permission-context-id`
2. 重新登录
3. 清理前端缓存后再进入系统

### 8.2 指定租户后提示“当前用户未加入指定租户”

**原因**

当前 `AuthorizationContextServiceImpl` 在非管理员 + 非 `default` 租户下，会显式校验：

1. 租户是否存在
2. 当前用户是否属于该租户

所以这个报错通常不是前端问题，而是租户成员关系本身不满足。

## 9. 一页式排查顺序

如果你想最快定位问题，推荐按这个顺序：

1. 中间件是否都起来：PostgreSQL / Redis / RabbitMQ / Consul / Vault
2. `start_developer.sh` / `init_profile.sh` 是否执行过
3. `authorization`、`common`、`host` 是否按顺序启动
4. 是否严格使用 `127.0.0.1` 的开发地址
5. `/common/tenants/permission-context-id` 是否正常
6. `/common/menus/service-routes` 是否正常
7. `/{service}/mf/mf-manifest.json` 是否存在
8. 对应资源的 `/schema` 是否已经在后端响应里缺按钮

## 10. 关联文档

- 本地开发：`doc/deployment/local_development.md`
- Docker Swarm 部署：`doc/deployment/docker_swarm_deployment.md`
- 授权上下文：`doc/permission/authorization_context.md`
- 授权流程：`doc/design/authorization_flow.md`
- API 约定：`doc/api/api_conventions.md`
- Schema API：`doc/api/schema_api.md`
