# 个人工作中心

Common 服务承载个人事项、待办与业务消息；Host 首页使用单一摘要接口。菜单位于首页后：
`/my/items`、`/my/todos`、`/my/messages`。

“我的”根菜单排序值为 1，紧随首页的 0，早于 DNA/AI 等业务模块。
三个列表与资源管理一样使用 schema 驱动的 `SimpleTable`，通过后端 `@ButtonDeclarations`、
`@Schema` 和 `AnnotatedTableSchemaProvider` 声明按钮、列和表单；每类提供 `/schema` 接口。
平台统一提供工具栏、选中数量约束、排序/分页、列偏好、右侧抽屉和 `SForm`。
业务仅适配 `loadPage/customButtonEvents/onSubmit`，详情操作同样使用平台 `TableActions`。
原有个人表格偏好键保留；切换视图重置当前筛选、排序、分页，缓存按视图和授权作用域区分。
新增/编辑保留未保存确认与错误内联显示，子清单使用平台数组表单并保留稳定 ID。
所有操作声明均注册为公开但要求登录的个人能力（不可授权给他人），读取及写入仍强制本人/工作空间隔离。
按钮以授权上下文裁剪，前端补充生命周期禁用，后端始终校验版本与状态；
消息无新增/编辑/删除入口，外部来源待办不允许手工改结果。
表头复用平台排序与筛选面板；状态/优先级使用带中文标签的多选项，日期使用类型化条件。
三个列表不再提供重复的顶部搜索/筛选栏；保留视图标签和新建、全部已读等操作按钮。
排序和筛选均由后端执行，改变条件回到第一页，重置后恢复业务默认顺序。
子清单的“完成数/总数”是计算摘要，仅用于展示，不提供误导性的字符串排序或筛选。

### 表头查询契约

列表继续支持已有视图/关键词条件，另接受 JSON 字符串 `filters` 和 Spring `sort=title,asc`：
`filters={"priority":"in:1,2","dueAt":"than:greater:2026-09-18T00:00:00Z"}`。
表头条件与现有条件取交集，不能覆盖 owner/workspace、回收站、归档、未读等约束。
字段白名单：事项 title/status/priority/category/dueAt；待办 title/priority/source/dueAt；
消息 title/priority/source/createdAt。不接受任意实体属性、关联路径或 SQL 片段。

支持平台包含/不包含、等于/不等于、集合、比较、区间和空值操作符；
数字按整数绑定，日期按 Instant 绑定，YYYY-MM-DD 按 UTC 零点解释。文本包含按字面匹配（转义 %/_）。
ISO 时间与复合操作符中的冒号完整保留。无效字段/类型/操作符返回 400，不静默忽略。
筛选 JSON 上限 8192 字符、16 个条件、每列 20 个值、3 个排序字段；空值排后，ID 作为稳定排序次键。
分页数据和总数使用相同谓词，不将当前页的客户端排序伪装成全量结果。

## 数据与权限

- API / implementation / REST 三层模块，使用平台授权上下文、JPA、审计字段、资源声明和中英文国际化。
- HTTP 接口要求已认证。用户及工作空间仅从服务端授权上下文推导，不能由请求体指定；管理员也没有绕过个人数据隔离的入口。
- 工作空间键包含作用域类型和租户 ID；平台作用域固定为 `PLATFORM:__platform__`。不同角色在同一工作空间看到同一份个人数据。
- 每次读取、计数、详情、操作历史及批量已读均包含 owner/workspace 条件。写入需要当前 version，冲突返回 HTTP 409，不覆盖旧窗口中的修改。
- 四张独立业务表：`simpoint_personal_items`、`simpoint_personal_item_events`、`simpoint_personal_todos`、`simpoint_personal_messages`。遵循平台现有 Hibernate schema 管理方式；启动时新增表及索引，不修改已有业务表。
- 长文本使用普通 VARCHAR / TEXT，不使用 PostgreSQL OID 大对象。

## 事项

标题、说明、分类、优先级、起止时间、截止时间、置顶和最多 50 个子项。状态为未开始、进行中、已完成、已取消；支持重新打开。
完成前必须勾选全部子项；已结束事项才能归档，重新编辑前需要取消归档并重新打开。
删除进入回收站，可恢复，保留原归档状态。操作和备注以不可修改的历史记录保存。
完成事项会在同一事务中生成一条个人消息。截止时间仅用于排序和逾期展示，本期不发送定时提醒。

## 待办与消息 / 工作流对接

待办支持手工创建、完成、取消和重新打开。消息支持收件箱、未读筛选、标记已读/未读、全部已读及归档。
消息是业务收件箱，不是用户间聊天；公告继续使用已有通知模块。

`PersonalInboxGateway` 是受信任的进程内集成契约，不对浏览器暴露投递或指定收件人接口：

- `deliverTodo(target, delivery)` / `deliverMessage(target, delivery)`：来源系统显式指定收件人和工作空间。
- `source + sourceKey + userId + workspace` 具有唯一约束，相同事件重试返回同一记录；来源键必须稳定。
- 并发首次投递的唯一键冲突由调用方在新事务重试。生产集成建议来源业务事务提交后通过 outbox 消费投递，禁止吞掉投递失败。
- `resolveTodo(target, source, sourceKey, outcome)`：来源系统同步完成/取消，重复相同结果幂等，不接受相反的终态。
- 非手工待办不能由个人页面改结果，用户通过站内业务链接进入来源系统处理。来源系统仍必须独立校验业务权限。
- 仅接受站内绝对路径，拒绝外部 URL、协议相对 URL、反斜杠和控制字符。

本期不提供工作流引擎、审批动作、撤回/转办或跨服务投递协议，也不恢复已删除的 AI 工作流模块。

## 性能与验证

列表服务端分页（最多 100 条）；首页一次调用 `GET /common/personal/overview`，每类最多 5 条摘要。
详情/历史按需加载，查询缓存按授权上下文隔离；修改后定向失效 personal 查询。首页仅可见时每分钟刷新，不新增 SSE。
表格与详情共享 60 秒 Schema 缓存，按授权作用域隔离，避免重复打开详情时重复加载按钮声明。
所有页面拥有页内滚动，弹层在缓存页失活时隐藏；未保存草稿阻止关闭标签页，失败保留内容。

后端测试覆盖真实 Hibernate 事务、版本冲突、跨用户/工作空间隔离、事项全生命周期、批量已读及投递幂等，另验证表头全部操作符、日期冒号、通配符转义、稳定分页、非法查询及生产异常转换链。
默认 H2；可用 `PERSONAL_TEST_DB_URL=jdbc:postgresql://127.0.0.1:45433/personal_test` 在一次性 PostgreSQL 上运行。
测试用户和密码均为 `personal_test`，测试会 create/drop 表，**严禁指向业务数据库**。
REST 测试覆盖匿名拒绝、JSON 契约及 400/404/409；前端 `pnpm check:frontend` 覆盖类型、国际化、构建、MF 预算和隔离 API 浏览器回归。
Schema 测试使用真实平台生成器，验证权限裁剪、表单约束、日期类型和按钮选择数量限制；浏览器回归覆盖单选/多选禁用、草稿退出确认及冲突后保留表单。
HTTP Schema 必须转换为普通 Map/List，不能直接返回 Jackson 2 的 JsonNode：生产 MVC 使用 Jackson 3，会将旧版节点序列化为内部元数据。Schema REST 测试显式使用生产 Jackson 3 转换器，并在 `personal-rest/build/generated-test-resources/personal-schemas.json` 输出浏览器契约；更新 `simplepoint-react/scripts/personal-schemas.fixture.json` 时使用此产物，不手写替代真实类型。
