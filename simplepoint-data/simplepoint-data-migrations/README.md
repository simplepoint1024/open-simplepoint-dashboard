# 模块数据库迁移

本库为编译期模块组合提供独立的迁移计划与执行器，使用 Spring Boot BOM 管理的 Flyway（当前解析为 11.14.1）。它不依赖 JPA、Spring 容器或物理服务名称，不接受业务请求上传的 SQL。

声明和发现已拆入中立 `simplepoint-data-migrations-api`；本库导出该 API 并提供执行器。
业务模块中的 SQL 贡献适配器只依赖 API，安装工具才显式依赖本库，避免将 Flyway
传递到业务宿主后触发 Spring Boot 默认迁移。解析后的运行期依赖门禁覆盖元数据 API、
共享适配器、事件组合包、工作流实现和原工作流宿主。

## 模块声明

在拥有表结构的模块入口添加 `@ModuleMigrations`，资源约定为：

```text
META-INF/simplepoint/modules/<module-id>/db/
  manifest.properties
  postgresql/V1__initial.sql
  postgresql/V2__add_reference.sql
```

```properties
tables=business_records,business_details
dialects=postgresql
migrations=V1__initial.sql,V2__add_reference.sql
```

纯模块明确不拥有 SQL 表时使用 `@ModuleMigrations(ownsSchema = false)`，无需伪造迁移文件或空表。共享事件表等基础设施仍须由其独立贡献负责。

`tables` 声明本模块拥有的表，包含后续版本新增的表。`dialects` 是经过验证的方言；当前执行器支持 PostgreSQL 与 H2，现有领域控制库的真实迁移只声明 PostgreSQL。每种声明方言都必须提供相同文件名的完整 SQL 资源。版本从 1 开始；已发布的文件不得修改，变更必须增加版本。

资源通过模块 ID 精确读取，拒绝重复类路径资源、重复表归属、无效名称和超限内容。文件由清单明确列出，Flyway 不扫描其他模块目录、Java 迁移或通用 `db/migration` 目录；不使用占位符替换 SQL。

## 使用

```java
var plan = ModuleMigrationPlan.discover(selectedModules, storageBindings);
var engine = new ModuleMigrationEngine();
engine.migrate(plan, Map.of("default", migrationDataSource));
engine.verify(plan, Map.of("default", migrationDataSource));
```

数据源按存储组提供，迁移账号与连接信息由安装环境管理。迁移不自动建库、建 schema、创建账号或授予权限。

- 执行顺序沿用模块的本地依赖顺序；需要同库的跨模块关系仍须由 `@ModulePersistence.sameStoreAs` 声明。
- 每个模块拥有独立历史表，其名称由稳定模块 ID 的 SHA-256 前 32 个十六进制字符派生，形如 `sp_migration_<hash>`。宿主或存储组改名不会改变历史身份；迁移历史需要随所属业务数据一起保留。
- 已选且绑定存储、但没有迁移声明的模块进入 `undeclaredModules`；执行器以 `INCOMPLETE_MIGRATION_PLAN` 拒绝不完整安装。当前二十个逻辑模块均已声明：十九个拥有 SQL、Redis 管理不拥有 SQL。声明齐全不等于正式宿主已完成安装和启动验收。
- 执行前检查全部数据源、方言、旧表状态及已执行版本的校验和，并解析版本冲突。不同存储组不能映射到同一 DataSource 实例。
- Flyway 管理历史、锁和版本执行。校验拒绝脚本被修改、缺失已执行版本以及旧二进制面对更高 schema 版本；不会自动忽略未来版本、repair 或 clean。
- `verify` 检查迁移历史与脚本状态，不执行迁移脚本。它不是完整的物理 schema 差异比较；宿主仍须进行 ORM/业务结构校验。

## 共享基础设施贡献

共享表不能重复归属于每个使用它的业务模块。`ModuleMigrationContributor` 通过标准
ServiceLoader 注册，按已选模块和 `ModuleStorageBindings` 派生安装贡献；实现只能读取
元数据，不得连接数据库、读取凭据或启动后台任务。`loadSchema` 复用相同的精确资源路径、
清单校验和大小限制。共享贡献与业务迁移一起完成全量预检查，再执行 SQL。

能力注解可以通过中立的 `@ModuleSharedSchemas` 声明所需共享 schema。规划器检查每个
使用方的存储组是否都有对应贡献；缺少提供者时以 `MISSING_SHARED_MIGRATION_CONTRIBUTOR`
拒绝生成完整计划。共享提供者不得冒充已选业务模块、向未选存储写入或重复占用其他贡献的表。

`simplepoint-events-migrations` 是首个适配器：自动读取 `@ModuleEvents`，按存储去重贡献
`platform-events`。模块合并时共用一份事件 schema，分库时各自安装；两种情况下历史身份和
旧事件 owner 保持稳定。它不依赖 Spring、JPA、Rabbit 客户端或领域实现，事件运行时组合包
自动带入此适配器。旧事件表没有受管历史时仍必须显式接管，不会因引入适配器而自动 baseline。

## 新模块与旧库

没有迁移历史、且本模块声明的所有表均不存在时，可以安装新模块。为了允许多个独立模块共用一个非空 schema，执行器在上述检查后使用 Flyway 的 **0 版本基线**；所有声明的迁移从 1 开始，因此不会被该基线跳过。该操作由 Flyway 的并发锁保护。

只要发现任何本模块的已有表却没有历史，就以 `LEGACY_SCHEMA_ADOPTION_REQUIRED` 停止，且不先迁移其他模块。不会直接将现有库标记为最新版本。显式 PostgreSQL V1 接管现由 `ModuleSchemaAdoption` 执行，正式安装器提供 `schema-review` / `schema-adopt`；运行期仍不得自动接管旧库或用默认 baseline 跳过版本。[安装步骤与限制](../../simplepoint-platform/simplepoint-platform-installation-host/README.md)

接管要求独立执行 V1 得到的结构参照、V1 脚本 SHA-256、业务核对证据指纹及固定操作 ID。核对覆盖声明表的字段、默认值、约束、索引、触发器和行策略；PostgreSQL 备份恢复产生的 varchar 数组逐项转换仅做限定的等价归一化，不忽略其他表达式差异。安装器必须先停止旧执行者并核验业务不变量；结构一致不等于业务数据正确。

接管使用数据库会话锁、表锁和 `PREPARED` / `APPLIED` 回执。仅登记 V1，V2 及以后仍通过普通迁移执行。普通 `migrate` / `verify` 也核对已接管基线的原始脚本指纹，避免 Flyway 跳过基线校验导致旧脚本被静默替换。恢复只接受同一份审核输入；缺失完成历史、冲突操作或结构漂移均拒绝。

多个数据库或模块之间没有分布式原子提交。DDL 的事务能力由数据库决定：本轮在 PostgreSQL 验证了失败 DDL 回滚，以及外部故障修复后用同一脚本恢复；不把该结果扩大到 H2 或其他方言的所有 DDL。

## 首个接入：工作流

工作流模块拥有自己的 `db/manifest.properties` 与不可变 `V1__workflow_foundation.sql`，
涵盖十张平台工作流表；供应方内部表保持独立的迁移身份。

原生表现在由独立 `simplepoint-plugin-workflow-migrations` 贡献 `flowable-process`，
依据工作流的 `@FlowableProcessSchema` 自动加入所属存储。Flowable 8.0.0 的五份供应方
PostgreSQL 建库资产按原文组合成一个事务型 V1，共 45 张表；安装阶段无需启动引擎。
模块化运行期已绑定所属数据源/事务并保持原生 schema 更新关闭。
原生建表仅保留在旧入口兼容及供应方结果对比 fixture 中；
正式宿主的安装/就绪编排、受控版本升级和旧库接管仍待完成。
来源与升级规则见[原生表迁移说明](../../simplepoint-plugins/simplepoint-plugin-workflow/simplepoint-plugin-workflow-migrations/README.md)。

旧服务的 `schema.sql` 已迁入工作流模块 `db/legacy.sql`；旧入口通过精确 `spring.sql.init.schema-locations` 继续使用它。它是兼容旧部署的重复初始化脚本，版本化 V1 则是不可变的新库快照。当前没有将旧服务切换为自动接管 Flyway，也没有改动正在运行的业务数据库。

## APP 表单接入

`app-form` 的 V1 拥有十一张 APP 控制表，包括应用/页面目录、表单草稿和不可变版本、发布/绑定/策略协调、提交记录及资源下线记录。业务物理表与明细子表仍由 DNA 管理，不属于这份控制库迁移。共享事件表也不并入 APP 的表清单。

应用目录的三个实体（含只读汇总模型）已从可运行服务移入 `org.simplepoint.plugin.app.form.entity`，模块持久化扫描能够独立发现全部十二个实体，不需要依赖服务 JAR。HTTP 目录服务、访问适配器等剩余宿主代码仍需在后续步骤提取，不能仅据此宣称 APP 已可独立组合运行。

旧 APP 入口通过精确路径执行模块 `db/legacy.sql`。该脚本在原始脚本末尾补入了此前仅存在于部署增量中的 `detail_bindings_json` 列，保持旧数据为 NULL；新库 V1 直接声明该列。真实 PostgreSQL 校验曾发现原启动脚本漏列，本次已修复。其他旧库历史约束修复仍沿用既有部署迁移，不能把 `legacy.sql` 重复执行视为任意历史版本的完整升级或 Flyway 接管。

验证覆盖新旧初始化结果的列类型/长度/空值/默认值、约束和索引对比，全部实体 Hibernate validate 与实际查询、租户外键/发布幂等约束/取消恢复状态约束、重启保留数据，以及 APP 和工作流共库时的独立迁移历史。这是这两份建表结果的结构一致性验证，并非通用旧库接管器。

## DNA 接入

三个逻辑模块分别拥有迁移清单与 PostgreSQL V1，历史身份独立于 DNA 服务名称：

| 模块 | 控制表数 | 归属和来源 |
| --- | ---: | --- |
| `dna-core` | 3 | JDBC 数据源、驱动、方言源；由当前映射核对形成静态 SQL 基线 |
| `dna-federation` | 10 | 联邦目录、视图、策略、审计、连接用户等，以及保存的 SQL 模型；保留 JDBC 用户的 PostgreSQL 权限数组类型 |
| `dna-runtime` | 9 | 物理模型/版本/绑定、发布迁移/步骤、审计、记录幂等回执和资源代次投影；汇总既有控制库部署 SQL，保留外键、默认值、状态和进度约束 |

SQL 模型实体从可运行宿主移到联邦查询模块，实体名称和物理表名保持兼容。原 SQL 模型部署脚本中的国际化消息更新不进入 DNA 结构迁移，须由国际化拥有方处理。运行时维护的业务表、明细表及目标库内的操作日志/围栏仍由相应运行时协议维护，不作为控制库固定表安装。

`dna-core` 与 `dna-federation` 按既有声明共库；`dna-runtime` 可使用另一存储组。组合验证分别安装共库和分库，校验全部二十二个实体并实际查询，检查模块历史、数据保留和表没有落入其他存储组。

运行时 V1 与既有六份控制库升级脚本的新库结果进行结构对比。旧回执升级脚本的约束检测已限定到目标表，避免同一 PostgreSQL 数据库内其他 schema 的同名约束导致漏建；测试在两个 schema 中同时存在这些约束并重放历史脚本。

DNA 的真实维护控制库测试已使用模块迁移安装，再执行 Hibernate validate；目标业务数据继续分别使用 PostgreSQL/MySQL 验证。MySQL 是这些业务目标测试的方言，**不代表已提供 MySQL 控制库安装基线**。既有 DNA 服务入口仍沿用原启动方式，完整安装器接入与旧库接管尚未完成。

## 身份与基础登记模块接入

以下四个模块分别提供 PostgreSQL 静态基线，共四十三张表（四十一个实体和两张集合关联表），不依赖宿主启动时自动生成结构：

| 模块 | 表数 | 归属 |
| --- | ---: | --- |
| `identity` | 31 | 账号、角色、授权、数据/字段权限、平台账户、租户资源及组织成员 |
| `oidc` | 5 | 客户端、授权、同意记录和外部身份提供方/链接 |
| `i18n` | 6 | 国际化消息、命名空间、语言与地域元数据 |
| `bootstrap-registry` | 1 | 初始化贡献的持久登记 |

身份基线合并了实体映射与既有部署 SQL 中的约束，保留托管资源身份检查、目录主键顺序/部分索引、平台身份与二次确认的用户外键、成员唯一性及默认值、授权版本和加密 MFA 字段长度。只插入用于平台安全操作串行化的 `platform` 锁记录，不创建用户、管理员或角色授权。历史组织归属回填、旧数据清理和其他模块的消息变更不属于新库 V1。

真实 PostgreSQL 验证覆盖共用 schema，以及四组独立数据源/schema 的完整 JPA 映射与查询。无请求过滤器时，撤销授权仍被实际仓储查询排除；迁移重启保留下线代次、已应用初始化记录和消息；长令牌文本能够经 OIDC 实体查询完整读回。分组隔离测试使用同一个 PostgreSQL 实例，不将其当作跨服务器、完整登录或部署拓扑验收。

OAuth 核心适配器、MFA 与外部绑定已完成模块存储归属验证；其余模块迁移、真实初始化过程、正式宿主接入及既有库接管仍待完成。静态基线保留现有映射语义，不代表所有历史映射约束已经通过批量初始化审查；相关后续核对项记录在模块组合计划中。

## 审计、个人工作台、通知与存储接入

| 模块 | 表数 | 归属 |
| --- | ---: | --- |
| `audit-logging` | 3 | 错误、登录和资源授权变更日志 |
| `audit-rate-limit` | 2 | 服务及接口限流规则 |
| `personal` | 4 | 私人事项、事项历史、待办与消息 |
| `notification` | 2 | 系统通知与阅读回执 |
| `storage` | 3 | 对象元数据、存储提供商配置与租户配额 |
| `audit-redis` | 0 | 显式声明 `ownsSchema=false`，不安装空的迁移历史 |

五份静态 PostgreSQL V1 从当前 Hibernate PostgreSQL 映射草稿核对形成，保留枚举检查、
通知/回执及待办/消息的去重约束、限流服务标识唯一性、索引和字段类型。草稿生成器不进入
运行时代码；宿主仍按版本化 SQL 安装，随后做 Hibernate validate。

通知依赖个人工作台，但只声明自己的两张表；存储继续继承 identity 的同库存储约束，
配额只读汇总在安装身份租户表后可查询，不把 `StorageQuotaReadModel` 误建为物理表。
历史工作流消息搬迁是跨模块数据迁移，不混入通知或个人工作台的新库结构基线。

真实 PostgreSQL 用例联合安装五个模块及 identity：共库和五组独立 schema/数据源均
校验 45 张表、44 个实体，检查没有越界建表。覆盖配额实际汇总、长审计文本、消息/待办
去重、通知撤销和阅读状态重启保留；这些场景不等于真实对象存储网络、MQ 投递或 UI 验收。

## AI 模块

| 模块 | 表数 | 范围 |
| --- | ---: | --- |
| `ai-core` | 5 | 提供商、模型、API 密钥、调用与网关资源 |
| `ai-knowledge` | 4 | 知识库、文档、持久索引任务与向量片段 |
| `ai-runtime` | 8 | 节点、池、工作负载、租约、密钥与 MCP 配置版本 |
| `ai-mcp` | 7 | 服务、能力快照、调用、OAuth、连接、发布与任务 |
| `ai-catalog` | 2 | 目录条目与同步状态；聚合读模型不创建物理表 |

五份 PostgreSQL V1 保留当前映射及宿主后置 DDL 的列、唯一约束、检查约束和索引。
新库基线不执行旧授权资源重命名、跨存储文档租户回填或处理中任务重入队。
原宿主脚本继续保留；这些历史数据操作必须经过独立的旧库接管协议。

知识库基线使用 `public` 中的 pgvector 和 pg_trgm 扩展。安装数据库须具备对应扩展包，
并由有权限的安装连接创建扩展，或由 DBA 预先安装到 `public`。应用连接可以只使用模块
schema 的搜索路径，查询显式引用扩展类型、函数与运算符。扩展创建不等于操作系统包自动安装。

AI 存储分为 `ai-core + ai-knowledge` 和 `ai-runtime + ai-mcp + ai-catalog` 两个必须
共库的组；目录聚合查询直接使用 MCP 表，因此不能只声明本地依赖而允许其另配数据库。
两组可以分别使用独立数据源。真实数据库用例验证实体查询、系统/租户唯一性、软删除复用、
租约与任务约束、实际向量/关键词/混合检索、事务回滚及迁移重启保留数据。

当前 **19 个逻辑模块、126 张平台控制表**拥有 SQL 迁移，Redis 管理显式没有 SQL 表。
共享事件迁移额外贡献三张表，Flowable 原生迁移额外贡献 45 张表；全目录在一个新 schema
下共二十一份迁移、174 张表。动态业务物理表不计入此数量；完整宿主就绪还必须核对
正式安装编排与各领域运行期协议。
工作流跨身份表的读模型仍有未解除的共库依赖，由总体计划 G22 跟踪，不能据此宣称全量分库可用。

## 验证与剩余工作

- H2：共库独立历史、分库隔离、重启不重放、旧表拒绝接管、校验和与未来版本拒绝、完整预检查、数据源别名拒绝。
- 隔离 PostgreSQL 17：真实工作流模块迁移、模块 JPA 结构校验、Flowable 流程部署/启动/完成、并发安装仅执行一次、DDL 失败回滚/同脚本恢复、旧脚本重复初始化。
- 工作流完整 PostgreSQL 回归也通过版本化脚本创建平台表，再由 Hibernate `validate` 检查映射；H2 回归保持原方式。
- APP 组合迁移用例使用真实 PostgreSQL；原 APP 历史升级和兼容初始化用例通过 `APP_FORM_TEST_POSTGRES_URL/USER/PASSWORD` 启用，使用独立 schema 并在完成后删除。APP 其余领域测试仍按原有 H2/单元测试方式执行，没有宣称全部改用 PostgreSQL。
- 真实数据库测试位于 `simplepoint-platform-composition-verification`，通过 `SP_MIGRATIONS_POSTGRES_URL`、`SP_MIGRATIONS_POSTGRES_USER`、`SP_MIGRATIONS_POSTGRES_PASSWORD` 接入专用临时数据库，URL 必须是 `jdbc:postgresql://127.0.0.1:<port>/migration_test`；每个用例创建并删除自己的 schema。完整工作流数据库回归另外启用 `WORKFLOW_TEST_POSTGRES=1`，并通过 `WORKFLOW_TEST_POSTGRES_URL/USER/PASSWORD` 指向临时实例的 `workflow_test` 库。不要指向业务数据库。

剩余工作包括工作流跨领域读模型、经过校验的历史库接管、安装/启动顺序接入，以及完整单体与多种部署拓扑验收。共享事件和 Flowable 原生迁移已进入同一计划，但仍须接入正式安装器和宿主生命周期。当前库与上述模块接入不能作为这些事项已完成的证据。
