# API 约定（API Conventions）

## 1. 背景与目标

当前仓库的 API 风格不是严格的“单一 REST 规范样板”，而是基于以下几个约定组合出来的：

1. 控制器大量继承 `BaseController`
2. 浏览器通常通过 host 网关访问 `/common/...`
3. 权限判断依赖 `AuthorizationContext`
4. 响应体默认返回原始 JSON，而不是统一信封对象

本文只总结**当前代码里已经稳定存在的约定**，便于联调、补接口和写文档。

## 2. 路径与入口约定

### 2.1 浏览器侧通常走 host 网关

前端当前最常见的调用方式是：

```text
/common/...
/authorization/...
```

也就是说，浏览器通常先访问 host，再由 host 把请求转发到后端服务。

### 2.2 控制器本身通常只声明资源路径

资源服务里的控制器一般不会自己写 `/common` 这样的服务前缀，而是只声明资源路径，例如：

- `/roles`
- `/menus`
- `/dictionaries`
- `/platform/dictionaries`

经过 host 转发后，浏览器看到的完整路径才会变成：

- `/common/roles`
- `/common/menus`
- `/common/platform/dictionaries`

### 2.3 存在别名路径

部分控制器会同时声明两组路径，例如：

```java
@RequestMapping({"/dictionaries", "/platform/dictionaries"})
```

这类路径别名在当前仓库里是常见做法，写联调脚本时不要默认每个资源只有一个 base path。

## 3. 认证与请求头约定

### 3.1 浏览器请求默认依赖 host 会话

前端共享请求层默认使用：

```ts
credentials: 'include'
```

所以浏览器场景里最核心的是 host 会话 cookie，而不是前端手写 Bearer Token。

### 3.2 host 会把 Bearer Token 转发给下游

host 的网关配置里已经启用了：

```text
TokenRelay
```

因此浏览器请求经 host 转发到资源服务后，下游通常能看到 Bearer Token。

### 3.3 直接调资源服务时的最小头部

如果你绕过 host，直接调 `common` / `auditing` / `dna`，当前最稳妥的做法是同时带上：

```http
Authorization: Bearer <token>
X-Tenant-Id: <tenantId>
X-Context-Id: <contextId>
```

其中：

- `Authorization` 用于通过 Resource Server 认证
- `X-Tenant-Id` 决定租户作用域
- `X-Context-Id` 决定授权上下文缓存与刷新

## 4. 响应体约定

### 4.1 默认不是 `{ code, data, message }`

当前 `Response<T>` 虽然叫“统一响应结构”，但本质上是 `ResponseEntity<T>` 的一个薄封装。  
因此大多数接口返回的其实就是：

- 单个对象
- 数组 / 集合
- 分页对象
- 或空响应

而不是多一层业务信封。

例如：

```json
{
  "id": "xxx",
  "name": "Tenant A"
}
```

而不是：

```json
{
  "code": 0,
  "data": {
    "id": "xxx"
  }
}
```

### 4.2 常见返回形态

| 场景 | 当前返回形态 |
| --- | --- |
| 单对象查询 / 创建 / 修改 | 直接返回对象 |
| 集合查询 | 直接返回 JSON 数组 |
| 分页查询 | 直接返回分页 DTO |
| 只表示成功 | `200` + 空 body，或 `204` |

## 5. 分页约定

### 5.1 控制器普遍使用 `Pageable`

当前很多列表接口形态类似：

```java
@GetMapping
public Response<Page<Role>> limit(@RequestParam Map<String, String> attributes, Pageable pageable)
```

也就是说，分页接口通常同时接收：

1. 一个自由形式的过滤参数 `Map<String, String>`
2. Spring `Pageable`

### 5.2 当前分页 JSON 结构

仓库里的 JPA Web 支持启用了：

```text
EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO
```

因此当前前端按下面的分页形态消费接口：

```json
{
  "content": [
    { "id": "r1", "name": "Admin" }
  ],
  "page": {
    "size": 10,
    "number": 0,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

### 5.3 页码是零基

当前前端表格控制器会把 Ant Design 的页码转换成：

```text
page = current - 1
```

所以服务端分页参数当前按零基页码理解更准确。

## 6. 常见 CRUD 约定

### 6.1 基础资源接口

当前仓库里的很多资源控制器都遵循这组约定：

| 动作 | 常见形态 |
| --- | --- |
| 分页查询 | `GET /resource?page=0&size=10&...filters` |
| 新增 | `POST /resource` |
| 修改 | `PUT /resource` |
| 删除 | `DELETE /resource?ids=id1,id2` |
| Schema | `GET /resource/schema` |

### 6.2 常见扩展接口

除了基础 CRUD 外，当前仓库还大量使用下面这类扩展接口：

| 语义 | 常见路径 |
| --- | --- |
| 下拉候选项 | `/items` |
| 当前已授权对象 | `/authorized` |
| 执行授权绑定 | `/authorize` |
| 解除授权绑定 | `/unauthorized` |
| 专用查询动作 | 以业务含义命名，如 `/service-routes`、`/options` |

例如：

- `/common/roles/items`
- `/common/roles/authorized`
- `/common/roles/authorize`
- `/common/platform/dictionaries/options`

## 7. 过滤与排序约定

### 7.1 过滤参数

当前很多列表接口直接接收：

```java
@RequestParam Map<String, String> attributes
```

这意味着过滤参数本身没有一个全局固定 DSL，而是：

- 前端把普通筛选字段直接拼进 query string
- 服务 / 仓储层再决定如何解释这些键值

因此文档里最稳妥的表达方式是“该接口支持 query 过滤参数”，不要假设所有资源都支持完全相同的过滤语法。

### 7.2 排序参数

排序仍然走 Spring `Pageable` 约定。  
个别控制器会在“未显式排序”时补默认排序，例如菜单接口会默认按 `sort ASC`。

## 8. 安全约定

### 8.1 服务端最终以 `@PreAuthorize` 为准

当前控制器常见写法是：

```java
@PreAuthorize("hasRole('Administrator') or hasAuthority('roles.view')")
```

也就是说：

- 菜单显示与按钮显示只是 UI 投影
- 真正的放行边界还是服务端 authority 判断

### 8.2 有效 authority 来自 `AuthorizationContext`

资源服务里的 Spring Security authorities 并不是简单从 JWT scope 直接展开，而是从 `AuthorizationContext.asAuthorities()` 生成。  
所以租户、上下文、角色、功能链路都会影响接口是否放行。

## 9. 内容类型约定

### 9.1 默认 JSON

当前前端请求层默认会发送：

```http
Content-Type: application/json
```

并把响应按 JSON 或 text 解析。

### 9.2 例外：文件上传

像对象存储这类接口会使用 multipart/form-data。  
前端请求层已经对 `FormData` 做了特殊处理，不会强行覆盖 content-type。

## 10. OpenAPI 与文档入口

当前资源服务的安全配置默认放行：

- `/v3/api-docs/**`
- `/swagger-ui/**`

因此在服务可达时，可以直接通过这些路径查看当前服务暴露的 OpenAPI 文档。

## 11. 联调示例

### 11.1 列表查询

```http
GET /common/roles?page=0&size=10&name=admin
```

### 11.2 删除多个资源

```http
DELETE /common/roles?ids=role1,role2
```

### 11.3 获取 Schema

```http
GET /common/platform/tenants/schema
```

### 11.4 获取字典选项

```http
GET /common/platform/dictionaries/options?dictionaryCode=organization_type
```

## 12. 当前实现的几个边界

### 12.1 “统一响应”更偏 HTTP 层统一，而不是业务层统一

当前 `Response<T>` 统一的是：

- HTTP 状态码
- content-type

而不是一个固定的业务信封协议。

### 12.2 列表接口的过滤语义由具体资源决定

虽然很多控制器都接 `Map<String, String>`，但不同资源的底层查询解释方式并不一定一样。  
文档和前端调用最好按资源逐个确认，而不是想当然复用。

### 12.3 浏览器路径和服务内部路径不是同一层

浏览器常看到 `/common/...`，控制器代码里常只写 `/roles`、`/menus`、`/dictionaries`。  
写接口文档时记得区分“服务内路径”和“通过 host 暴露后的访问路径”。

## 13. 关联文档

- Schema API：`doc/api/schema_api.md`
- 授权流程：`doc/design/authorization_flow.md`
- 授权上下文：`doc/permission/authorization_context.md`
- 权限模型：`doc/permission/permission_model.md`
- 本地开发：`doc/deployment/local_development.md`
- 常见问题：`doc/troubleshooting/common_issues.md`
