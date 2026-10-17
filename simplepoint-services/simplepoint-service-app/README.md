# 应用工作台服务

`simplepoint-service-app` 是与 AI、DNA 平级的独立服务，使用 `app` 服务名和 `/app` 网关前缀。
前端 `simplepoint-app` 通过 `/app/mf/mf-manifest.json` 提供页面。“应用平台”下的工作台现提供租户应用列表、创建应用和添加附属页面；应用模板和应用市场仍为空页面。

资源清单位于 `src/main/resources/META-INF/simplepoint/resources/app.json`，三个页面权限点分别是
`app.workbench.view`、`app.templates.view`、`app.marketplace.view`。内置组织标准套餐包含
`simplepoint-app` 应用，新页面资源在注册后关联到该应用并授予示例组织所有者与管理员。

工作台使用 `simpoint_app_definitions` 和 `simpoint_app_pages` 存储租户隔离的应用与页面元数据。创建应用时同步创建唯一主页面；附属页面可以继续添加。新页面为草稿；发布表单时准备租户运行资源、绑定 DNA 模型，再激活 APP 页面绑定。未发布页面不能借工作台权限直接打开，运行数据仍受独立的页面动作与行/字段权限控制。

表单控制面已新增 `simpoint_app_forms`、`simpoint_app_form_drafts`、
`simpoint_app_form_versions` 和 `simpoint_app_form_publications`。当前支持表单创建、草稿修订、
完整服务端文档校验和不可变版本冻结；表单版本同时固化规范化 DNA 存储投影及其 checksum。
基础发布编排、运行 CRUD 和拖拽设计器已接入；会话内增改删可靠重试已接入。完整数据/规则设计、跨刷新写入恢复、资源退役治理和 Workflow 对接仍在实施中。Workflow 的定义、实例和任务继续由独立领域接口接入；
两者通过稳定的表单版本和提交记录标识协作，不共用领域表。

表单提交数据规划采用“每个表单一张物理表”（用户于 2026-09-29 确认），以支持直接 SQL 查询。
表单发布根据版本化定义生成受控存储投影，并调用 DNA 的结构化 Schema API。DNA Schema Planner
负责差异、风险与方言 DDL，再由目标数据源 JDBC 原生执行并刷新元数据；查询和标准 Record DML
继续经过 Calcite。APP 不提交任意 DDL 或物理表名。发布与运行时已有基础实现，真实数据库到页面全链路验收仍未完成；此存储决策不代表
现有应用页面各自对应一张表。

应用工作台、表单设计器、DNA 数据闭环和 Workflow 对接的权威实施计划见
[`doc/design/application_form_engine_plan.md`](../../doc/design/application_form_engine_plan.md)。计划明确区分应用页面、
表单与表单场景，并以版本化发布状态机完成从拖拽设计到物理表、页面资源和流程运行的闭环。
2026-10-13 的架构复审与阶段 2 前置门禁见
[`doc/reviews/application-form-engine-design-review-20261013.md`](../../doc/reviews/application-form-engine-design-review-20261013.md)。

## 发布记录与升级

`simpoint_app_form_page_bindings` 在任何 DNA 远程准备前保存发布意图，起始状态为 `DATA_PREPARING`。
工作台的“发布记录”使用平台表格，提供服务端分页、筛选与权限过滤后的“继续发布”和“取消本次发布”。刷新或重新进入后，
按发布 ID 恢复原目标页面、版本及幂等键；恢复不冻结新草稿。查看数据需要目标页面的运行数据权限。

已有环境按顺序执行 APP 控制库迁移：

1. [`20261013-app-form-domain.sql`](../../doc/deployment/sql/20261013-app-form-domain.sql)。
2. [`20261013-app-form-release-intent.sql`](../../doc/deployment/sql/20261013-app-form-release-intent.sql)。
3. [`20261013-app-form-resource-generations.sql`](../../doc/deployment/sql/20261013-app-form-resource-generations.sql)。
4. [`20261014-app-form-policy-publications.sql`](../../doc/deployment/sql/20261014-app-form-policy-publications.sql)。
5. [`20261014-app-form-policy-cancellation.sql`](../../doc/deployment/sql/20261014-app-form-policy-cancellation.sql)。
6. [`20261014-app-form-policy-protocol.sql`](../../doc/deployment/sql/20261014-app-form-policy-protocol.sql)。
7. DNA 控制库执行 [`20261013-dna-resource-projections.sql`](../../doc/deployment/sql/20261013-dna-resource-projections.sql)，其前置为 DNA Runtime 基础、Record 和新增回执迁移。
8. 先部署支持目标取消协议的 DNA，再部署 APP 服务与前端，保持 Hibernate `ddl-auto=validate`。迁移期间停止 APP 发布写入；所有 APP 发布节点必须识别新状态与请求键映射后再恢复。

第二份迁移可重复执行，保留已有记录。回滚应用时不要收紧状态约束或删除准备中的发布意图。
独立 PostgreSQL 17 已验证迁移及映射；这不代表当前集成环境已经执行升级。

取消接口为 `POST /applications/{applicationId}/forms/{formId}/releases/{releaseId}/cancel`，使用与发布相同的服务端权限，
只按持久化发布 ID 操作；请求体不能替换版本、页面或请求键。列表返回服务端 `canCancel` 能力，前端不能自行放宽。
尚未开始资源执行的尝试可以直接取消；已有策略意图则先持久化取消决定，再取得 DNA 的目标确认。
原发布若已经在目标库提交，继续完成原发布并返回实际结果。取消响应未知时不能释放保护；“继续发布”会恢复已保存的取消决定。
已进入资源执行且没有策略意图的旧发布不支持取消，必须继续原发布；租约到期不能证明远端操作没有提交。
取消保留已准备的物理结构及历史请求键，不回滚 DDL，也不停止可能被其他页面复用的结构准备。
当前取消入口位于发布记录中。新发布强制执行历史页面影响分析和目标事务检查；阻断结果可在发布窗口及发布记录中读取，修复后重试原发布。
新旧发布通过 `policy_protocol_version` 区分；旧资源执行尝试必须先完成，才能在同表单上准备新策略，详见实施计划第 40 节。

页面绑定意图持有按租户/页面递增的 `generation`；DNA 在单个控制库事务内切换该页面的模型关联并退役旧关联，
保留其他页面对旧模型的访问。迟到的低版本请求会被拒绝，普通绑定/解绑不能修改已被 APP 管理的关联。
授权重放活动发布（原发布命令或 `POST /applications/{applicationId}/forms/{formId}/releases/{releaseId}/resume`）
会按已保存的身份和版本修复资源关联，不重新冻结草稿、不执行 Schema 发布；已退役发布保持终态。
部署后需逐一重放当前活动页面的发布，完成旧关联接管。不能以该操作代替下文的目标库回执表准备。

跨服务切换不具有全局事务：DNA 已切换但 APP 尚未激活时，旧页面可能暂时被拒绝访问，失败后继续原发布。
本版保留手动“继续发布”；自动恢复调度、页面归档/停用生命周期仍待后续实现。回滚时保留版本和所有权记录，
不能重新启用旧版 APP 发布写入者，也不能通过清空 DNA 记录恢复无版本绑定。

本地构建：

```bash
./gradlew :simplepoint-services:simplepoint-service-app:installDist
cd simplepoint-react && pnpm build:app
```


## Record 写入确认协议

新增/修改返回原操作元数据及可空的 `currentRecord`，两者分别代表已完成操作与当前可读数据。
修改请求和删除查询参数必须带 `idempotencyKey`；响应不明时重发原请求，不能生成新键。
DNA 在目标库同一事务保存修改、删除和原子命令组回执，APP 在业务校验前核对，并处理并发核对窗口。
平台 `SimpleTable` 继续负责数据编辑和删除交互。

**本次升级还要求先部署 DNA 并准备存量目标 namespace 的回执基础表，再协调部署 APP 服务与前端。**
已有活动发布需通过授权 DNA 发布接口重放并验证成功；普通 Record 调用不会自动建表。
详细契约、升级步骤和测试边界见实施计划第 20 节。当前未部署；浏览器刷新后的写入意图恢复仍待完成。

## 表单字段写入规则

设计器通过平台 SForm 配置“始终可编辑”“仅新增时填写”“提交后不可修改”；服务端根据当前记录状态强制校验，界面规则不能解除数据权限或业务锁定。
更新请求必须省略已锁定字段，显式原值和 null 也属于写入；运行表单和平台数据表格已经自动处理。仅新增且必填的字段需在首次保存草稿前填好或配置有效默认值。

该策略随表单版本发布，不触发 DNA 表结构迁移，不自动补写存量数据。最高已激活版本形成表单级写入限制，与页面自身版本的策略取交集；旧页面原有的更严格限制保留，草稿或失败发布不生效。界面仍使用绑定版本的布局和字段，不自动替换为最新设计。完整边界见实施计划第 22、26 节。

## 表单数据设计

设计器“数据设计”支持独立新增字段、字段类型与精确默认值、提交必填/存储非空、静态选项及普通/唯一组合索引。
编辑后点击“应用修改”进入草稿，支持撤销/重做；发布才改变运行版本及物理结构。长整数、小数和 JSON 默认值保留原有精度。
多个场景共享字段时，类型必须兼容全部控件；已有规则引用的字段暂不开放改类型。索引字段按排列顺序生效，支持平台数组编辑器的增删和排序。

普通设计操作保留已有字段和索引的稳定身份，不开放直接删除物理列。影响存量数据的结构变更继续由 DNA 评估，当前仅自动执行 SAFE 计划。
本次没有新增数据库迁移，服务端增强了默认值长度、精度及日期/JSON 校验；应更新 APP 服务后再部署前端。详细范围见实施计划第 23 节。

## 表单交互规则

设计器“交互规则”支持选择目标控件、配置显隐/启用/必填效果，并通过平台条件树组合“满足全部、满足任一、不满足”。条件可以引用其他场景的字段。规则按顺序执行，同类效果以后面的规则为准；界面明确说明条件不成立时的相反效果。

点击“应用修改”后规则进入草稿并支持撤销，发布后才影响运行页面。必填条件由服务端在正式提交时复核；启用规则不会解除权限和字段写入限制。空值包含空白文字，JSON 按原文比较；时间值支持 ISO 时区和纳秒精度。

普通长整数和精确小数字段的默认值、编辑值及记录响应统一使用精确字符串，运行端与数据页复用平台 Decimal 控件和 x-decimal 校验。升级先更新全部 APP 服务节点，再部署前端。本项不新增数据库迁移。

条件编辑和运行已通过模型测试及浏览器 HTTP fixture；受保护字段的规则一致性、跨版本统一业务约束、计算/联动、Workflow 和真实数据库全链路仍在后续计划中。见实施计划第 24 节。

## 只读与脱敏字段

运行表单和数据编辑页使用平台 SForm 的 `x-display-only` 能力展示不可编辑值。脱敏数字、日期、布尔和 JSON 不再交给输入控件解析，历史选项不存在时保留原值；编辑其他字段不会因这些展示值的类型/格式校验失败而被阻止。展示值不会加入修改请求，服务端权限与业务规则仍独立执行。

运行协议会移除脱敏字段的默认值、选项和依赖其原值的客户端规则。对有权读取原值的只读字段仍保留条件规则。此项不代表受保护字段的条件必填交互已全部收口，完整边界和验证见实施计划第 25 节。升级先更新 APP 服务，再更新前端，不需要数据库迁移。

## 跨页面策略与重试

发布字段写入限制后，使用旧表单版本的页面也须遵守；重新绑定旧版本不会降低表单级策略。运行页检查版本时会同时检查有效写入限制，确认加载前保留用户草稿。其他页面仅修改布局时不要求当前用户重新加载。

新增和修改均先核对 DNA 原操作回执，再执行当前业务校验。新增回执查询不占用新幂等键、不插入记录，原操作成功后仍可确认，当前记录不可见时只返回原操作元数据。核对验证原操作者及请求指纹，不允许跨操作者领用同一回执。

本项无数据库迁移，须先更新 DNA 提供新增回执查询，再更新全部 APP 节点，最后更新前端。策略收紧不会回填已有空值；将必填字段改为仅新增可能影响存量草稿，存量影响检查与修复治理仍须在上线前补齐。详见实施计划第 26 节；当前尚未完成真实环境部署验收。


## 表单级策略发布协调

APP 增加 `simpoint_app_form_policy_publications` 和表单 `write_policy_version`，保存目标库发布的原始命令及确认状态。
未完成的意图必须继续原发布，过期租约或网络错误不会释放其表单/页面占用。页面激活与 APP 写入版本更新在同一控制库事务提交，
运行 Record 写入由服务端传递该版本；已有基线默认 0，数据库重放升级不能重置非零版本。

本阶段已实现状态服务、远程协调器和页面激活/写入适配，尚未在一键发布中自动生成影响检查并调用协调器。
仍须完成历史/条件规则影响分析及平台数据处理反馈，不能手动传空检查启用非零版本。此阶段没有新增用户配置。
已有环境须先执行上面的第四份迁移再部署新 APP；新安装通过启动 schema 初始化，已用真实 PostgreSQL 验证。
详细范围见实施计划第 31 节，尚未部署到当前集成环境。


## 默认物理存储配置

DNA 服务需要由管理员配置 `simplepoint.dna.runtime.placement.targets.default` 的 `data-source-id`、
`catalog-name`、`schema-name` 和 `isolation-mode`。数据源必须已在 DNA 注册并可连接，目标 namespace 由部署预先准备。
APP 浏览器不能传入物理库表或覆盖此映射。已有控制库备份与正确迁移并不能代替这项目标库配置。

发布检查只返回问题字段、问题类型和精确计数字符串，不返回业务行和值。修复入口需要独立运行页编辑权限；
修复仍经过原页面的行、字段与状态约束。其他活动页面要求继续参与新增和修改校验，删除保留原软删除及幂等契约。
宽表读取按 DNA 单次最多 100 列的契约分批，并验证各批记录身份和版本；读取期间发生变更时要求刷新，不返回拼接的不同版本。


## 跨服务发布装配

只有 Common 开启 `simplepoint.rbac.managed-resource.enabled=true` 并导出 `saas.managed-resource:1`。
Common 的 `simplepoint.rbac.managed-resource.caller-owners[simplepoint-service-app]=app` 必须与实际 OAuth2 服务客户端一致；
未配置时拒绝管理请求。APP 显式将该契约路由至 Common，避免认证服务因依赖租户实现模块而误成为资源管理提供者。
APP 服务路由读取超时为 65 秒，覆盖 DNA 的 30 秒目标事务预算和远程准备开销；未知结果仍通过原发布意图恢复。

发布权限与数据访问权限独立。租户普通管理员/设计者需要通过平台角色资源配置取得新运行页与动作授权；
发布操作不扩大其数据权限。验收账号未获运行页授权时应返回 403，显式授权后才允许读写。

### 跨服务业务错误

DNA Runtime 契约声明自己的错误解码器，将 namespaced 错误码恢复为 `DnaRuntimeException`。
服务路由只实例化本地契约显式声明的解码器，远端不能指定异常类。已识别的业务拒绝不重试、不进入 fallback；
未识别错误仍使用通用路由失败。APP 因此在跨进程部署时也能返回记录不存在的 404、权限拒绝的 403、
版本/幂等冲突的 409 和输入错误的 400。升级此契约时应同步部署共享路由及 DNA/APP 服务，并回归其他调用方。

运行表格 Schema 的业务字段明确接受 SQL NULL，选项字段也保留可清空值，避免未填写的 JSON、日期等字段阻断编辑。
场景运行时和新增的必填约束会移除 nullable 分支；服务端始终验证提交必填和其他活动页面要求。
读取到的 NULL 不转换为缺失属性，不借此重新应用历史默认值。

## 表单与流程提交集成

当前实现边界及验收状态以 `doc/design/application_form_engine_plan.md` 第 43 节为准。
APP 拥有提交意图和冻结的业务快照，DNA 完成主子表原子写入，Workflow 拥有审批事实。
提交记录的技术状态和审批状态分别展示；修改业务表不会覆盖已提交审批证据。

升级已有环境时须备份控制库和目标业务库，并执行：

1. `doc/deployment/sql/20261014-workflow-operation-receipts.sql`。
2. `doc/deployment/sql/20261014-workflow-business-links.sql`（包含已发布绑定目录）。
3. `doc/deployment/sql/20261014-app-form-submissions.sql`。

先更新授权服务，使服务令牌包含签名的 `client_id` 和 `grant_type`，再更新调用方并取得新机器令牌。
APP 的 `simplepoint.app.form.workflow-remote.allowed-callers` 和 Workflow 的
`simplepoint.workflow.business-remote.allowed-callers` 必须分别匹配实际 Workflow、APP OAuth 客户端。
服务入口同时要求 `client_credentials` 和 `service-router.invoke`，普通用户令牌不能调用内部能力。
标准服务装配使用 `simplepoint-service-workflow`、`simplepoint-service-app`，可通过对应服务配置中的环境变量覆盖。

已有物理命名空间须由授权管理员通过 `POST /dna/platform/dna/runtime/models/{modelId}/prepare-storage?expectedVersion={lockVersion}` 准备 `simpoint_dna_record_snapshots_v1`；
先 GET 同一模型详情读取 `model.lockVersion`，服务端只准备 ACTIVE 版本并校验乐观锁。
活动版本的发布重放会检查并补齐基础设施，不改写已有业务表或重置写入屏障。
不要在浏览器提交路径临时创建表。新命名空间随正常 Schema 发布准备。

恢复调度默认开启，每 5 秒领取最多 20 个到期意图，单次租约 90 秒；已发起流程约每 20 秒核对状态。
连续失败最多 10 次后显示需要处理，由原提交人在提交记录中恢复同一意图。
重试不会替换表单值、版本或流程身份，也不会撤销已提交到 DNA 或 Workflow 的事实。
终态后修改重提创建新提交和关联流程，原审批历史保持不变。

使用流程、故障处理和容量边界见 [运维指南](../../doc/validation/form-workflow-operations.md)，正式验收结果以计划第 43 节和关联证据为准。
