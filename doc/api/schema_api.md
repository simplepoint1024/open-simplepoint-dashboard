# Schema API（`/schema`）

## 1. 背景与目标

当前仓库大量平台型 CRUD 页面不是靠前端手写字段和按钮，而是靠后端统一输出：

```http
GET {baseUrl}/schema
```

前端再基于这个结果动态生成表格、表单和动作按钮。  
本文只描述当前仓库里已经实现的 `/schema` 协议，而不是泛化 JSON Schema 规范。

## 2. 接口入口

`/schema` 不是每个控制器单独手写的，而是来自 `BaseController` 的默认实现：

```http
GET /schema
```

因此，大多数继承 `BaseController` 的资源都天然拥有这个接口，例如：

- `/common/roles/schema`
- `/common/menus/schema`
- `/common/platform/tenants/schema`
- `/common/platform/dictionaries/schema`

## 3. 响应结构

当前响应体是**原始 JSON 对象**，不是 `{ code, data, message }` 这种信封结构：

```json
{
  "schema": {
    "type": "object",
    "properties": {
      "name": {
        "type": ["string", "null"],
        "title": "i18n:tenants.title.name",
        "description": "i18n:tenants.description.name",
        "maxLength": 128,
        "x-order": 0,
        "x-ui": {
          "x-list-visible": "true"
        }
      }
    }
  },
  "buttons": [
    {
      "title": "i18n:tenants.button.config.package",
      "key": "config.package",
      "authority": "tenants.config.package",
      "icon": "SafetyOutlined",
      "sort": 3,
      "danger": false
    }
  ]
}
```

其中：

| 字段 | 说明 |
| --- | --- |
| `schema` | 当前实体的 JSON Schema 结果。 |
| `buttons` | 当前用户在此资源上可见的动作按钮声明。 |

## 4. `schema` 是怎么生成的

### 4.1 后端生成链路

当前后端链路是：

```text
实体字段注解 -> OpenApiModule -> JSON Schema -> BaseServiceImpl.schema()
```

`BaseServiceImpl.schema()` 会：

1. 取当前实体的 `domainClass`
2. 用 `JsonSchemaGenerator` 生成 JSON Schema
3. 按 `x-order` 对 `properties` 重排
4. 再补上当前用户可见的 `buttons`

### 4.2 当前支持的主要字段约定

`OpenApiModule` 当前会从实体字段注解里提取这些信息：

| 注解 / 属性 | 进入响应后的结果 |
| --- | --- |
| `@Schema(title, description)` | 字段标题、描述 |
| `@Schema(maxLength, minLength)` | 字符串长度约束 |
| `@Schema(format)` | 格式信息 |
| `@Schema(defaultValue)` | 默认值 |
| `@Schema(hidden = true)` | 字段从 schema 中隐藏 |
| `@Schema(accessMode = READ_ONLY / WRITE_ONLY)` | 读写模式 |
| `@Schema(nullable = true)` | 允许空值 |
| `@Schema(extensions = @Extension(name = "x-ui", ...))` | 前端专用扩展字段 |
| `@Order(n)` | 输出 `x-order` |

另外，当前实现还会把：

- `String`
- `Instant`

默认视为可空字段（除非另有显式约束）。

## 5. `x-ui` 和 `x-order` 约定

### 5.1 `x-order`

- 来源：字段上的 `@Order`
- 用途：前端按显示顺序重排字段

### 5.2 `x-ui`

`x-ui` 是后端传给前端的 UI 扩展约定，当前常见字段包括：

| 键 | 含义 |
| --- | --- |
| `x-list-visible` | 是否在列表里默认显示 |
| `widget` | 前端控件类型，如 `select` |
| `dictCode` / `dict-code` | 字典编码，前端会据此补查选项 |
| `ui:options` | 传给前端控件的额外选项 |
| `format` | 某些字段的前端渲染提示 |

## 6. `buttons` 是怎么来的

`buttons` 来源于实体类上的 `@ButtonDeclarations`。  
当前每个按钮声明可以携带的主要字段包括：

- `title`
- `key`
- `path`
- `argumentMinSize`
- `argumentMaxSize`
- `type`
- `color`
- `variant`
- `danger`
- `icon`
- `authority`
- `sort`

但是，`/schema` 并不会把实体上声明的所有按钮原样返回。  
`BaseServiceImpl.getButtonDeclarationsSchema(...)` 会先做一次权限过滤：

1. 读取当前 `AuthorizationContext`
2. 检查按钮的 `authority`
3. 只有管理员，或当前用户拥有对应 authority 时，按钮才会出现在返回结果中

所以“按钮没返回”通常是权限结果导致的，不是前端自己删掉了按钮。

## 7. 前端如何消费 `/schema`

前端共享 Hook `useSchema(baseUrl)` 会做下面几件事：

1. 请求 `${baseUrl}/schema`
2. 以 `tenantId + contextId` 为 query key 的一部分，确保租户或权限上下文变化时自动重查
3. 递归把 `title` / `description` 里的 `i18n:` 前缀解析成当前语言文本
4. 把图标字符串转成前端组件图标
5. 对字典字段补查选项

### 7.1 字典字段增强

当字段里存在：

```json
"x-ui": {
  "dictCode": "..."
}
```

前端会额外请求：

```http
GET /common/platform/dictionaries/options?dictionaryCode=...
```

然后把结果改造成：

- `oneOf`
- 默认 `select` widget

这样后端只需要声明“这个字段属于哪个字典”，不需要把所有选项硬编码进 schema。

### 7.2 和 `SimpleTable` 的关系

`SimpleTable` / `useSimpleTableController` 当前会同时使用：

1. `/schema`
2. 列表分页接口
3. `POST / PUT / DELETE`

因此，`/schema` 不是孤立接口，而是页面 CRUD 骨架的一部分。

## 8. 当前使用方式的几个约束

### 8.1 `/schema` 结果依赖当前权限上下文

前端请求 `/schema` 时，请求层会自动附带：

- `X-Tenant-Id`
- `X-Context-Id`

所以：

- 不同租户下同一个资源的按钮可能不同
- 权限变更后，刷新 `contextId` 后再请求 `/schema`，按钮结果也会跟着变化

### 8.2 后端主要声明结构，前端负责增强

当前后端负责：

- 字段元数据
- UI 扩展字段
- 按钮声明

前端负责：

- i18n 解析
- 字典选项补查
- 图标转换
- 实际控件映射与表格渲染

### 8.3 `/schema` 不等于完整页面协议

`/schema` 只负责字段和按钮元数据。  
列表分页、删除 `ids` 约定、权限绑定、菜单装配等仍然属于其他 API / 权限链路的一部分。

## 9. 调试建议

如果你怀疑某个页面 Schema 不对，推荐按下面顺序查：

1. 直接打开对应资源的 `/schema`
2. 看 `schema.properties` 里是否有预期字段和 `x-ui`
3. 看 `buttons` 是否已经在后端响应里缺失
4. 如果是字典字段，看 `/common/platform/dictionaries/options` 是否可用
5. 如果是权限相关按钮，看当前 `tenantId / contextId / AuthorizationContext` 是否正确

## 10. 关联文档

- Schema 驱动 UI：`doc/architecture/schema_driven_ui.md`
- API 约定：`doc/api/api_conventions.md`
- 授权上下文：`doc/permission/authorization_context.md`
- 权限模型：`doc/permission/permission_model.md`
- 常见问题：`doc/troubleshooting/common_issues.md`
