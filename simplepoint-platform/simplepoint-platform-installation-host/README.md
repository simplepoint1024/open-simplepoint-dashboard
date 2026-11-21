# 平台安装器

本工程是受信任的离线安装入口，不是业务服务，不开放 HTTP/RPC 安装接口。使用实际模块声明派生数据迁移、服务身份、资源归属、字典归属和事件拓扑。必要的领域写入通过选择性装配的本地领域端口执行，不启动业务初始化器、工作流引擎或消费者。

## 发行与配置

```bash
./gradlew :simplepoint-platform:simplepoint-platform-installation-host:installDist
simplepoint-platform/simplepoint-platform-installation-host/build/install/simplepoint-platform-installation-host/bin/simplepoint-platform-installation-host plan /private/installation.properties
```

配置必须是当前用户私有的普通文件，不能是符号链接。保留完整安装发行目录：审核会绑定全部运行 JAR 的名称和内容 SHA-256；升级期间不可替换其中的文件。数据库、Broker 和模块调用凭据只进入私有配置，不提交到仓库。

已有命令：`plan`、`migrate`、`verify`、`provision-review`、`provision`、`broker-review`、`broker`、`schema-review`、`schema-adopt`、`legacy-review`、`legacy-adopt`、`tenants-review`、`tenants-adopt`、`ownership-review`、`ownership-install`、`ownership-verify`、`ownership-inventory-review`、`ownership-inventory-verify`、`ownership-target-review`、`ownership-target-verify`。`plan` 不连接基础设施；其他命令只连接明确声明的目标。数据库使用 `installation.stores.<store>.url/username/password`，模块和存储绑定继续使用平台模块配置。

`provision-review` 预检查模块调用身份与资源/字典部署归属，输出绑定发行包、目标存储、完整命令和凭据的计划指纹。核对后配置 `installation.authorities.review-fingerprint`，再执行 `provision`。三个拥有方分别提交本地事务，安装日志记录每步；响应丢失时通过拥有方的只读回执确认结果。它不是跨存储事务，不回滚已成功的身份安装来掩盖后续失败。

`broker-review` 要求虚拟主机已显式存在，完整检查交换机、队列、绑定、身份和权限，并创建私有审核文件。审核允许把捕获的存量权限明确调整为计划派生的精确 ACL；不会改变已有身份的密码或接受特权身份。核对输出后配置 `installation.broker.review-fingerprint`，再执行 `broker`。执行前再次完整预检查，按交换机、队列、绑定、身份、权限排序，逐项写安装日志；权限必须仍是审核现状或已经达到目标。遇到第三种权限状态、拓扑不兼容或凭据冲突即停止。

这两个阶段分别使用 `installation.authorities.target/expected-generation` 和 `installation.broker.target/expected-generation`，安装日志都通过 `installation.journal.store` 指定。Broker 另需 `installation.broker.review-file`；预览只允许新建该文件。审核和执行必须使用同一份**不可变发行目录**，不要在执行中重新构建或覆盖 JAR。完成后重跑会核对当前效果；被后续部署替代的旧操作不能被用来恢复旧权限。

## PostgreSQL 旧库结构接管

先停止或隔离不具备新写入边界的旧执行者，保留可恢复备份，在副本中验证。正式单体复用原数据库及现有基础设施；参照库和备份副本只用于校验与演练。

1. 按同一发行包的声明，在独立参照库**实际执行每个模块的 V1**。参照库不得用正数 baseline 代替建表，也不能已经执行 V2 及以后。
2. 审核旧库结构差异，使用经过验证的兼容升级补丁。核验业务不变量与第三方引擎版本，保存不含业务明文的验证证据。
3. `schema-review` 比较全部模块的实际结构与参照，并创建私有审核文件，不修改目标库。
4. 将输出的 `schemaAdoption.reviewFingerprint` 填入配置，执行 `schema-adopt`。随后显式执行 `migrate` 和 `verify`，执行 V2 及以后版本。

相关配置：

| 配置 | 含义 |
| --- | --- |
| `installation.operation` | 本次部署的固定 UUID，不能在重试时替换 |
| `installation.schema.target` | 稳定的安装目标名称 |
| `installation.schema.expected-generation` | 安装日志中的预期世代，首次为 0 |
| `installation.schema.review-file` | 审核文件路径，预览只允许新建 |
| `installation.schema.review-fingerprint` | 预览输出的完整计划指纹 |
| `installation.schema.evidence-file` | 业务数据/原生引擎核对证据文件 |
| `installation.references.<store>.url/username/password` | 独立 V1 参照库 |
| `installation.journal.store` | 明确选择安装日志所在的存储绑定 |

业务证据由部署人员核验；安装器绑定其内容指纹，不会把任意文件解析成“业务已验证”。结构审核不替代外键之外的业务约束、数据解密、审批流和授权验证。当前结构接管支持 PostgreSQL V1；遇到其他版本或方言会停止。

## 历史初始化与字典接管

结构迁移完成后、部署身份启用前，执行 `legacy-review` 和 `legacy-adopt`。

`installation.legacy.targets-file` 是审核过的普通初始化归属清单。例如：

```json
[
  {
    "targetModule": "identity",
    "identity": {"moduleCode": "rbac-core", "type": "system", "key": "system-user"}
  }
]
```

目标必须是所选模块，并声明平台初始化器。通知、OIDC 等独立模块字典从 `ModuleDictionaryPlan` 自动派生，通过身份域的专用接管端口处理，不要重复放入普通归属清单。每条历史贡献必须有可确认的成功版本；不确定或冲突的记录需先处理，不能删掉记录后重跑。

配置使用 `installation.legacy.target`、`expected-generation`、`review-file`、`review-fingerprint`、`targets-file`，与结构阶段的同名后缀含义一致。预览输出 `legacy.reviewFingerprint`。审核绑定发行包、数据库端点及实际库/schema、来源历史指纹、领域数据指纹和操作顺序。连接目标或发行包变化必须重新审核。

接管保留原历史行。普通初始化另外记录模块作用域的已执行版本；字典接管仅建立归属和持久回执，保留原数据。随后再执行 `provision`，不能颠倒顺序。

## 历史租户资源与权益接管

`tenants-review` / `tenants-adopt` 用于独立租户资源目录升级。必须选中 `identity` 模块，并先执行同一发行包的 `migrate` 和 `verify`；命令自身只验证结构，不隐式执行领域迁移或系统启动任务。此入口不依赖用户登录，避免历史个人空间尚待接管而无法登录的循环依赖。

安装宿主只装配所选持久化绑定、身份域接管存储和授权失效服务，不启动 HTTP、OIDC 登录、业务初始化器、流程引擎或 MQ 消费者。运行期 HTTP 接管继续要求平台管理员；离线适配器不参与组件扫描或 RPC 导出，并拒绝 HTTP 请求、平台授权上下文或 Spring Security 登录上下文。离线写入的 `adoptedBy` / 发放执行人为技术主体 `installation`，与安装日志中的操作 ID 配合审计，不伪造人类管理员。

| 配置 | 含义 |
| --- | --- |
| `installation.operation` | 固定部署 UUID；重试保持不变 |
| `installation.tenants.target` | 稳定安装目标，例如 `tenant-entitlements/production` |
| `installation.tenants.expected-generation` | 安装日志目标的预期世代；不是资源版本 |
| `installation.tenants.review-file` | 私有审核文件；预览只允许新建 |
| `installation.tenants.review-fingerprint` | `tenants-review` 输出的 `tenantAdoption.reviewFingerprint` |
| `installation.journal.store` | 安装日志所在存储绑定 |

操作顺序：

1. 停止旧目录/套餐写入者，完成结构迁移，并确保既有平台目录与 CORE/STORAGE 基础应用成员完整。缺少这些事实时接管会失败，不自动构造购买权益。
2. 执行 `tenants-review /private/installation.properties`。按稳定租户 ID 分页收集所有有效、待接管租户，写入租户清单与数量摘要；不创建目录、权益或安装完成记录。单次清单上限为 10000 个租户，超限明确失败。发现有效租户缺少准备标记也会失败，不能将它静默排除后报告迁移完成。
3. 核对清单和数量，配置输出的计划指纹后执行 `tenants-adopt`。发行包、存储目标和租户清单必须匹配；预览资源数量只供参考。执行时重新读取当前定义和旧许可，不比较资源版本、预览版本或资源更新时间。
4. 所有未完成租户先通过预检查，随后每个租户独立提交目录、账本、准备状态和授权失效。中途失败保留此前成功租户，修复原因后使用原计划重试；已完成租户仅读取原回执，不重新发放。最终回读的聚合回执还必须与安装日志保存的回执一致。
5. 保存命令结果及授权差异验证。`applied=true` 仅表示本次审核清单完成；还需 `pendingTenantsRemain=false`。审核后新增的待接管租户不会偷偷加入当前清单，应另行审核处理。完成历史接管后，仍须核对内置组织初始化、新注册用户、资源授权链路和页面验收，才能开放业务。

安装器沿用已有日志的失败/不确定执行恢复规则。领域提交与安装日志提交不构成分布式事务；缺失 ACK 通过只读回执核对，不靠回滚或更换 UUID 消除不确定状态。

## 物理命名空间归属目录

`ownership-review`、`ownership-install`、`ownership-verify` 安装和核对 DNA 物理数据命名空间的归属目录。
命令沿用所选模块的结构验证与控制库安装日志；不会隐式执行领域迁移、历史表采纳或运行时激活。

| 配置 | 含义 |
| --- | --- |
| `installation.ownership.target` | 此部署的稳定归属安装目标 |
| `installation.ownership.expected-generation` | 安装日志预期代数 |
| `installation.ownership.namespaces` | 逗号分隔的命名空间配置键，最多 512 个 |
| `installation.ownership.namespaces.<key>.url/username/password` | 私有的目标 JDBC 配置，不写入审核文件 |
| `installation.ownership.namespaces.<key>.namespace` | 目标的精确 schema/database 名称 |
| `installation.ownership.namespaces.<key>.namespace-id` | 已安装目录的稳定 UUID；新安装可省略，由安装操作与配置键确定并保存 |
| `installation.ownership.review-file` | 私有审核文件，审核命令只允许新建 |
| `installation.ownership.review-fingerprint` | 审核输出的计划指纹，安装与验证必填 |
| `installation.journal.store` | 保存安装日志的 PostgreSQL 控制库 |

先运行 `ownership-review /private/installation.properties`，将精确原生目标身份、协议指纹、命名空间 UUID、
发行与控制库绑定写入仅拥有者可读写的文件，并同步文件和父目录。审核不创建目标归属目录。
再将返回指纹填入私有配置，运行 `ownership-install`；最后用同份审核运行 `ownership-verify`。
改变目标、命名空间 UUID、操作、发行或控制库时拒绝沿用原计划。复用已有目录时必须明确声明原 UUID。

PostgreSQL 审核账号需能读取 `pg_control_system`；MySQL 审核账号需有完整元数据可见性。
安装器先核对全部目标再开始 DDL，执行期间再次在命名空间锁下核对。MySQL DDL 中断可能留下带标记的空目录，
只有同一持久身份的计划可以恢复；未知 RUNNING 执行仍需既有人工恢复协议，不能自动认定旧执行者已停止。
日志完成后仍复查实际目录，不能仅凭旧日志将已删除目录报告为完成。

**此命令不开放业务写入。** 返回的 `legacyAdopted=false`、`activated=false` 明确表示只完成结构安装。
运行时现在要求独立激活回执；历史业务表必须经过完整的归属采纳及旧写入者退出后才能激活。
公共库的空命名空间激活方法仅供可信安装流程使用，不作为本命令的历史数据绕过入口。

## DNA 历史绑定盘点

在所选模块包含 `dna-runtime` 时，可执行 `ownership-inventory-review` 与 `ownership-inventory-verify`。
命令使用模块计划中 DNA 自己的控制库，通过领域本地安装接口获取清单；不借用宿主默认库，不读取其他领域表，
不启动领域 HTTP、后台工作器或 JPA 上下文。入口仍先执行既有模块结构验证。

私有配置增加 `installation.ownership.inventory.review-file`；审核命令以只允许新建的方式保存完整清单，
返回 `physicalBindingInventory.reviewFingerprint`。复核时将该值填入 `installation.ownership.inventory.review-fingerprint`，
使用相同 `installation.operation`、发行包和控制库运行复核命令。
审核文件绑定操作、发行身份、模块存储及全部保留绑定的精确指纹，大小限制为 32 MiB。

盘点包含所有租户、软删除及已退役记录；缺失模型/绑定、租户不一致或超过 10000 条均明确失败。
数据库行级安全策略会过滤控制表时也拒绝盘点，不输出经过过滤的清单。盘点使用原生只读事务，返回前结束事务并释放连接。
复核期间新增记录、改变物理绑定/生命周期/修订或更换控制库会使旧审核失效。显示名称变化不改变物理归属。
该命令只证明控制清单仍一致，返回 `controlBindingsVerified`，不会认证实际物理表、采纳历史数据、
回填 namespace ID 或激活运行时。数据源别名的实际冲突、模型结构与共享基础表仍须在后续采纳阶段核对。

## DNA 原生物理目标审核

完成控制绑定审核后，执行 `ownership-target-review` 和 `ownership-target-verify`。
命令先按原指纹复核控制清单并释放控制库连接，再调用 DNA 离线适配器解析全部保留绑定；
目标读取结束后再次复核控制清单。不会在控制事务中等待目标数据库。

在同一份私有配置中补充：

```properties
installation.ownership.targets.review-file=/private/physical-targets.json
installation.ownership.target-sources=primary
installation.ownership.target-sources.primary.data-source-id=<控制绑定中的数据源ID>
installation.ownership.target-sources.primary.catalog=<控制绑定中的原始catalog；缺省哨兵需显式留空>
installation.ownership.target-sources.primary.url=<直接连接到该catalog的JDBC URL>
installation.ownership.target-sources.primary.username=<离线安装身份>
installation.ownership.target-sources.primary.password=<从秘密配置提供>
```

每个 `(data-source-id, catalog)` 精确对应一个连接配置；同一源的不同 catalog 分别声明。
连接配置应来自本次部署所审核的托管数据源配置，不根据浏览器输入生成。重复、缺失、多余的映射在连接目标库前拒绝。
目录名不裁剪；空 catalog 保留“使用该连接当前库”的含义，原生查询核对实际库，禁止默默连接其他库或自动重写 URL。
PostgreSQL 空 schema 使用连接当前 schema；MySQL 非空 schema 必须与实际库一致。

审核复用公共归属库的原生命名空间身份、目录检查及引号/大小写规则。
不同数据源别名指向同一原生表、命名空间标识被复制到另一原生位置、已有绑定标识不匹配均拒绝。
审核文件包含原生服务器/库/命名空间标识、目录安装状态及规范表名，不包含连接 URL、账号或密码。
文件独占创建，权限为 `0600`，上限 32 MiB；结果 `physicalBindingTargets.reviewFingerprint`
填入 `installation.ownership.targets.review-fingerprint` 后复核。
更换默认命名空间、安装/替换目录、控制绑定或发行变更都会使旧审核失效。

该命令仅返回 `nativeTargetsVerified`，不证明业务表存在或其结构正确，不授予关联读写权限。
尚无归属目录的命名空间允许作为待安装目标被记录；尤其 MySQL 的原生 schema 身份是库名，
仅凭没有目录 UUID 的审核不能证明同名库未被重建。后续写入仍须先安装目录并重新审核，
核验不可变模型/发布状态和共享基础表，证明旧执行者退出，提交归属及领域回执后协调激活。
审核不写归属、不回填绑定、不激活运行时，也不能替代上述步骤。

## DNA 模型与原生结构审核

`ownership-schema-review/verify` 依赖控制清单和原生目标审核。私有配置指定
`installation.ownership.schema.review-file`，首次审核后将输出指纹填入
`installation.ownership.schema.review-fingerprint`，使用同一发行、部署操作和数据源复核。

领域适配器在同一个 PostgreSQL 可重复读只读快照内读取完整绑定、所有保留的不可变模型版本和已完成发布。
拒绝版本缺失、租户错配、JSON/校验和不一致、未完成迁移和未释放关系保护；保留软删除历史。
逐个物理目标检查已发布表与完成发布的结构校验和，未发布目标必须不存在。
共享运行时表从领域唯一注册表获取：存在时须符合实际方言生成的目标结构，不存在时只记录缺失，不建表、不修复。
审核前后重新核对控制状态与原生位置；控制库事务不会跨越目标数据库读取。

私有文件上限 32 MiB，包含模型定义及结构证据，必须按业务元数据保护，禁止提交仓库。
这两个命令仅返回 `schemaVerified`，不会采纳或激活。

## DNA 已清理退役模型的绑定采纳

历史清理可能保留已软删除的 `RETIRED` 模型，却删除其绑定、全部版本和发布历史。
普通归属审核继续拒绝这类缺失；不能删除墓碑、伪造版本或把模型恢复为可用状态。
仅在原绑定备份和清理证据可核实、所有资源投影已正式下线、原生同名对象确实不存在时，
可先执行独立的清理采纳，再执行下面的完整归属采纳。

```properties
installation.ownership.cleanup.plan-file=/private/retired-cleanup-plan.json
installation.ownership.cleanup.evidence-file=/private/retired-cleanup-evidence.json
installation.ownership.cleanup.review-file=/private/retired-cleanup-review.json
installation.ownership.cleanup.retirement-kind=docker
installation.ownership.cleanup.retired-containers=<完整旧写入容器清单，以逗号分隔>
```

计划使用 `PhysicalRetiredCleanupPlan`：操作 ID 从本次安装操作按 `retired-cleanup` 派生，
证据 SHA-256 绑定原清理清单及备份来源，清单保存原始绑定身份、原生位置和当前精确模型墓碑。
每条绑定的 `cleanupEvidence` 必须等于该证据摘要。计划必须覆盖控制库内所有缺失绑定的模型。
文件按私有元数据保护；计划和证据分别限制为 16/32 MiB，不包含凭据。

1. 先完成 DNA V6 等模块迁移。执行 `ownership-cleanup-review`，只读核对控制状态和原生缺席，
   独占创建 `0600` 审核文件。将结果填入 `installation.ownership.cleanup.review-fingerprint`。
2. 按完整归属采纳相同的退出规则停止并保留全部旧写入者。执行 `ownership-cleanup-adopt`，
   在所有目标命名空间锁内再次核对对象缺席，并在一个控制事务内恢复软删除绑定及写入证据回执。
   保留模型计数、状态、软删除和授权，不重建版本或业务表，不激活运行时。
3. 同一维护窗口执行 `ownership-cleanup-verify`。提交响应丢失时复用原计划核对回执；
   部分回执、墓碑变化、同名原生对象或绑定变化均拒绝，不自动修复。
4. 重新生成完整控制、目标和结构审核，再完成普通归属采纳与激活。
   清理证据只允许对应墓碑缺少全部历史，不能忽略仍存在的版本或发布记录。

## DNA 历史归属采纳与激活

新增命令 `ownership-adoption-review`、`ownership-adopt`、`ownership-adoption-verify`。
实现使用公共安装日志，按“各物理库归属及回执 → DNA 控制库原子绑定及回执 → 各命名空间激活”的顺序执行。
归属、Runtime、宿主与真实 Docker 退出检查的针对性回归已通过；命令成功不代表跨表单业务验收通过。

必须先安装固定 UUID 的归属目录，再完成控制、原生位置与模型结构三类审核。补充配置：

```properties
installation.ownership.adoption.target=<此部署稳定的采纳目标>
installation.ownership.adoption.expected-generation=0
installation.ownership.adoption.review-file=/private/physical-adoption.json
installation.ownership.adoption.retirement-kind=docker
installation.ownership.adoption.retired-containers=<完整旧写入容器清单，以逗号分隔>
installation.journal.store=<已声明的控制库>
```

1. 从部署清单核实所有可能写入物理数据或控制绑定的旧执行者，包括同时存在的微服务和单体服务、联邦 SQL 写入口及后台写任务。
   当前 CLI 支持本机 Docker 或 Linux/systemd；远程服务、手工启动进程或其他调度器需要各自的退出适配器，不能遗漏后沿用本机结果。Docker 固定连接本机 `/var/run/docker.sock`，不读取环境中的远程 Docker context。
2. 执行 `ownership-adoption-review`，将完整模型、结构、归属、操作及退出服务清单写入独占 `0600` 文件，最大 48 MiB。
   Docker 审核绑定 daemon ID、每个容器的完整 ID 与镜像 ID，不读取/保存容器环境变量。此时不要求旧执行者已停止，也不会写入任何归属或控制数据。将输出指纹保存为 `installation.ownership.adoption.review-fingerprint`。
3. 在维护窗口停止全部审核容器并将 restart policy 设为 `no`。执行阶段核对原 daemon、容器和镜像身份，以及状态 `exited`、PID 为零、未运行/重启/暂停且禁止自动重启；同名容器重建也拒绝。容器必须保留到采纳复核结束，不能先删除再声称原执行者已退出。
   若采用 `retirement-kind=systemd`，改为配置 `machine-id` 与 `retired-units`。执行检查审核单元已 masked 且非活动、主进程为零、保留控制组无进程；仅 disable 或从发现服务注销不能替代。自有 `/etc/systemd/system` 单元须按部署流程实际屏蔽，不能将失败的 mask 命令当作完成。
4. 执行 `ownership-adopt`。每个物理库在原生命名空间锁下复核表结构，事务提交精确归属及不可变回执；尚不开放运行时。
   全部原生回执存在后，DNA 在自己的一个事务内锁定控制清单、校验完整模型历史、回填 legacy namespace ID、推进绑定修订并写 V5 控制回执。
   不改变模型业务状态、软删除、授权或业务行。激活前再次核对控制回执、模型和同一锁定连接上的结构。
5. 使用同一私有计划运行 `ownership-adoption-verify`。全部回执、控制清单和停机检查通过后才部署/启动新二进制并恢复服务。
   恢复阶段读取持久计划，不要求已经更新的控制库重新匹配旧的盘点文件；不能重新生成 UUID 绕过中途状态。

原生与控制数据库之间不宣称分布式原子事务。安装日志通过各自持久回执恢复提交成功但 ACK 丢失的步骤；无回执的失败/运行中步骤使用现有显式退出与重试授权协议，不盲目重放。
后续业务正常变更不撤销既有激活回执，但完整安装复核只适用于尚未恢复服务的维护窗口。
归属采纳不会开放 APP/DNA 的其他发布门禁，也不能替代真实权限、关联操作和双部署验收。

## 恢复与验证边界

结构和历史数据阶段从部署 UUID 派生不同的稳定操作 ID，分别记录步骤及领域回执。已完成步骤可以重复核对；响应丢失时读取回执确认结果。`RUNNING` 且无完成回执的步骤不会自动重放：必须确认旧执行者已终止，通过安装日志的 `authorizeRetry` 管理端口记录退役证据，再继续同一审核计划。目前 CLI 尚未开放该退役确认入口。

不能用新的 UUID、删除日志、修复 Flyway 历史或打开 ORM DDL 来绕过恢复。`provision` 和 `broker` 已接入逐步安装日志及回执核对；缺少回执的 RUNNING 执行者退役确认 CLI、旧进程的协调退役以及正式运行验收仍属于剩余交付工作。单个命令成功不等于单体已上线。
