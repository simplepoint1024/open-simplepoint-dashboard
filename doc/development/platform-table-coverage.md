> 2026-09-22 更新：独立资源授权中心及其角色目录、影响用户接口已经移除。下文关于该中心的阶段记录仅用于追溯历史实现，不能作为当前可用接口或页面说明。角色管理资源配置现使用 `/role-resource-configuration/roles/{roleId}`。

# 全平台标准表格与查询能力验收清单

目标：所有业务列表使用平台标准组件；所有适用列表完整支持类型化表头筛选、高级条件、多列排序、条件摘要、保存/恢复视图；工具栏仅图标并保留无障碍名称。不得把“换了 import”或“显示了图标”视为查询能力完成。

状态日期：2026-09-19。按用户要求停止继续扩展，已将当前阶段成果部署为 `20260919-tables1`；此清单尚未全量验收完成，不将发布等同于全平台目标完成。以下阶段中的“未部署”描述保留为当时记录，当前发布状态以 [本次发布记录](../release-notes/platform-tables-20260919.md) 为准。

## 全量入口扫描

- `node simplepoint-react/scripts/audit-platform-tables.mjs`：扫描 modules 和 libs 下全部 TSX；当前 149 个文件、95 个表格使用点（92 个平台组件使用点、2 个底层渲染器、1 个日历网格）。
- 守卫测试识别 Ant Design 别名、namespace、直接子路径导入以及原生 table，禁止业务页面绕过平台组件。平台底层 Table/DataTable 的 Ant Design 渲染器允许保留。
- HomeCalendar 的 HTML table 是有键盘日期导航的日历网格，不是业务列表；保留其日期网格语义，不加入记录筛选/分页。
- 表格组件迁移状态与端到端查询状态分开记录。以下“待验收”不表示已具备高级查询。

## 实施与验收门槛

- [x] 标准 Table 的筛选与视图入口改为图标，保留 Tooltip 和 aria-label。
- [x] 17 处直接原生业务表格改接平台 DataTable，覆盖 11 个页面/共享组件文件。
- [x] 加入全平台组件使用守卫；六工程 TypeScript 检查通过。
- [ ] DataTable 与标准 Table 共用类型化筛选、高级条件和保存视图；明确服务端分页与完整本地结果集的边界。
- [ ] common：所有标准 CRUD、账号/审计、成员、组织、权限分配、对象存储配置/对象/配额及相关弹窗逐一验证。
- [ ] auditing：日志、资源授权日志、限流及 Redis 查询完整接入；Redis 禁止伪装当前页为全集。
- [ ] DNA：数据源/驱动、联邦目录/策略/审计/模板/视图/资产/质量、JDBC 用户授权、方言/元数据和 SQL 结果逐一验证。
- [ ] AI：供应商/模型/API Key/账单/知识库/MCP，以及目录、文档、分块、运行时子列表逐一验证。
- [ ] host、authorization、mcp-gateway：检查公共入口、快捷键说明及服务端列表是否存在未登记调用，不能仅覆盖主菜单。
- [ ] 后端显式字段白名单、schema 契约和实际编译器一致；敏感字段/隐藏字段不可作为推断入口。
- [ ] 服务端强制租户、归属、权限和软删除与用户条件分别处理并外层 AND；迁移不得把服务端约束当成未开放用户字段拒绝，也不得丢失约束。
- [ ] 审计所有 limit/list/findAll 专用实现、字符串 normalizeLike 和分页适配，保证结构化 JSON 不被改写、忽略或当成普通文本。
- [ ] 服务端分页接口在分页前执行筛选与排序，count 同条件；所有保存视图恢复真实生效，不能只恢复 UI。
- [ ] 每个服务的真实 Controller → Service → 数据库/数据源集成回归，包括 AND/OR/NOT、表头条件、多列排序、分页/count、非法条件和权限隔离。
- [ ] 所有前端类型检查、单元测试、i18n、生产构建、MF 预算和浏览器回归通过。
- [ ] 完整重建部署，核对镜像及健康状态，并验证部署产物。全部证据齐备后才能完成目标。

## 逐使用点登记

阶段 1 验证：六工程类型检查、150 项前端单元测试、国际化、五应用生产构建、MF 体积检查与 56 个暴露模块浏览器回归通过。迁移过程中浏览器回归捕获 SQL 结果区分页超出容器的问题；平台 DataTable 增加工具栏高度预算与 ResizeObserver，SQL 结果区及穿梭表使用该能力，未通过扩大页面或削弱测试绕过问题。本阶段尚未覆盖后续后端查询改造，也尚未部署整批变更。

阶段 2（2026-09-19）：DataTable 复用标准表头、高级条件、排序和服务器保存视图，显式区分完整本地结果、有限结果快照与服务端查询；禁止将服务端总数大于已加载数据的分页结果作为本地全集。18 个使用点已显式接入本地查询，另外 26 个平台表格使用点尚未声明查询归属，44 个 SimpleTable 使用点仍需逐服务核实 schema 与真实后端能力。SQL 结果浏览器验证覆盖跨页筛选、数字排序、保存/恢复视图，且不重新执行 SQL。模型发现、MCP 能力/发布/部署工具、方言/来源、元数据结构、费用汇总、权限预览和快捷键共用此实现。MCP 部署历史不再先截断到 5 条，而是对已返回列表进行筛选后分页。

审计服务的 LoginLog、ErrorLog、ResourceGrantLog、ServiceRateLimitRule、EndpointRateLimitRule 已显式开放可见标量字段，保留已有系统审计访问范围（不把登录前产生的无租户事件错误隐藏）。新增 AuditingQueryHttpTest 在独立 H2 中通过五类真实 Controller → Service → Repository：AND/OR/NOT、表头 JSON、多列排序、分页/count、软删除及非法字段的安全 HTTP 400，共 10 项通过，两个 API 模块 Checkstyle 通过。未声明内部上下文字段、会话字段和异常堆栈作为查询能力。

阶段 2 验证：最终源码通过 159 项前端单测、六工程类型检查、国际化、五应用生产构建、MF 预算和 56 个暴露模块浏览器回归（生产静态资源、隔离 API 数据，未使用真实账户）。审计新增集成测试与 logging/rate-limit 两个实现模块已有测试全部通过。测试命令为 `pnpm check:frontend`，以及 `:simplepoint-services:simplepoint-service-auditing:test`、两个 auditing implementation 模块的 `test` 和两个 API 模块的 `checkstyleMain`；后端在 JDK 21 构建环境执行。

阶段 3（2026-09-19）：新增 `BaseRepository.limitScoped` / `BaseServiceImpl.limitScoped`，将用户筛选和服务端构建的必需标量条件分别编译、外层 AND，结果和 count 共用同一 Specification。隐藏的租户/归属字段仍可作为服务端约束，但不能借此成为用户查询字段。旧 `limit` 入口保留行为；不支持该能力的其他仓库默认拒绝，不静默丢弃限制。

AI 的 Provider、Model、ApiKey、Invocation、KnowledgeBase、McpServer、McpPublication 七类列表接入显式查询白名单与独立强制作用域；密文、密钥哈希、上下文标识和临时明文凭据不开放查询。调用账单原来手动复制少量参数、丢弃 `$query` 的问题已修正。供应商和 MCP 服务的上限显式为 500，以兼容现有选择器；其他本批接口上限 200，均有上限/超限验证。知识库文档/分块数量、模型供应商名称等计算/装饰字段未伪装成持久化标量，专用查询能力仍需单独评估补齐。

新增 `AiTableQueryHttpTest` 的 21 项真实 Controller → Service → H2 回归覆盖七类列表的高级 OR、表头 JSON 与高级条件共同生效、排序/分页/count、跨租户及系统作用域隔离、软删除、敏感/未知字段的安全 400、脱敏字段禁查/禁排序，以及通过生产 schema 生成器产生的实际 `/schema` 响应。另有公共 JPA 回归证明未开放的 `createdBy` 可以作为强制服务端约束，用户 OR 和 count 均不能绕过。最终相关后端共 579 项通过（core 299、JPA 35、AI 服务 100、common 10、auditing 10、AI core/knowledge/mcp 实现 56/14/31、personal 实现/rest 13/11），本批八个模块 Checkstyle 与六工程 TypeScript 检查通过。本阶段尚未完成七类实际 schema 的浏览器联调，也未部署整批变更。

阶段 4（2026-09-19）：DNA 的数据源、驱动、联邦目录/Schema/视图/审计/策略/模板/JDBC 连接用户、数据资产和质量规则共 11 类列表声明显式查询字段；密码和连接属性不开放查询。联邦目录合并数据库目录和数据源自动目录时，先通过各源服务应用软删除及数据权限，再统一筛选、排序、分页和计数，修正原先自动目录忽略高级条件、整体忽略用户排序的问题。

新增 `CollectionQuery` 用于完整、已授权的服务端合并集合，与 JPA 共用字段策略、AST 预算和类型转换，支持 NULL 三值逻辑、精确数值、14 类标量操作、多列排序及稳定分页。它不接受“当前页冒充全集”，也不代替调用者的授权；本批仅用于联邦目录。5 项集合查询测试、22 项 DNA 真实 Controller → Service → H2 与实际 schema 回归通过，验证混合目录、表头和高级条件、排序/count、非法字段、软删除及 ALL/SELF/NONE 范围。驱动注册表沿用原有全局可见语义，不误套记录归属。

DNA 相关共 473 项后端测试通过（JPA 40、DNA 服务 22、core 实现 124、federation 实现 287），四项生产代码 Checkstyle 通过。现有 federation CRUD 测试原来缺失授权上下文，已补显式测试授权和逐用例清理，并新增无授权仍拒绝写入的回归；没有放松生产权限。此阶段仍未部署，DNA 专用数据预览、用户选择和授权穿梭尚未完成，不以主列表通过替代这些子列表验收。

阶段 5（2026-09-19，Common 第一批）：国家、语言、地区、时区、命名空间、翻译消息六类列表声明持久化标量查询字段，沿用统一表格读取的真实 schema 契约。保留国际化参考数据原有全局可见语义，不用记录创建人误过滤共享翻译；软删除仍必需生效，创建人及删除标记不开放客户端查询。

新增 `I18nTableQueryHttpTest` 12 项真实 Controller → Service → H2 回归，覆盖 AND/OR/NOT、表头 JSON 与高级条件叠加、相同主排序值下第二排序字段生效、分页/count、实际 schema、非法字段/排序和分页上限。国家名称有唯一约束，排序并列测试使用可重复的默认时区字段，不修改数据库约束。Common 本批 374 项测试通过（core 299、JPA 40、Common 服务 22、i18n API 11/service 2），core 与 i18n API 的生产代码 Checkstyle 通过；六工程 TypeScript 检查通过。六页面实际 schema 的浏览器联调仍待验收，尚未部署。

阶段 6（2026-09-19，Common 租户目录）：应用、套餐、租户和组织四类实体声明可查询标量字段。`ApplicationServiceImpl.limit`、`PackageServiceImpl.limit` 与 `TenantServiceImpl.limit` 改用独立授权集合，不再覆盖用户的编码/ID 条件。新增 `BaseRepository.limitInScope` / `BaseServiceImpl.limitInScope`：授权标识按原始字符串集合绑定，与用户条件、租户及软删除外层 AND；空集合拒绝所有记录但仍验证非法查询；结果和 count 使用同一授权快照。此集合只来自服务端权限数据，不把用户 IN 的 100 项限制扩大，也不通过逗号串传递授权标识。泛型 `BaseEntity<I>.id` 按实际实体解析类型，避免 Hibernate 元模型的 Serializable 擦除类型导致租户列表拒绝查询。

新增 `TenantCatalogQueryHttpTest` 10 项真实四类 Controller → Service → H2/schema 回归，验证筛选、分页/count、排序、跨租户/未授权和软删除隐藏、平台管理员分支、空授权以及 153 项授权编码。公共 JPA 新测试验证含逗号/冒号的授权值按字面值处理、同名用户条件仍取交集、空授权不跳过校验。531 项后端测试通过（core 299、JPA 41、Common 服务 32、tenant API 22/implementation 137），API/core/JPA/tenant API 四项生产代码 Checkstyle 通过；补充了 tenant API 中原来缺失的六处查询方法说明，没有禁用检查。

公共查询层变更后，另外运行 AI 服务 100、DNA 服务 22、auditing 服务 10、personal 实现 13/rest 11 项回归，156 项全部通过；与本批上述 531 项合计 687 项。构建环境为 JDK 21，仅使用测试数据库，不修改业务数据。

SimpleTable 的自定义 `loadPage` 现在也接收统一传输编码后的筛选条件，修正组织页遗漏同名 `sort` 字段筛选的问题，个人列表 JSON 适配继续兼容。159 项前端单测、六工程类型检查、国际化、五应用构建、MF 预算和 56 个暴露模块浏览器回归通过；浏览器业务接口仍是隔离 fixtures，不冒充真实账号验收。

**阶段 6 发布阻断项更新：** 套餐分配应用、租户分配套餐的千万条请求和 `/items` 忽略筛选已在阶段 7 修正；组织树估算总数和前 100 条子节点截断已在阶段 8 修正，均尚未部署。成员列表和其余权限穿梭仍需逐专用路径验证。这些子列表未完成前不能把四类主接口通过视作页面整体完成。

阶段 7（2026-09-19，分配弹窗）：新增平台 `AssignmentTable`，套餐→应用和租户→套餐两侧独立服务端分页，复用 DataTable 的表头筛选、排序、高级条件、视图及平台动作按钮。默认每页 20、最多选择 100 条；不再全量读取已分配标识或请求千万条数据。作用域/父对象改变时重建会话；同步提交锁阻止重复移动，失败保留选择，成功后两侧重新查询并修正空末页。

后端新增只接收服务端构建值的 `QueryMembership` 与 `limitMemberships`，授权候选集合与已分配/未分配集合分别编译为 IN/NOT IN，再与用户条件及软删除外层 AND；不扩大公共用户 IN 条件预算，不使用逗号拼接编码。两个 `/items` 接口处理真实筛选、排序、分页/count，并提供同权限 `/items/schema`；应用候选与租户授权取交集，套餐分配读取保持平台管理员要求。

Common 新增 5 项真实 Controller→Service→H2/schema 回归（租户目录共 15 项），覆盖超过 100 个分配标识、两侧分页/count、空分配、软删除、过滤交集及两种越权父对象拒绝。相关 692 项后端测试通过（core 299、JPA 41、Common 37、tenant API 22/implementation 137、AI 100、DNA 22、auditing 10、personal implementation 13/rest 11），API/core/JPA/tenant API 生产源码 Checkstyle 通过。

前端完整 `pnpm check:frontend` 通过：160 项单测、六工程类型检查、国际化、五应用构建、MF 预算及 56 个暴露模块浏览器回归。新增套餐应用分配回归使用 240 条隔离 API 数据，验证第 100 条后的已选项、跨页检索、排序重置页码、保存/恢复视图、表头与高级条件取交集、失败保留勾选、快速重复提交和双向刷新。测试等待旧筛选浮层退出动画及两侧独立刷新完成，避免误操作旧 DOM 或把尚在加载当作筛选丢失。租户套餐使用同一组件，真实后端已验证，其独立页面浏览器联调仍待验收。此阶段没有变更业务数据，也尚未部署整批改造。

阶段 8（2026-09-19，组织树）：组织管理新增 `/tree` 真实分页/count 接口，保留选择器的 count-free `/options`，不再用 hasNext 估算管理列表总数或额外请求 options 装饰每一页。父节点、当前租户和软删除是服务端外层 AND 条件；子节点标记按当前页批量查询。默认按可读的 sort/name/code 排序，再由公共仓库保证唯一 ID 顺序；被隐藏/脱敏的字段不用于默认排序，用户显式查询这些字段仍返回 400。树结果在 HTTP 边界使用组织实体的字段策略投影为普通 Map，隐藏字段不输出、脱敏字段不泄露原值，不依赖某个 Jackson 版本的 DTO 序列化插件。

组织页无筛选/排序时显示根节点树；用户筛选、排序或恢复视图时切换为全层级匹配结果，避免展开混入不匹配的子节点。共享 TreeTable 支持每父节点独立懒分页，组织每次加载 50 条；可继续加载超过 100 条的子节点，失败保留现有行并重试同一页。没有伪造可勾选的“加载更多”数据行。加载/重试控件跟随用户设置后的首个可见业务列，隐藏名称列也不会失去入口。切换菜单取消未完成请求但保留展开内容；换查询/页码/作用域会取消并隔离旧响应，旧请求完成不能清掉新请求的锁。

新增 4 项子节点分页状态测试及 2 项真实组织 Controller→Service→H2 回归（租户目录测试共 17 项），验证 125 个有效子节点跨页、真实根节点总数、租户/软删除/父节点交集、字段隐藏/脱敏与排序约束。相关 694 项后端测试全部通过（core 299、JPA 41、Common 39、tenant API 22/implementation 137、AI 100、DNA 22、auditing 10、personal implementation 13/rest 11），API/core/JPA/tenant API 生产源码 Checkstyle 通过。

最终 `pnpm check:frontend` 通过：164 项单测、六工程类型检查、国际化、五应用生产构建、MF 预算及 56 个暴露模块浏览器回归。新增组织回归验证 120 个根节点的真实总数、125 个子节点分批加载、隐藏名称列后的继续加载、失败/重试、切菜单保留展开、全层级高级查询和保存视图恢复。个人待办键盘筛选测试等待新增抽屉完成关闭，避免关闭动画恢复焦点与测试键盘事件竞争。浏览器使用隔离 API fixtures，后端使用独立 H2；未操作业务数据、未部署整批改造。

仍待处理：MCP 快照历史目前只取服务端前 100 条，不能作为本地全集；数据库预览、Redis、账号/成员、运行时分页、权限穿梭、Common 其余标准 CRUD 必须继续补齐专用服务端查询。此阶段未部署整批变更，不能视为全平台完成。

阶段 9（进行中，成员列表）：新增显式标量投影查询适配器 `ProjectionQuery`，在 SQL 分页前处理表头与高级条件、多列排序，结果和 count 共用租户/软删除/账号联表约束。成员姓名的 COALESCE 与启用状态表达式显式保留 Java 类型，避免 Hibernate 延迟推导类型导致合法文本/布尔条件被拒绝。`PermissionFields` 将联表展示字段关联到源字段权限，隐藏或脱敏字段不开放过滤/排序，输出使用显式 Map 投影。成员列表接入真实 `/tenant-members/schema` 和 DataTable 远程查询。

本阶段当前验证：core、JPA、Common 服务、RBAC core API/implementation 合计 482 项测试通过，其中成员 Controller→Service→H2/schema 新增 6 项，资源管理 3 项（新增排序数字字段筛选与重复排序参数共同使用的回归）；六工程 TypeScript 与 6 项前端筛选专项测试通过。core/JPA 生产 Checkstyle、成员 API 两个修改文件的定向 Checkstyle 通过；RBAC core API 全模块 Checkstyle 有其余文件的既有问题，不能宣称全模块静态检查通过。构建记录 `/tmp/platform-member-query8.log`，验证镜像 `simplepoint-query-verify:member-tables`。

阶段 9 补充验收（2026-09-19）：成员真实 HTTP schema 导出为浏览器 fixture，并在 Gradle 测试资源中自动比较实际响应，防止手写模拟契约与生产脱节。浏览器用 145 条独立数据验证第 100 条之后的数据、姓名筛选、布尔 false 的 JSON 类型、服务器排序/页码重置、高级 ID 条件与表头交集、保存视图及 INVALID_QUERY 后清除恢复。成员页加入独立滚动容器；短视口下检查分页几何与真实命中元素，避免分页被裁剪。隐藏字段从页面列定义移除，脱敏布尔值不误显示为“启用”。

DataTable 补齐与标准表格一致的图标式清除全部筛选入口，保留当前排序。查询抽屉关闭后卸载表单 DOM，仍由组件状态保留未应用的草稿；避免缓存多个标签页时保留多份隐藏表单。保存视图的资源键不再包含序列化的 schema，防止服务重启后 Map/JSON 属性顺序改变导致视图丢失；应用时仍按当前查询白名单校验。浏览器完整刷新后，模拟服务器改变查询结构的属性顺序，实际恢复并执行原保存视图通过。

另新增泛型用户 ID 筛选测试，在修改前复现 `Serializable` 擦除类型导致字符串条件被拒绝的问题，显式绑定投影为 String 后通过。最终相关后端 798 项、97 个测试类全部通过（成员 HTTP/SQL/schema 共 7 项）；core/JPA 与成员 API 定向 Checkstyle、tenant API Checkstyle 通过，core API 全模块静态检查的既有问题仍如上记录。完整 `pnpm check:frontend` 通过：164 项测试、六工程类型检查、国际化、五应用生产构建、MF 预算及 56 个模块浏览器回归，无非预期异常/警告。日志 `/tmp/platform-member-quality-final.log`、`/tmp/platform-member-id-green.log`、`/tmp/platform-member-regression-final.log`，最终后端验证镜像 `simplepoint-query-verify:member-regression`。

阶段 9 的租户用户分配遗留项已在阶段 10 补齐，见下。成员新实现与阶段 1–8 整批改造仍未部署。资源管理排序参数冲突的既有修复已在当前运行的 Host/Common 中，本轮未重新部署或修改业务数据。

阶段 10（2026-09-19，租户用户分配）：租户配置用户弹窗改用平台 `AssignmentTable`，删除请求 100000 条后本地分页及全量读取已分配 ID 的路径。新增 `TenantUserCandidateQuery`，两侧通过 SQL EXISTS/NOT EXISTS 在分页前应用真实表头、高级条件及排序，count 使用相同作用域。所有者即使没有成员关系行也属于已分配侧；软删除关系视为未分配，已删除账号不出现在任一侧，其他租户关系不能影响当前分配。默认按可读姓名排序，唯一用户 ID 稳定收尾。

`/users/items/schema` 与数据接口使用相同父租户成员管理授权，查询缓存键包含父租户参数。可查询展示字段显式声明来源权限；隐藏或脱敏字段不可筛选/排序，响应 Map 投影避免序列化绕过字段权限。不可读用户 ID 拒绝整个分配查询，避免脱敏 ID 被当成操作标识。个人上下文及不匹配的租户管理上下文不能跨租户读取候选或 schema。

公共分配组件增加不可选择的保护标识：所有者勾选和全选均不可选中，提交仍由后端保护。移入/移出最多 100 个标识，同步提交锁防双击；失败保留选择，成功两侧重查，切换父租户清空会话。成员新增使用有界关系查询并复用软删除关系，避免唯一约束冲突；移除只清理当前租户成员及角色关系，不删除全局账号或其他租户角色。非法批量及移除所有者返回受控 400。

新增 9 项真实 Controller→Service→Hibernate/H2/schema 回归，覆盖跨第 100 条的分页/count、所有者隐式归属、表头/高级/旧 keyword 交集、多列排序、字段权限、软删除恢复、角色隔离、批量上限、父租户越权和 schema 契约。schema 由真实 HTTP 响应导出，测试强制比对浏览器 fixture。独立租户用户分配页面浏览器回归使用 240 条隔离数据，验证跨页检索、排序重置页码、高级条件与表头取交集、保存/恢复视图、全选保护所有者、失败保留选择/重试、双击仅一次提交、两侧刷新和切换租户隔离；不使用真实业务账号或写入业务数据库。

阶段 10 验证：相关后端 807 项、98 个测试类通过（资源管理 3 项继续通过）；tenant API 生产 Checkstyle 及两个改动实现文件的定向 Checkstyle 通过，不代表整个实现模块既有静态检查债务已解决。完整 `pnpm check:frontend` 通过：164 项前端测试、六工程类型检查、国际化、五应用生产构建、MF 预算和 56 个暴露模块浏览器回归，无非预期异常/警告。日志 `/tmp/platform-user-assignment-quality.log`、`/tmp/platform-user-assignment-regression2.log`；最终验证镜像 `simplepoint-query-verify:user-assignment-regression`。

仍待处理：Common 其余 CRUD、角色/权限穿梭、对象存储、Redis、DNA 专用授权及预览、AI 运行时及知识库子列表等尚未全部验收；租户套餐分配独立页面浏览器回归仍待补齐。阶段 1–10 整批改造尚未部署，不将这一批通过视作全平台任务完成。

以下来自源码扫描。共享组件使用点同时覆盖其调用者，但仍需检查调用者传入的分页/查询能力；表头配置、列表功能和服务端集成验收尚待补齐。

阶段 11（2026-09-19，成员角色分配）：成员“配置角色”弹窗改用平台 `AssignmentTable`。移除请求 100000 条后本地切页与全量 `/users/authorized` 读取，两侧使用当前租户、目标成员、已分配状态的 SQL 分页/count。`UserRoleCandidateQuery` 通过 EXISTS 查询成员和分配关系，候选仅含当前租户未删除角色；失效关系不隐藏可选项，其他租户或成员的关系不影响当前侧。字段契约覆盖 ID、名称、角色标识及描述，来源权限继承 Role，脱敏/隐藏字段不可筛选排序，ID 不可读时不允许配置。

`/users/role-candidates` 与新增 `/schema` 共同校验当前租户管理权限和目标成员，所有者无需旧成员关系行也可使用；个人上下文、个人租户、已删除成员或非成员被拒绝。角色操作最多 100 项，移除也验证目标成员；写入只查询本批关系，重新激活软删除关系、跳过已存在的有效授权。浏览器同步提交锁及顺序重复请求回归均通过；这不等于新增了跨实例并发唯一约束。移除仅影响当前租户/成员，不删除其他租户角色关系。

新增 `UserRoleAssignmentHttpTest` 9 项真实 HTTP/SQL 测试，覆盖超过 100 条的两侧分页/count、AND/OR/NOT、表头及旧 keyword 交集、多列排序、作用域/软删除/权限、非法字段及分页、批量限制、恢复/重复提交和 schema 契约。真实 schema 导出并与浏览器 fixture 强制比较，使用生产 Jackson 3 响应转换器；角色候选和阶段 10 的租户用户候选增加 Spring 持久化异常转换代理回归，并显式声明校验异常，确保非法查询仍为安全 400。

独立角色分配浏览器回归使用 240 条隔离数据，验证跨第 100 条的分页、全范围检索、排序重置页码、高级条件交集、视图保存/恢复、失败保留选择/重试、快速双击、双侧刷新、切换成员与 schema 缓存隔离。开发 MSW 同步实际契约及筛选/排序/分页、分配状态，避免开发模式继续使用旧截断接口。完整 `pnpm check:frontend` 已通过（164 项测试、六工程类型检查、国际化、五应用构建、MF 预算及 56 个模块浏览器回归）；日志 `/tmp/platform-role-quality2.log`。新投影、DTO、修改的 UsersServiceImpl 定向 Checkstyle 通过，日志 `/tmp/platform-role-assignment5.log`。相关后端跨服务回归合计 816 项、99 个测试类，零失败/错误；日志 `/tmp/platform-role-regression.log`，验证镜像 `simplepoint-query-verify:role-assignment-regression`。其中角色分配、租户用户分配各 9 项，资源管理 3 项；租户分配实现定向 Checkstyle 再次通过。

阶段 11 剩余的组织多选器已在阶段 12 迁移，随后确认旧穿梭组件没有调用方才删除。角色主列表及资源授权树、其余 Common 专用页、Redis、DNA/AI 子列表、租户套餐独立浏览器验收仍未全部完成。阶段 1–11 整批尚未部署，本轮没有修改真实业务数据。

阶段 12（2026-09-19，表单组织多选器）：`OrgTreeMultiSelect` 复用平台 `AssignmentTable`，保留表单草稿语义。新增只读 `POST /platform/organizations/selection` 和同权限 `/schema`；POST 仅携带草稿 ID，不保存组织或数据权限。两侧均在 SQL 中先应用当前租户、软删除、草稿成员关系及用户条件，再排序、分页和 count，不再估算总数或全量解析已选组织。新增显式投影包含 ID、名称、编码、类型、描述和启用状态，源字段权限同时约束 schema、筛选/排序和实际响应。

草稿传输协议最多 2000 个有效 ID、单个 ID 最长 128 字符，移动操作仍最多 100 项；这是独立的只读草稿请求预算，不扩大公共筛选 IN 的 100 项限制，也不把客户端选择伪装成服务端授权集合。即使草稿含外租户、已删除或不存在的 ID，也不能查出相应组织。超过预算的既有值明确提示并完整保留，不请求截断数据；用户可以明确清空草稿后继续选择，关闭未提交表单不会覆盖旧值。表格错误、分页和筛选也不修改草稿。

`AssignmentTable` 增加自定义只读加载器、输入查询键、禁用状态和选择动作名称；持久化分配仍按原流程刷新，草稿由受控值变化刷新两侧，避免额外刷新一轮。数据权限页面的条件 schema 变换现保留源字段 `readOnly` 等元数据，修复组织字段在条件显示时丢失只读权限的问题。开发 MSW 同步新协议及完整查询、作用域和分页。旧 `EntityTransfer`、`STableTransfer` 两个文件已删除，源码全仓确认无调用，组件审计去掉其旧名称；可从 Git 历史恢复旧组件，没有删除业务数据。

新增 `OrganizationSelectionHttpTest` 7 项生产转换器/实际 SQL 回归：超过 100 项的两侧真实计数、AND/OR/NOT 与布尔/表头组合、多列排序、空草稿、字段权限、租户/软删除隔离、2000/2001 边界及公共 IN 预算、无租户拒绝、真实 schema 导出并强制匹配浏览器 fixture。测试包含真实 Spring 持久化异常转换代理，非法查询仍为受控 400；统计断言一次取页加 count 共两条查询，草稿查询不更新实体。

真实数据权限表单浏览器回归覆盖跨页选择、表头/高级条件、排序重置页码、保存/恢复视图、失败重试、取消恢复、仅显式提交时保存 121 个完整 ID、2001 项旧值不截断以及只读状态下禁止选择/清空。专用测试页结束后关闭，避免影响独立的 8 页缓存预算测试；未扩大缓存或放宽原有断言。前端完整 `pnpm check:frontend` 通过（164 项测试、六工程类型检查、国际化、五应用构建、MF 预算及 56 个暴露模块浏览器回归），日志 `/tmp/platform-org-selection-quality.log`。

阶段 12 后端相关 823 项、100 个测试类，零失败/错误；tenant API Checkstyle 及两个组织实现文件定向 Checkstyle 通过。日志 `/tmp/platform-org-selection2.log`、`/tmp/platform-org-selection-regression2.log`，验证镜像 `simplepoint-query-verify:org-selection-regression`。阶段 1–12 整批仍未部署，不将本批完成等同于全平台验收完成；下一批继续 Common 主列表及其余服务专用查询。

阶段 13（2026-09-19，Common 主列表）：角色、数据权限、字段权限、字典、字典项、远程模块和外部身份提供商七类实体显式声明查询字段。权限策略保持租户边界，共享字典、模块及身份提供商保持原有全局配置语义；密码明文/密文、插件内部标记、集合及隐藏上下文字段不开放查询。`CommonMainTableQueryHttpTest` 通过真实 Controller、Service、带持久化异常转换的仓库代理、生产 JSON 转换器和独立数据库验证全部声明字段的类型化查询与排序，以及 AND/OR/NOT、表头叠加、多列排序、分页/count、权限限制和安全 400。

字典项弹窗的父字典不再存于可清空的普通筛选中。前端始终单独发送 `parentDictionaryCode` 并按父字典隔离列表缓存；后端 `limitInDictionary` 将父字典与用户条件外层 AND，恢复视图或冲突的同名条件只能缩小结果，不能换掉父范围。开发 MSW 也改用真实 schema、完整集合查询后分页，不再返回固定第一页。数据权限和字段权限的 EAGER 集合增加批量加载，真实数据库断言 20 行列表至多三条语句（取页、计数、一个集合批次），保留编辑所需原数据。

逐字段 PostgreSQL 回归发现远程模块的 `@Lob String` 描述列不能直接用于 `lower()`，且其 OID 不能代表文本排序。公共 `QueryExpressions` 为查询与排序读取真实文本：PostgreSQL 解引用大对象，其他当前验证的 H2 路径显式转换文本；不迁移字段、不重写已有内容。新增长于 700 字符、中文、字面 `%`/`_`/反斜杠、等值/集合和内容顺序回归。PostgreSQL 17 一次性测试库的 26 项专项测试全部通过，测试容器及匿名数据卷已清理，未触碰业务库。

七类真实 HTTP schema 导出至 `scripts/common-main-schemas`，后端强制比对浏览器 fixture。生产前端浏览器回归验证超过 100 行的分页、表头/高级条件交集、排序重置页码、图标入口及视图保存/恢复；字典项另验证错误重置仍保留父范围、切换字典不串缓存、跨父字典恢复旧视图不会泄漏旧父的数据。组织草稿补充禁用组织不可新选、已选禁用组织可移除且取消不落库的断言。完整前端质量门禁通过（164 测试、六工程类型检查、国际化、五应用构建、MF 预算、56 暴露模块回归），日志 `/tmp/platform-common-main-quality3.log`；业务 API 均为隔离 fixtures，不冒充真实账号写入验收。

本批最终 H2/跨模块回归 970 项、118 个测试类，零失败/错误/跳过，日志 `/tmp/platform-common-main-regression2.log`，验证镜像 `simplepoint-query-verify:common-main-regression`；上述 PostgreSQL 专项 26 项为额外的跨数据库执行，日志 `/tmp/platform-common-main-postgres3.log`。JPA、tenant API、OIDC API 的生产代码 Checkstyle，以及本批四个 security 实体、字典项服务/控制器的定向 Checkstyle 通过。security-core 全模块 Checkstyle 另有八个无关文件的 30 项既有告警，不能将定向检查表述为整个模块通过。

阶段 1–13 整批仍未部署。后续继续账号/账号审计、OAuth 客户端、通知、存储与剩余专用列表，以及早期主列表尚缺的真实 schema 浏览器验收，不能据本批七个主列表通过宣布全平台完成。

阶段 14（2026-09-19，平台账号与账号审计）：两类专用 SQL 列表改为复用 `ProjectionQuery`，接入同一 `$query` 条件树、类型化表头、多列稳定排序、分页和 count。账号 DTO 显式开放六个标量字段，审计开放八个标量字段，最大页大小均为 50；角色集合不伪装为标量查询字段。`prepareIgnoringCase` 仅对该领域显式指定的文本字段保留原有大小写不敏感语义，不改写整个 JSON，不改变其他投影的匹配语义，也不修改展示/排序原值。所有平台读取仍逐次复核数据库账号与平台身份，保留授权撤销、禁用、已删除账号及平台/租户上下文边界。

账号页只投影需要的六列，再以当前页 ID 一次批量读取角色，避免逐行身份查询和加载整页账号凭据。真实 Hibernate 统计验证 20/50 行均至多五条语句（授权账号读取和刷新、分页、计数、角色批次），仅授权账号被实体化。投影通过 `PermissionFields` 继承 User/PlatformIdentity 的来源策略，响应使用显式字段投影，schema、查询与 JSON 响应共同禁止隐藏/脱敏字段推断。前端容忍角色数组被隐藏/脱敏；根据实时能力及必要字段完整性禁用编辑/身份替换，避免把脱敏值或未知角色回写。

统一 Table/DataTable 读取 `x-query.maxPageSize` 限制分页选项，不再为上限 50 的接口提供 100 条选择；本地表格仍保留自己的分页配置。两个生产 HTTP schema 导出为浏览器 fixture，并由后端测试强制比对。`PlatformAccountQueryHttpTest` 的 9 项测试在 H2 和 PostgreSQL 17 均通过，包括逐个声明字段查询/排序、AND/OR/NOT、表头叠加、日期范围、NULL/空文本、字面特殊字符、权限撤销、敏感字段拒绝及分页预算。PostgreSQL 使用独立一次性库，测试容器及匿名数据卷已删除，业务库未更改。

浏览器在 240 行隔离数据上验证两页真实 schema 的跨页筛选、排序、视图保存/恢复、图标入口与 10/20/50 分页；账号另验证完整账号可操作、脱敏角色不误显示超级管理员且不允许身份编辑，以及查询失败重置恢复。完整前端质量门禁通过（168 单测、六工程类型检查、国际化、五应用生产构建、MF 预算和 56 暴露模块），日志 `/tmp/platform-account-quality5.log`。相关后端最终共 979 项、119 个测试类，零失败/错误/跳过，日志 `/tmp/platform-account-regression.log`，验证镜像 `simplepoint-query-verify:platform-account-regression`；PostgreSQL 专项日志 `/tmp/platform-account-postgres.log`。JPA 生产代码 Checkstyle，以及账号 DTO、两个查询/服务文件、审计实体和控制器的定向 Checkstyle 通过，日志 `/tmp/platform-account-query5.log`。

阶段 1–14 整批仍未部署，不能将本批两页完成等同于全平台完成。后续仍包括 OAuth 客户端、通知、存储、权限子列表、Redis、DNA/AI 专用子列表及早期主列表缺少的实际 schema 浏览器验收，再进行整批重建部署。

阶段 15（2026-09-19，OAuth 客户端与系统通知）：两类实体分别显式开放 11/13 个查询字段，沿用全局配置/通知管理原有权限和软删除边界，不把密钥、内部设置或版本号开放为查询字段。通知标题/内容的旧文本兼容层只包装未带操作符的普通文本，不再破坏结构化表头 JSON、等值、集合或否定条件。OAuth 列表和配置响应不再返回客户端密钥，实体/配置 DTO 均明确仅写入；实际 schema 保留密码输入，新建表单的密码框已通过浏览器验证，列表分页也保持在页内。

真实 HTTP 权限断言发现 Spring Boot 4 的 Jackson 3 转换器未使用原有 Jackson 2 字段权限模块。新增独立 Jackson 3 模块，共用 `FieldPermissionPolicy`，保留旧 schema mapper；逐请求应用隐藏/脱敏，不缓存用户权限、不修改持久化实体。四项专项回归覆盖自动配置注册、缓存序列化器切换权限、JSON 字段别名、投影来源、严格策略合并、数组形状及空值；原始 Map 仍须显式领域投影，不能推断其权限归属。九类 Common 主列表现在均断言实际 HTTP 响应的隐藏/脱敏，而不只检查查询白名单和 schema。

`CommonMainTableQueryHttpTest` 扩为 34 项，H2 和独立 PostgreSQL 17.11 均通过，覆盖两页全部声明字段的查询/排序、组合条件、分页/count、非法条件、通知旧协议兼容、密钥不回传且输入仍可反序列化。生产 HTTP schema 导出并强制匹配浏览器 fixtures；OAuth 开发 MSW 同步真实 schema 和先筛选/排序再分页。PostgreSQL 测试容器及匿名卷已删除，未改动业务库。

完整前端门禁通过：168 单测、六工程类型检查、国际化、五应用生产构建、MF 预算及 56 个暴露模块浏览器回归，日志 `/tmp/common-control-quality3.log`。Common 的 11 类列表浏览器验证真实 schema、超过 100 行的分页/筛选、条件交集、排序、图标入口和视图恢复；通知另外验证显示“草稿”但传输 `in: [DRAFT]` 的类型化枚举条件，不混用标签与值。浏览器使用隔离 API fixtures，不冒充真实账号写入验收。

后端 16 模块最终共 1004 项、123 个测试类，零失败/错误/跳过，日志 `/tmp/common-control-regression.log`，验证镜像 `simplepoint-query-verify:common-control-regression`；PostgreSQL 专项日志 `/tmp/common-control-postgres.log`。core、OIDC API、notification API/service 生产代码 Checkstyle 通过，日志 `/tmp/common-control-final.log`。资源管理原有 3 项回归再次通过，并核对当前运行 Common 的 JAR 确实包含分页/排序保留参数修复；这项既有修复已部署，不代表阶段 1–15 整批已经发布。

阶段 1–15 整批仍未部署。剩余包括存储配置/对象/配额、权限子列表、Redis、DNA/AI 专用子列表、租户套餐独立页面及早期主列表实际 schema 浏览器验收。配额的使用量/剩余量是派生字段，下一阶段必须先解决数据库查询投影，不能对当前页装饰后冒充全量筛选。

阶段 16（2026-09-19，对象存储三类主列表）：OSS 配置、当前租户文件、租户配额均声明查询字段并提供同读取权限的 `/schema`。前端继续使用平台 DataTable，三页共用服务端查询 hook 和真实 schema，支持类型化表头、条件树、多列排序、服务器保存/恢复视图及图标入口。移除重复顶部检索框，分页/查询状态纳入平台按用户/租户隔离的查询缓存，非活动页暂停刷新；失败保留筛选入口。开发 MSW 同步 schema 和先筛选/排序再分页，不返回固定第一页。

文件列表将服务端租户约束与用户条件分离，以 `limitScoped` 外层 AND 应用；未开放 tenantId，客户端不能覆盖租户或软删除。配置列表的“凭证已配置”使用 SQL 布尔表达式，允许查询是否配置但不开放密钥、密文和 Access Key 内容。原始文本兼容层不再改写结构化 JSON。修复 eTag 的 JSON/schema 属性声明，使查询字段、实际响应与元数据一致。

配额使用只读 `StorageQuotaReadModel` 内联 SQL 投影：先按租户聚合有效对象字节数，再连接配额和未删除的租户名称；使用量、剩余量和租户名称都在 SQL 中参与条件、排序及 count，不再仅装饰当前页。不限额保持 NULL，超额剩余为零，无对象使用量为零。映射使用 `@Immutable`/`@Subselect`/`@Synchronize`，不新建业务表，也不缓存派生实体；取页和 count 共两条 SQL，无逐租户查询。全局配额用量不受其他调用遗留的 tenantFilter 扭曲，同时不关闭调用方的过滤器。新实体置于平台扫描的 `entity` 包，并用生产扫描模式回归，避免只在显式注册的测试中可用。

派生值用 `PermissionFields` 继承源字段限制：对象大小受限时，配额用量和剩余量也无法筛选/排序或通过 HTTP 原值泄漏。前端安全展示脱敏字节数及布尔值，不把脱敏值当成真实启用状态。OSS 配置页不再请求无关且权限不同的可用提供方接口；配额租户选择数据改为打开表单后加载，主列表不再等待整个选择目录。

新增 `StorageTableQueryHttpTest` 16 项通过 H2 和 PostgreSQL 17.11：全部声明标量逐字段查询/排序、AND/OR/NOT 与表头交集、超过 100 行的全范围分页/count、NULL/超额语义、敏感字段和分页预算拒绝、HTTP 字段权限、租户边界、聚合实时更新、生产实体扫描及实际 schema 强制比对。PostgreSQL 使用一次性测试库，容器及匿名卷已删除，业务库未修改。日志 `/tmp/storage-postgres.log`；PostgreSQL 验证镜像 `simplepoint-query-verify:storage-postgres`。

最终相关 18 模块共 1120 项、138 个测试类，零失败/错误/跳过；Storage API/implementation 全模块生产代码 Checkstyle 通过，日志 `/tmp/storage-regression.log`，验证镜像 `simplepoint-query-verify:storage-regression`。完整前端门禁通过（168 单测、六工程类型检查、国际化、五应用生产构建、MF 预算和 56 暴露模块浏览器回归），日志 `/tmp/storage-quality3.log`。Common 浏览器回归扩到 14 类列表，使用真实 schema 和 240 行隔离 fixtures 验证跨页筛选、排序、条件交集及视图恢复；不冒充真实用户写入验收。

补充浏览器回归验证三类存储列表查询 400 后保留工具并能重置恢复，以及配额字节数为脱敏值时安全显示。复用上述最终生产构建执行 `pnpm test:mf:browser`，通过，日志 `/tmp/storage-browser-final.log`。

阶段 1–16 整批仍未部署。存储三类主列表查询已补齐，但存储表单的租户选择器仍只取前 200 条、自定义编辑表单/按钮的字段权限交互还需专项补齐；不能把主列表完成当成整个存储模块验收完成。下一阶段连同权限选择器前 100 条截断一起处理，后续还有 Redis、DNA/AI 专用子列表、租户套餐独立页和早期主列表实际 schema 浏览器验收，最后整批重建部署。

阶段 17（2026-09-19，权限与租户远程选择器）：新增平台 `EntitySelect`，角色配置的两类范围、资源中心的两类范围、存储配额的租户共五处复用。删除首 100/200 条目录再本地过滤的实现；统一服务端每页 20 条、名称搜索防抖、滚动或显式加载更多。已选项不在当前搜索页时，通过原有受权限约束的列表接口按 ID 精确读取一条。失败保留原值并显示重试，不自动清空或提交；租户配额新建不再默认选中第一条租户。

数据范围、字段范围、租户显式开放 ID 查询；没有增加绕过授权的详情接口。实际 HTTP 回归暴露继承泛型 ID 被解析为 `Serializable` 的问题：`QueryCapabilities` 与 JPA `QueryExpressions` 现在都按具体实体解析泛型，schema、操作符与参数类型一致，不强制转换所有 Serializable，也不为 ID 增加 SQL CAST。精确查找仍受租户/成员、数据范围、软删除及字段权限约束。DataScope/FieldScope 浏览器契约重新从真实 HTTP schema 导出并强制比对。

分页缓存复用平台租户/角色/context 隔离、取消信号和非活动页暂停机制。`scopedQueryOptions` 增加显式分页参数泛型，并用 `NoInfer` 防止普通查询被调用位置误推导成 infinite query；新增真实 QueryClient 回归验证下一页参数及旧权限域拒绝发送。移除无调用的 `api/system/data-scope.ts` 和 field-scope 的首 100 条旧查询方法；它们属于可由 Git 恢复的源码清理，不删除业务记录。

验证：H2 及 PostgreSQL 17 的 Common 主列表/租户目录 54 项专项通过，包含精确回显、其他租户/无成员/已删除不可见；一次性 PostgreSQL 容器和匿名卷已删除，未修改业务库。最终 18 个相关模块共 1124 项、139 个测试类，零失败/错误/跳过，资源管理原 3 项回归继续通过；验证镜像 `simplepoint-query-verify:entity-select-regression`，日志 `/tmp/entity-select-regression.log`。core、tenant API 全模块生产 Checkstyle，以及本次 JPA/权限实体改动文件定向 Checkstyle 通过，日志 `/tmp/entity-select-style.log`。

完整 `pnpm check:frontend` 通过：171 单测、六工程类型检查、国际化、五应用生产构建、MF 预算及 56 暴露模块浏览器回归，日志 `/tmp/entity-select-quality-final.log`。五处选择器使用隔离 240 行数据验证超过旧 100/200 条边界的分页/搜索、当前页之外已选项精确回显、回显与搜索失败重试、角色显式保存及资源中心/配额取消不提交；未对真实角色/配额执行写入。

阶段 1–17 整批仍未部署。新增确认：资源中心左侧角色目录独立使用 page=0/size=100，并在本地搜索，下一阶段需要修复；本次只完成其中两个权限范围选择器，不能据此宣布资源中心全功能完成。存储自定义编辑表单/按钮的字段权限交互、权限资源子树、Redis、DNA/AI 专用子列表、租户套餐独立页及早期主列表真实 schema 浏览器验收仍待处理，最后整批重建部署。

阶段 18（2026-09-19，资源中心角色目录）：删除左侧 page=0/size=100 后本地搜索的卡片目录，改用平台 DataTable。独立角色名称/编码/描述/资源数/用户数表头与高级查询、稳定排序、分页、条件摘要、服务器视图保存/恢复及图标入口已接入；原顶部搜索框与过时卡片样式、旧前端目录 fetch 方法移除。抽取 `DataTable/useRemoteList` 复用上一阶段存储查询/错误恢复逻辑，存储保留兼容 re-export，没有复制第二套查询实现。

原 `/access-center/roles` 保留嵌套 role 与范围摘要响应，新增同权限 `/roles/schema`；查询模型 `AccessCenterRoleRowVo` 明确六个字段、最大页 100，客户端仅展开响应供表格使用。新增 `AccessCenterRoleQuery` 在 SQL 中按当前租户及有效角色查询，资源/用户数量使用相关子查询先去重并排除失效关系、无效用户，再参与条件与排序，不在当前页计数后伪装全量筛选。角色目录每页及 count 两条 SQL，加上范围摘要批量读取最多六条，不逐角色请求服务。权限范围保留独立绑定优先、显式 NULL 重置覆盖旧授权、旧范围冲突不任选一条的语义；不返回其他租户或软删除策略。

目录/schema 都调用 `RoleService.requireManagementTenant` 复用角色管理的租户所有者/管理员授权。表格计数继承来源关系字段权限，嵌套 Role 与 DataScope/FieldScope 摘要保留来源实体字段权限；隐藏/脱敏字段不开放查询。该领域原角色管理不应用业务数据范围，未借本次目录查询扩大为跨租户角色查询。

授权草稿不再随目录翻页/筛选而切换到新页首角色；当前角色标题来自详情，离页后仍明确显示正在编辑谁。切换角色及关闭标签页走未保存确认，后台 focus 不自动刷新覆盖详情草稿；租户/角色/context 变化重建编辑会话。新增开发 MSW 目录使用同一真实 schema 和 240 条隔离数据，在分页前筛选排序并保持显式保存语义。

性能支持索引已加入 Role、RoleResourceGrant、UserRoleRelevance 映射，覆盖 tenant/deleted/name/id 或 tenant/role/deleted/resource/user。已有库升级脚本为 `doc/deployment/sql/20260919-access-center-query-indexes.sql`，需在事务外逐条执行并验收再发布；当前**仅已生成脚本和验证新建测试库索引，尚未在业务库执行**。本批没有删除业务记录。

新增真实 HTTP+SQL 8 项测试在 H2/PostgreSQL 均通过：超过 100 条分页、派生值查询/排序、OR/NOT 不能越租户、实时计数、批量摘要/显式重置、HTTP 字段限制、无效条件/预算拒绝、管理权限撤销，以及 JDBC 元数据核对三组索引列顺序。真实 schema 已导出为 `scripts/common-main-schemas/AccessCenterRole.json`，常规后端测试强制比对。另增 RolesService 真正的非管理员上下文授权/撤销回归，避免误用只初始化一次的管理员 setter。PostgreSQL 测试容器与匿名卷已清理；日志 `/tmp/access-center-postgres-final.log`。

最终相关 18 模块 1133 项、140 个测试类零失败/错误/跳过，资源管理原 3 项回归继续通过；镜像 `simplepoint-query-verify:access-center-regression`，日志 `/tmp/access-center-regression-final.log`。本次相关四模块改动文件定向 Checkstyle 通过，日志 `/tmp/access-center-style-final.log`，不代表全模块既有静态检查债务均已清除。完整前端门禁通过（171 单测、六工程类型检查、国际化、五应用构建、MF 预算与 56 暴露模块浏览器回归），日志 `/tmp/access-center-quality-final.log`。浏览器新增目录跨页、派生表头与高级条件交集、视图恢复、错误重置、草稿翻页保留/切换取消/关闭取消的端到端验证；接口均使用隔离 fixtures。

阶段 1–18 整批仍未部署。资源中心角色目录查询已验收，不代表资源授权工作台全部完成：影响用户仍全量读取、详情统计口径/来源字段限制及资源子树需继续专项处理；存储自定义编辑表单/按钮的字段权限交互、Redis、DNA/AI 专用子列表、租户套餐独立页及早期主列表真实 schema 浏览器验收仍待处理。完成全量验收及已有库索引升级后再整批重建部署。

阶段 19（2026-09-19，资源中心影响用户）：角色详情的全量用户卡片替换为平台 `DataTable`，同角色独立服务端分页、姓名/邮箱/手机号/ID 类型化查询、排序、表头与高级条件交集、错误重置及服务器视图保存/恢复。当前角色 ID 同时进入接口、缓存与视图范围，切换角色重建列表状态，不能把旧角色的数据或条件带到新角色；入口只显示图标并保留无障碍名称。

新增同管理权限的 `GET /access-center/roles/{roleId}/users` 与 `/users/schema`。`AccessCenterUserQuery` 在 SQL 中先应用当前管理租户、有效角色及有效用户关联，再筛选/排序/分页/count；每页上限 100，角色授权校验加数据/count 共三条 SQL。`RoleUserMembership` 为目录计数、详情计数和用户列表共享 EXISTS 定义，重复关联只计一次，排除已删除用户、已删除关联及其他租户。移除详情中无界的 `assignedUsers` 数组和旧的全量仓库查询，调用方须改用新分页接口，不能继续依赖旧数组。

用户 DTO 继承 User 字段权限，邮箱脱敏、手机号隐藏同时约束真实响应和 schema/查询；用户 ID 不可读，或来源用户/角色关系字段被限制时，不返回用户分页总数以避免关系推断。详情的 `assignedUserCount` 继承概览计数的来源权限，前端支持脱敏值并以 `-` 区分隐藏/未知和真实零值。管理权限、租户以及角色有效性不能被用户 OR/NOT 条件绕过。

新增七项真实 HTTP/SQL 测试；资源中心共 15 项在 H2 和一次性 PostgreSQL 17 均通过，包括超过 100 条分页、去重、失效记录、三处计数一致、实际 schema 强制比对、字段与关系权限、非法条件及管理权限撤销。独立测试库及匿名卷已删除，未修改业务库。新增 `scripts/common-main-schemas/AccessCenterUser.json` 来自真实接口，开发 MSW 和浏览器 fixtures 都使用相同契约。

完整前端门禁通过（171 单测、六工程类型检查、国际化、五应用构建、MF 预算及 56 暴露模块浏览器回归），日志 `/tmp/access-impact-quality-final.log`。浏览器验证第 12 页数据、邮箱排序、表头与高级条件交集、视图恢复、失败重试和切换角色不串条件/数据；API 使用隔离 fixtures，没有操作真实角色。相关 18 模块 1140 项、140 个测试类零失败/错误/跳过，日志 `/tmp/access-impact-regression-final.log`；四个相关模块改动文件定向 Checkstyle 通过，日志 `/tmp/access-impact-style-final.log`。

阶段 1–19 整批仍未部署；既有库三组索引迁移仍未执行。资源授权子树、存储自定义编辑表单/按钮权限、Redis、DNA/AI 专用子列表、租户套餐独立页及早期主列表实际 schema 浏览器回归仍待验收。新核实 AI Runtime 节点/池/密钥/工作负载仍先取前 500 条，必须继续实现服务端查询，不能将当前组件扫描无违规当作全平台完成。

阶段 20（2026-09-19，AI Runtime 四类主列表）：节点、资源池、密钥、工作负载删除首 500 条后本地切页的实现，改用平台 `DataTable/useRemoteList`。接入真实 schema、类型化表头与高级条件、稳定多列排序、独立分页/count、服务器视图与错误恢复；筛选入口仅图标，删除重复的页面刷新按钮。内部标签页暂停非活动列表查询，权限上下文变化重建 Runtime 会话。

四个原列表接口现在读取过滤参数，新增同权限 `/schema`。节点保持平台管理员入口；其他三类保留 `AiScopeAccessPolicy.currentManagementScope()`，系统资源强制 SYSTEM + NULL 租户，租户资源强制 TENANT + 当前租户，所有列表强制未删除。条件通过公共 JPA `limitScoped` 在 SQL 分页之前编译，用户条件不能替换范围；最大页 100，默认创建时间倒序并追加 ID 稳定排序。移除四个不再使用的 service `findAll(Pageable)` 入口，仓库中调度/准入仍使用的专用查询保留。实体只开放显式标量查询字段，不开放密钥明文/密文、内部 JSON、锁或租约、原始错误内容和数组推断查询；身份字段不可读时拒绝行、schema 和分页总数。

资源池/工作负载列表的 MCP 名称改为当前页 ID 批量精确回显，不再读取全部服务目录。部署入口按 serverId 查询一条当前作用域的 Pool，不再在前 500 条中查找。平台 `EntitySelect` 增加复用的远程多选和候选条件，既有单选五处契约不变；候选每页 20，搜索防抖，选中项独立按 ID 回显，超过 100 个既有选中 ID 分批处理而不截断。Runtime 的 MCP 候选限制启用的 MANAGED_OCI，Secret 候选限制启用，使用类型化 JSON 条件；Secret 最多选 16 项与后端上限一致。候选条件不影响旧选中值的受授权精确回显，加载失败保留完整选择，只有明确保存才写入。

补齐资源池部分字段不可读时的操作保护：隐藏镜像摘要不再导致列表渲染异常，缺失/脱敏的配置、配额数字或 Secret/出口引用不能进入完整覆盖编辑；扩缩容也需要真实可读副本数。数值格式化不将脱敏值显示成 NaN。新增 `rowPolicy` 单测覆盖每个必需字段被隐藏/脱敏、非法数值及引用，保持未授权字段不被前端默认值覆盖。

新增 `RuntimeTableQueryHttpTest` 16 项真实 Controller → Service → JPA/SQL 与生产 Jackson 回归，通过 Spring 持久化异常转换代理，验证超过旧 500 条边界、OR 与表头交集、系统/租户和软删除范围、全部 73 个公开字段的编译与排序、最大页、字段限制/脱敏、隐藏 ID 及四个真实 schema 与浏览器 fixture 的强制比对。H2 和一次性 PostgreSQL 17 均通过；PostgreSQL 日志 `/tmp/runtime-query-postgres.log`，验证镜像 `simplepoint-query-verify:runtime-postgres`，测试容器及匿名卷已删除，未修改业务库。相关 20 模块测试任务合计 1224 项、160 个测试类零失败/错误/跳过；日志 `/tmp/runtime-query-regression.log`，镜像 `simplepoint-query-verify:runtime-regression`。四个相关模块本次生产改动文件定向 Checkstyle 通过，日志 `/tmp/runtime-query-style.log`。

前端六工程类型检查、173 项单测、国际化、五应用构建和 MF 预算通过（`/tmp/runtime-query-quality3.log`）。该轮浏览器测试因按钮定位误选复制按钮而失败；修正为明确定位编辑按钮后，最终独立浏览器回归全通过（`/tmp/runtime-query-browser-final.log`）：四类实际 schema 列表均到达第 26 页、执行排序与条件交集、保存/恢复视图和错误重置；两个创建表单找到第 500 条之后的服务与密钥并保留多选；既有 Secret 批量回显失败保留两个引用，重试完整恢复，取消不提交；隐藏 Pool 配置不崩溃且禁止覆盖编辑。56 个暴露模块、原五处 EntitySelect、角色影响列表等既有浏览器检查同时通过。浏览器 API 使用隔离 fixtures，不代表真实账号写入验收。

阶段 1–20 整批仍未部署，已有库三组索引迁移仍未执行。Runtime 的 Profile API、MCP 部署/能力子列表、AI Catalog/知识库文档与分块、Redis、DNA 专用列表、资源授权子树、存储编辑权限及早期主列表浏览器验证仍待继续处理；四个 Runtime 主列表通过不代表全部 AI/全平台查询已完成。

阶段 21（进行中，Redis 后台查询基础）：新增 `/redis/entries/query` 及同权限真实 `/query/schema`，沿用实例级 Redis 读取授权。后台 SCAN 去重生成不含键值的元数据观察集，批量管道读取单键 TYPE/TTL/大小；只有完整成功后才在服务端执行平台类型化条件、稳定排序、分页和 count。完成、扫描中、过期、繁忙、超限、超时、失败状态分离，不返回截断结果或把失败伪装成空页。每次查询重新验证字段权限，输出显式投影；隐藏键名拒绝 schema、行和总数，派生编辑能力/永久标记继承来源字段限制。

扫描结果按模式和版本复用，旧版本翻页不静默切换；只允许一个后台扫描，缓存数量、键数、估算内存、扫描时长和保留期均有预算。完成与运行中的结果在成功写入后失效，旧工作不能重新发布；独立截止时间定时器中断阻塞扫描，避免只有返回键时才检查超时。集群路径遍历主节点并检查槽位覆盖及前后拓扑，一次性 Redis 7.4 实例用于真实单节点回归，集群仅有模拟连接测试。

公共 `CollectionQuery`、`QueryValues` 及对应测试从 JPA 移到 core，Redis 不依赖 SQL 实现模块，DNA 混合目录与 JPA 继续使用同一转换规则。新增 13 项缓存/状态/预算/权限/真实并发测试、6 项连接/管道/集群路径测试、5 项真实方法授权代理 → Controller → Service → Redis/schema 回归；真实接口使用 527 个测试键，涵盖七个声明字段、跨 500 条分页、AND/OR/NOT 与表头交集、UTF-8 空格键、字段脱敏、非法条件和只读权限。方法拒绝在独立 MockMvc 中验证 AccessDeniedException，不冒充已验证完整安全过滤器链的 HTTP 403。

阶段 21 当前验证：清理继承验证镜像中的四个已移动旧源码路径，并 clean core/JPA 后，22 个相关模块测试任务合计 1272 项、165 个测试类，零失败/错误/跳过（含资源管理原有 3 项）；日志 `/tmp/redis-query-regression-clean.log`，镜像 `simplepoint-query-verify:redis-regression`。已核对 JPA 产物不再保留旧 CollectionQuery/QueryValues 类或重复测试。core 与 Redis API 的生产 Checkstyle、JPA/DNA/Redis implementation 本次改动文件定向 Checkstyle 通过，日志 `/tmp/redis-query-quality-clean.log`，镜像 `simplepoint-query-verify:redis-quality`。前端未改动，组件守卫复查仍为 149 文件/95 使用点（92 平台、2 渲染器、1 日历）；这不是新的浏览器查询验收。测试 Redis 容器和匿名卷在验证后清理，未连接或修改业务 Redis。

阶段 21 页面接入批次（2026-09-19）：Redis 页面已切换新查询/schema，使用平台 DataTable、权限按钮和图标式筛选/视图，独立保留扫描范围。扫描中、等待、过期、超限、超时和失败不伪装为空分页；标签页暂停轮询，翻页/筛选/视图固定观察集版本，刷新显式重扫。旧同步扫描列表、对应服务方法及三项过时测试已删除，详情和增删改仍保留。精确键名不 trim，删除不再让 Spring 将逗号拆成多个键。MSW 同步新查询契约和实际后端 schema。

本批最终后端 22 模块/165 类/1273 项零失败、错误、跳过（Redis 实际 HTTP 专项 9 项），日志 `/tmp/redis-ui-regression.log`；镜像 `simplepoint-query-verify:redis-ui-regression`。19 个 Redis/审计测试 Java 源文件与镜像同哈希，Redis API 生产及修改实现/控制器定向 Checkstyle 通过，日志 `/tmp/redis-ui-strict3.log`。前端完整门禁 179 单测、六工程类型检查、国际化、五应用构建、MF 预算及 56 模块浏览器回归通过，日志 `/tmp/redis-ui-quality3.log`；最终附加滚动、size 参数冲突、终态恢复用例通过，日志 `/tmp/redis-ui-browser-final.log`。浏览器覆盖超 500 条分页、完整观察集查询、视图保存/恢复、详情按需加载/失败重试及不完整值禁止编辑。测试 Redis/匿名卷已清理，业务数据未改动。

阶段 21 详情保障批次（2026-09-19）：固定只读 Lua 返回有界预览，字符串 GETRANGE 最多 262144 原始字节，集合使用有界选择/裁剪，不再完整 HGETALL/SMEMBERS 后应用端截断；Stream 和二进制预览明确只读。后端在读取前检查值字段权限、在输出时显式投影，并在写入前检查字段可写性；schema 移除被字段策略禁止的新增/编辑按钮。派生字段补齐权限归属类型，修复任意字段策略导致无关字段被误隐藏的问题。

最终后端 22 模块/165 类/1282 项零失败、错误、跳过，含 Redis HTTP 13 项、管理服务 23 项；镜像 `simplepoint-query-verify:redis-preview-regression`，日志 `/tmp/redis-preview-regression.log`，22 源文件与产物源码同哈希。API 生产及改动实现/控制器定向 Checkstyle 通过，日志 `/tmp/redis-preview4.log`。完整前端门禁及新增二进制提示浏览器回归通过，日志 `/tmp/redis-preview-ui.log`、`/tmp/redis-preview-browser.log`；后续开发 MSW 大小限制同步通过类型检查。测试 Redis/卷已清理，业务数据未改动。

协议和剩余步骤见 [Redis 标准表格查询协议](redis-table-query.md)。**创建/更新并发安全、写入返回契约和真实集群验收仍未完成；预览返回预算不是 Redis 内部执行/分配的硬上限。** 已验证部分不代表整个 Redis 功能已验收；全平台任务、整批部署及三组索引迁移继续保持未完成。

| 使用点 | 平台组件 | 查询能力验收 |
| --- | --- | --- |
| `modules/simplepoint-common/src/views/system/AccessCenter/RoleDirectory.tsx` | DataTable | 实际 schema/SQL、派生计数表头与高级条件、跨页/稳定排序/视图/错误恢复及草稿保护通过；资源子树待继续验收 |
| `modules/simplepoint-common/src/views/system/AccessCenter/UserImpact.tsx` | DataTable | 实际 schema/H2/PostgreSQL；跨页/排序/条件交集/视图/失败恢复及角色隔离浏览器回归通过；详情不再返回全量数组 |
| `libs/components/AssignmentTable/index.tsx` | DataTable (`../DataTable`) | 两侧服务端查询；套餐应用、租户用户、成员角色分配及组织草稿浏览器回归通过，租户套餐独立页面联调待验收 |
| `libs/components/SForm/widgets/OrgTreeMultiSelect.tsx` | AssignmentTable (`../../AssignmentTable`) | 实际 schema/SQL、双侧筛选/排序/视图、草稿取消/提交、超限保留及只读浏览器回归通过 |
| `libs/components/SimpleTable/index.tsx:129` | TreeTable (`../TreeTable`) | 待验收 |
| `libs/components/SimpleTable/index.tsx:130` | Table (`../Table`) | 待验收 |
| `libs/components/SimpleTable/index.tsx:199` | Table (`../Table`) | 待验收 |
| `libs/components/TreeTable/index.tsx:47` | Table (`../Table`) | 懒分页/取消/重试单测和组织树浏览器回归通过 |
| `modules/simplepoint-ai/src/views/workbench/ApiKeys/index.tsx:170` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/Billing/index.tsx:273` | DataTable (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/Billing/index.tsx:311` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/Catalog/index.tsx:357` | DataTable (`@simplepoint/components/DataTable`) | 待验收 |
| `modules/simplepoint-ai/src/views/workbench/KnowledgeBases/index.tsx:440` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/KnowledgeBases/index.tsx:477` | DataTable (`@simplepoint/components/DataTable`) | 待验收 |
| `modules/simplepoint-ai/src/views/workbench/KnowledgeBases/index.tsx:556` | DataTable (`@simplepoint/components/DataTable`) | 待验收 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/Capabilities.tsx:599` | DataTable (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/Capabilities.tsx:616` | DataTable (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/Capabilities.tsx:633` | DataTable (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/Capabilities.tsx:659` | DataTable (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/Capabilities.tsx:676` | DataTable (`@simplepoint/components/DataTable`) | 待验收 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/Capabilities.tsx:895` | DataTable (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/McpDeploymentStatusDialog.tsx:429` | DataTable (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/McpDeploymentStatusDialog.tsx:497` | DataTable (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/Publications.tsx:246` | DataTable (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/Publications.tsx:314` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/Runtime/RuntimeNodes.tsx` | DataTable (`@simplepoint/components/DataTable`) | 真实 schema/H2/PostgreSQL；第 500 条之后分页、表头/高级条件/排序/视图及错误恢复浏览器通过 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/Runtime/RuntimePools.tsx` | DataTable (`@simplepoint/components/DataTable`) | 真实 schema/SQL、跨旧 500 条边界的分页/查询/视图、远程服务/密钥选择及部分字段编辑保护浏览器通过 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/Runtime/RuntimeSecrets.tsx` | DataTable (`@simplepoint/components/DataTable`) | 真实 schema/SQL、元数据分页/查询/排序/视图；密钥内容不开放读取或查询；浏览器错误恢复通过 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/Runtime/RuntimeWorkloads.tsx` | DataTable (`@simplepoint/components/DataTable`) | 真实 schema/SQL、跨旧 500 条边界的查询/视图、远程服务/密钥多选及取消不写入浏览器通过 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/index.tsx:228` | DataTable (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/McpServers/index.tsx:528` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/Models/index.tsx:173` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/Providers/index.tsx:150` | DataTable (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-ai/src/views/workbench/Providers/index.tsx:324` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-audit/src/views/monitoring/EndpointRateLimitRule/index.tsx:17` | SimpleTable (`@simplepoint/components/SimpleTable`) | 后端 10 项集成回归通过；真实 schema/UI 联调待验收 |
| `modules/simplepoint-audit/src/views/monitoring/ErrorLog/index.tsx:25` | SimpleTable (`@simplepoint/components/SimpleTable`) | 后端 10 项集成回归通过；真实 schema/UI 联调待验收 |
| `modules/simplepoint-audit/src/views/monitoring/LoginLog/index.tsx:29` | SimpleTable (`@simplepoint/components/SimpleTable`) | 后端 10 项集成回归通过；真实 schema/UI 联调待验收 |
| `modules/simplepoint-audit/src/views/monitoring/Redis/index.tsx` | DataTable | 实际 schema、完整观察集筛选/排序/分页/视图、扫描状态恢复、受限预览和后端字段权限通过回归，旧同步列表已删除；写入并发/返回契约和真实集群仍待验收 |
| `modules/simplepoint-audit/src/views/monitoring/ResourceGrantLog/index.tsx:31` | SimpleTable (`@simplepoint/components/SimpleTable`) | 后端 10 项集成回归通过；真实 schema/UI 联调待验收 |
| `modules/simplepoint-audit/src/views/monitoring/ServiceRateLimitRule/index.tsx:17` | SimpleTable (`@simplepoint/components/SimpleTable`) | 后端 10 项集成回归通过；真实 schema/UI 联调待验收 |
| `modules/simplepoint-common/src/views/i18n/Countries/index.tsx:16` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库回归通过；浏览器联调待验收 |
| `modules/simplepoint-common/src/views/i18n/Language/index.tsx:16` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库回归通过；浏览器联调待验收 |
| `modules/simplepoint-common/src/views/i18n/Message/index.tsx:16` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库回归通过；浏览器联调待验收 |
| `modules/simplepoint-common/src/views/i18n/Namespace/index.tsx:16` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库回归通过；浏览器联调待验收 |
| `modules/simplepoint-common/src/views/i18n/Region/index.tsx:16` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库回归通过；浏览器联调待验收 |
| `modules/simplepoint-common/src/views/i18n/Timezone/index.tsx:16` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库回归通过；浏览器联调待验收 |
| `modules/simplepoint-common/src/views/ops/Microapp/index.tsx:16` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/SQL 及主列表分页、筛选/排序/视图浏览器回归通过；子功能另行验收 |
| `modules/simplepoint-common/src/views/personal/components/PersonalTable.tsx:58` | SimpleTable (`@simplepoint/components/SimpleTable`) | 待验收 |
| `modules/simplepoint-common/src/views/platform/Account/index.tsx` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/H2/PostgreSQL；跨页查询、排序/视图、分页上限、脱敏动作保护及错误恢复浏览器回归通过 |
| `modules/simplepoint-common/src/views/platform/AccountAudit/index.tsx:7` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/H2/PostgreSQL；跨页查询、排序/视图与分页上限浏览器回归通过 |
| `modules/simplepoint-common/src/views/platform/Application/index.tsx:63` | SimpleTable (`@simplepoint/components/SimpleTable`) | 主列表 schema/数据库回归通过；树/分配子列表及浏览器联调待验收 |
| `modules/simplepoint-common/src/views/platform/Dictionary/config/item/index.tsx:26` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/SQL 及主列表分页、筛选/排序/视图浏览器回归通过；子功能另行验收 |
| `modules/simplepoint-common/src/views/platform/Dictionary/index.tsx:63` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/SQL 及主列表分页、筛选/排序/视图浏览器回归通过；子功能另行验收 |
| `modules/simplepoint-common/src/views/platform/ObjectStorage/index.tsx:687` | Table (`@simplepoint/components/DataTable`) | OSS 主列表实际 schema、H2/PostgreSQL、跨页筛选/排序/视图浏览器回归通过；自定义表单交互待专项验收 |
| `modules/simplepoint-common/src/views/platform/ObjectStorage/index.tsx:711` | Table (`@simplepoint/components/DataTable`) | 文件主列表服务端查询及强制租户范围回归通过；实际 schema 浏览器跨页筛选/视图通过 |
| `modules/simplepoint-common/src/views/platform/ObjectStorage/index.tsx:734` | Table (`@simplepoint/components/DataTable`) | 配额及派生值 SQL 查询/count、字段权限与实际 schema 浏览器回归通过；租户选择器分页及编辑权限待补齐 |
| `modules/simplepoint-common/src/views/platform/Organization/index.tsx:83` | SimpleTable (`@simplepoint/components/SimpleTable`) | 主列表及树 schema/数据库、全层级筛选/视图和子节点懒分页浏览器回归通过 |
| `modules/simplepoint-common/src/views/platform/Package/config/application/index.tsx:22` | AssignmentTable (`@simplepoint/components/AssignmentTable`) | schema/数据库及分页、筛选/视图、移动失败重试浏览器回归通过 |
| `modules/simplepoint-common/src/views/platform/Package/index.tsx:66` | SimpleTable (`@simplepoint/components/SimpleTable`) | 主列表 schema/数据库回归通过；树/分配子列表及浏览器联调待验收 |
| `modules/simplepoint-common/src/views/platform/Tenant/config/package/index.tsx:22` | AssignmentTable (`@simplepoint/components/AssignmentTable`) | schema/数据库回归通过；复用已验证组件，独立页面浏览器联调待验收 |
| `modules/simplepoint-common/src/views/platform/Tenant/config/user/index.tsx` | AssignmentTable (`@simplepoint/components/AssignmentTable`) | 实际 schema/SQL、双侧分页/查询/视图、所有者保护、失败重试及父租户隔离浏览器回归通过 |
| `modules/simplepoint-common/src/views/platform/Tenant/index.tsx:135` | SimpleTable (`@simplepoint/components/SimpleTable`) | 主列表 schema/数据库回归通过；树/分配子列表及浏览器联调待验收 |
| `modules/simplepoint-common/src/views/system/DataScope/EffectiveScopePreview.tsx:45` | Table (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-common/src/views/system/DataScope/index.tsx:78` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/SQL 及主列表分页、筛选/排序/视图浏览器回归通过；子功能另行验收 |
| `modules/simplepoint-common/src/views/system/FieldScope/index.tsx:58` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/SQL 及主列表分页、筛选/排序/视图浏览器回归通过；子功能另行验收 |
| `modules/simplepoint-common/src/views/system/IdentityProvider/index.tsx:229` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/SQL 及主列表分页、筛选/排序/视图浏览器回归通过；子功能另行验收 |
| `modules/simplepoint-common/src/views/system/Notification/index.tsx:208` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema、H2/PostgreSQL、跨页表头/高级条件/视图及状态枚举浏览器回归通过 |
| `modules/simplepoint-common/src/views/system/OAuthClient/index.tsx:432` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema、H2/PostgreSQL、跨页表头/高级条件/视图、页内分页及仅写密码表单回归通过 |
| `modules/simplepoint-common/src/views/system/Resource/index.tsx:206` | SimpleTable (`@simplepoint/components/SimpleTable`) | 待验收 |
| `modules/simplepoint-common/src/views/system/Role/index.tsx:72` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/SQL 及主列表分页、筛选/排序/视图浏览器回归通过；子功能另行验收 |
| `modules/simplepoint-common/src/views/system/User/index.tsx` | DataTable (`@simplepoint/components/DataTable`) | 成员 SQL/schema、跨页筛选/排序、视图恢复、错误恢复及页内滚动通过；角色配置子弹窗已单独回归 |
| `modules/simplepoint-common/src/views/system/User/config/role/index.tsx` | AssignmentTable (`@simplepoint/components/AssignmentTable`) | 实际 schema/SQL、双侧分页/筛选/视图、失败重试及成员隔离浏览器回归通过 |
| `modules/simplepoint-dna/src/views/platform/Dashboard/index.tsx:278` | Table (`@simplepoint/components/DataTable`) | 待验收 |
| `modules/simplepoint-dna/src/views/platform/DataAsset/index.tsx:98` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-dna/src/views/platform/DataCatalog/index.tsx:7` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-dna/src/views/platform/DataQuality/index.tsx:145` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-dna/src/views/platform/FederationJdbcUser/config/dataSource/index.tsx:335` | Table (`@simplepoint/components/DataTable`) | 待验收 |
| `modules/simplepoint-dna/src/views/platform/FederationJdbcUser/config/dataSource/index.tsx:364` | Table (`@simplepoint/components/DataTable`) | 待验收 |
| `modules/simplepoint-dna/src/views/platform/FederationJdbcUser/index.tsx:120` | Table (`@simplepoint/components/DataTable`) | 待验收 |
| `modules/simplepoint-dna/src/views/platform/FederationView/index.tsx:91` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-dna/src/views/platform/JdbcDataSource/index.tsx:197` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-dna/src/views/platform/JdbcDialect/index.tsx:391` | Table (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-dna/src/views/platform/JdbcDialect/index.tsx:424` | Table (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-dna/src/views/platform/JdbcDriver/index.tsx:257` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-dna/src/views/platform/JdbcMetadata/index.tsx:967` | Table (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-dna/src/views/platform/JdbcMetadata/index.tsx:983` | Table (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-dna/src/views/platform/JdbcMetadata/index.tsx:1005` | Table (`@simplepoint/components/DataTable`) | 待验收 |
| `modules/simplepoint-dna/src/views/platform/QueryAudit/index.tsx:39` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-dna/src/views/platform/QueryPolicy/index.tsx:105` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-dna/src/views/platform/QueryTemplate/index.tsx:106` | SimpleTable (`@simplepoint/components/SimpleTable`) | 实际 schema/数据库隔离回归通过；浏览器联调待验收 |
| `modules/simplepoint-dna/src/views/platform/SqlConsole/index.tsx:631` | Table (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
| `modules/simplepoint-host/src/App.tsx:299` | AntTable (`@simplepoint/components/DataTable`) | 已声明本地查询；共用引擎回归通过，逐入口联调待验收 |
