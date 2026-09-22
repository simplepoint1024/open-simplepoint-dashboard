# 页面数据权限、字段权限和表达式

本次将权限能力从组织/用户页面的固定名单移入平台元数据与执行层，并补齐规则管理界面。数据规则始终与租户边界、软删除条件及资源授权取交集；字段规则在查询、输出、表单和写入端执行。

## 标准接入

使用 `BaseController + BaseServiceImpl + BaseRepositoryImpl` 的页面，注册时由控制器实体类型与根 GET 的资源声明自动生成 `Resource.permissionMetadata`。角色资源配置依据实际注册的能力展示数据和字段规则槽位，不再维护页面编码白名单。服务端选择当前页面资源，不接受前端指定权限上下文。

默认所有者字段为 `createdBy`，组织字段为 `createOrgDeptId`。特殊实体可通过 `@PermissionModel` 指定字段；组织实体使用 `id`。权限配置本身等基础设施模型通过注解明确关闭不适用的规则槽位。字段规则目录合并本地实体和各服务已注册的实体/投影字段。

列表、分页总数、主键读取、创建、更新前后状态、删除使用同一规则。显式配置的页面规则也约束管理员；没有页面规则时保留原业务管理边界。查询条件、隐藏/脱敏字段的筛选排序均不能扩大权限。

关联查询需要真实字段映射，不能从前端列名猜测数据库归属。非标准控制器用 `@PermissionPage` 声明页面与投影模型，投影用 `@PermissionModel(page=..., owner=..., department=...)` 指定口径，并通过 `ProjectionQuery` / `DataScopePredicates` 执行。租户成员已适配为租户成员关系中的组织与用户标识；组织成员的查询和修改使用独立的组织页面接口。全局用户账号的历史组织字段不再作为租户成员数据范围。

一个页面使用多个实体时，表达式仅开放类型兼容的公共字段，并取各模型支持操作符的交集。外部存储、原生 SQL 或自行拼装 Map 的接口不能仅靠前端封装获得行权限：必须提供执行适配和字段投影。没有注册执行能力的页面不会显示可配置的数据规则槽位。

## 规则管理

数据规则页面修正了预览区与列表的伸缩布局，创建默认选择 SELF，CUSTOM 使用可分页组织选择器，EXPRESSION 提供目标页面与表达式编辑器。当前生效预览展示页面级数据和字段规则，同时保留旧角色默认范围的诊断信息。

已绑定的规则不能删除；已绑定规则更换类型或目标页面前需要解除绑定。创建/更新与字段条目保存均校验租户归属并刷新授权版本。新页面绑定标记 `pagePolicy=true`，不再被合并成影响其他页面的旧角色默认规则。已有 null 标记保留旧版兼容口径。

多个服务可能携带同一资源声明，没有页面控制器的服务不会用空值覆盖页面所属服务发布的权限元数据。

## 表达式与可视化

使用 [ANTLR Java 工具与运行时](https://www.antlr.org/download.html) 生成解析器，语法文件位于 `simplepoint-core/src/main/antlr/PermissionRule.g4`。解析结果复用平台 `QueryDocument.Node`；执行复用带参数的 JPA Criteria 谓词，表达式不是 SQL。

支持 AND、OR、NOT、括号、等于/不等于、大小比较、IN/NOT IN、CONTAINS/NOT CONTAINS、IS NULL/IS NOT NULL。字符串使用 JSON 双引号，数字、布尔值和日期按注册字段类型校验。数值也可使用严格校验的字符串保留大整数与小数精度。

```text
createdBy = $user.id
id = $user.organizationId
id IN $user.organizationIds
name CONTAINS "研发" AND enabled = true
NOT (description IS NULL)
```

可用变量为 `$user.id`、`$tenant.id`、`$user.organizationId`、`$user.organizationIds`。组织变量来自后端验证的当前租户成员关系；当前模型只有一个主组织，因此 organizationIds 为该主组织组成的列表，不含下级组织。内置“本组织及下级”规则继续提供组织树范围。

每条规则最多 8192 字符、32 个 AST 节点、4 层分组，固定值列表最多 100 项。仅允许目标页面注册的字段/操作符；没有任意 SQL、反射、方法执行、任意属性链或外部调用。缺失上下文值遵守 SQL UNKNOWN 语义，包括 NOT；写入校验使用相同的三值逻辑。不同角色的表达式与内置范围按现有并集语义组合，最终仍与租户条件相交。

文本与可视化通过同一校验接口往返转换。可视化复用平台条件树和类型输入组件，修改草稿后须应用才能保存，避免把未应用的旧表达式提交。服务端在保存和角色绑定时再次验证，表达式规则只能绑定其目标页面。

接口：

- `GET /data-scopes/expression/catalog`：当前用户可访问且已注册的页面及模型字段。
- `POST /data-scopes/expression/validate`：提交 `resourceCode` 与 `expression` 或 `ast`，返回规范表达式和 AST。
- `GET /data-scopes/effective`：当前工作空间已保存的有效规则。

## 升级与验证

先备份数据库，再执行 `doc/deployment/sql/20260922-permission-expressions.sql`。迁移为资源添加权限元数据、为页面授权添加来源标记、为数据规则添加目标页面与表达式，并扩展 PostgreSQL 的类型 CHECK 约束。需要同步更新发布/消费授权上下文的服务；缓存键升级为 `verified-v5`。

回滚旧应用前应确认没有新增 EXPRESSION 规则，旧枚举不认识该类型。数据库备份不能覆盖上线后新增的业务写入；有新增配置时应先导出并单独处理这些规则。

验证覆盖：标准页面自动注册、AOP 字段映射、显式管理员规则、租户隔离、分页与计数、字段读写、绑定规则删除保护、页面规则独立合并、ANTLR 文本/AST 往返、生产 JSON 转换器、非法输入与复杂度、SQL 空值语义、组织选择器及成员投影。
