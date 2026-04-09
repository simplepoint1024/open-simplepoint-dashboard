# Schema 驱动 UI（Schema-driven UI）

## 1. 背景与目标

当前前端 CRUD 页面的核心思路不是“每个页面手写一套表单和按钮定义”，而是：

1. 后端实体通过注解声明字段元数据和动作元数据。
2. 通用控制器输出 `/schema`。
3. 前端 `SimpleTable` / `SForm` 读取这个 schema，自动生成表格、表单和操作按钮。

这套机制是仓库里平台型页面的通用骨架。

## 2. 后端产物长什么样

`BaseController` 默认暴露：

```http
GET {baseUrl}/schema
```

返回值由 `BaseServiceImpl.schema()` 组装，结构固定为：

```json
{
  "schema": { "...": "JSON Schema" },
  "buttons": [ "...按钮声明..." ]
}
```

其中：

- `schema` 用于表单与表格字段
- `buttons` 用于页面级动作按钮

## 3. 注解是如何变成 schema 的

### 3.1 字段元数据来源

字段 schema 主要来自实体字段上的：

- `@Schema`
- `@Order`

`simplepoint-data-json-schema` 模块里的 `OpenApiModule` 会把这些注解转成 JSON Schema 扩展字段。

| Java 注解/属性 | 生成后的 schema 能力 |
| --- | --- |
| `@Schema(title, description)` | 字段标题、描述 |
| `@Schema(maxLength, minLength, format, hidden, nullable, defaultValue)` | 常规表单约束 |
| `@Schema(extensions = @Extension(name = "x-ui", ...))` | 前端专用扩展，如列表展示、控件类型、字典编码 |
| `@Order(n)` | `x-order`，供前端排序字段 |

### 3.2 一个真实例子

`Organization.type` 字段的声明里同时包含：

- `widget = select`
- `dictCode = TenantDictionaryCodes.ORGANIZATION_TYPE`
- `x-list-visible = true`

这意味着后端不仅告诉前端“这是个字符串字段”，还告诉前端：

- 列表里要显示
- 表单里用下拉框
- 选项来自某个字典编码

## 4. 按钮是如何生成的

页面动作按钮来自实体类上的 `@ButtonDeclarations`，例如 `Role`、`Organization`、`Dictionary`、`MicroModule` 等实体都声明了：

- 新增
- 编辑
- 删除
- 以及个别业务自定义按钮

`BaseServiceImpl.getButtonDeclarationsSchema(...)` 会基于当前 `AuthorizationContext` 做一次过滤：

1. 读取当前用户权限集合
2. 检查每个按钮声明的 `authority`
3. 只有当前用户有权限，或者用户是管理员时，按钮才会出现在 `/schema` 返回结果中

这意味着按钮权限控制的真正来源是**实体注解 + 当前授权上下文**，而不是前端页面硬编码。

## 5. 前端如何消费 `/schema`

前端共享库里的 `useSchema(baseUrl)` 是这条链路的入口：

1. 请求 `${baseUrl}/schema`
2. 按 `x-order` 排序字段
3. 解析 `i18n:` 前缀的标题/描述
4. 规范化按钮标题与图标
5. 遇到 `x-ui.dictCode` 时，再去请求字典选项
6. 返回增强后的 `schema + buttons`

`SimpleTable` 组件内部通过 `useSimpleTableController` 调用 `useSchema(...)`，再把结果分发给：

- `Table`：负责列表列定义
- `SForm`：负责表单渲染

因此大部分 CRUD 页本身只需要提供：

- `baseUrl`
- 少量自定义按钮事件
- 提交前后的个别钩子

## 6. 字典字段是怎么补全的

当字段的 `x-ui.dictCode` 存在时，前端不会直接用原始字符串渲染，而是会额外请求：

```http
GET /common/platform/dictionaries/options?dictionaryCode=...
```

然后把结果转换为 schema 的 `oneOf` 选项，并自动补成 `select` 控件。

这条链路让后端只需要声明“这个字段属于哪个字典”，而不用把静态选项硬编码进每个实体。

## 7. 表格和表单如何解释这些扩展字段

### 7.1 表格

`Table` 组件会读取字段 schema 中的：

- `x-ui.x-list-visible`：决定列是否默认可见
- `oneOf` / `anyOf`：把枚举值展示为标签
- `title`：作为列名

### 7.2 表单

`SForm` 基于 RJSF 渲染表单，并读取：

- `x-ui.widget`
- `x-ui.ui:options`
- `x-ui.format`

其中：

- `textarea` 会被自动补充自适应高度
- `icon` 字段如果没有显式 widget，会自动接入 `IconPicker`
- `x-ui.format === json` 的字段会启用 JSON 格式校验

## 8. 和租户 / 权限链路的关系

Schema 不是静态公共资源，它会受到当前上下文影响：

1. 前端请求 `/schema` 时会自动带上 `X-Tenant-Id` 和 `X-Context-Id`
2. 后端读取 `AuthorizationContext`
3. `/schema` 返回的按钮集合会随当前权限变化
4. 字典选项接口也会带着当前租户和上下文请求

因此同一个页面在不同租户、不同角色下，拿到的 schema 可能并不完全一样。

## 9. 当前实现的边界

### 9.1 后端主要负责“声明”，前端负责“增强”

后端负责输出字段定义、动作定义和 `x-ui` 扩展；  
前端负责做这些增强：

- i18n 文本替换
- 字典选项补查
- 组件映射
- 表格列与表单控件的最终渲染

### 9.2 页面仍然可以做局部覆写

虽然页面是 schema 驱动的，但不是完全不可定制。  
例如组织页面会通过 `formSchemaTransform` 动态过滤 `parentId` 选项，角色页面也会为自定义按钮绑定额外事件。

这说明当前方案不是“零代码页面”，而是“通用骨架 + 局部定制”。

## 10. 适合阅读的源码入口

推荐按下面顺序看这条链路：

1. `BaseController.schema()`
2. `BaseServiceImpl.schema()`
3. `OpenApiModule`
4. 一个带 `@Schema` / `@ButtonDeclarations` 的实体，例如 `Role`、`Organization`
5. 前端 `useSchema`
6. `libs/components/SimpleTable/useSimpleTableController.ts`
7. `libs/components/Table` 和 `libs/components/SForm`

## 11. 关联文档

- 服务拓扑：`doc/architecture/service_topology.md`
- 多租户模型：`doc/architecture/multi_tenant_model.md`
- 前端微前端：`doc/architecture/frontend_microfrontend.md`
- Schema API：`doc/api/schema_api.md`
- API 约定：`doc/api/api_conventions.md`
