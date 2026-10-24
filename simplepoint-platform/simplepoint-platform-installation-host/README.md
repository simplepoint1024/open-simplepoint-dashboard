# 平台安装器

本工程是受信任的离线安装入口，不是业务服务，不开放 HTTP/RPC 安装接口。使用实际模块声明派生数据迁移、服务身份、资源归属、字典归属和事件拓扑。必要的领域写入通过选择性装配的本地领域端口执行，不启动业务初始化器、工作流引擎或消费者。

## 发行与配置

```bash
./gradlew :simplepoint-platform:simplepoint-platform-installation-host:installDist
simplepoint-platform/simplepoint-platform-installation-host/build/install/simplepoint-platform-installation-host/bin/simplepoint-platform-installation-host plan /private/installation.properties
```

配置必须是当前用户私有的普通文件，不能是符号链接。保留完整安装发行目录：审核会绑定全部运行 JAR 的名称和内容 SHA-256；升级期间不可替换其中的文件。数据库、Broker 和模块调用凭据只进入私有配置，不提交到仓库。

已有命令：`plan`、`migrate`、`verify`、`provision-review`、`provision`、`broker-review`、`broker`、`schema-review`、`schema-adopt`、`legacy-review`、`legacy-adopt`、`tenants-review`、`tenants-adopt`。`plan` 不连接基础设施；其他命令只连接明确声明的目标。数据库使用 `installation.stores.<store>.url/username/password`，模块和存储绑定继续使用平台模块配置。

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

## 恢复与验证边界

结构和历史数据阶段从部署 UUID 派生不同的稳定操作 ID，分别记录步骤及领域回执。已完成步骤可以重复核对；响应丢失时读取回执确认结果。`RUNNING` 且无完成回执的步骤不会自动重放：必须确认旧执行者已终止，通过安装日志的 `authorizeRetry` 管理端口记录退役证据，再继续同一审核计划。目前 CLI 尚未开放该退役确认入口。

不能用新的 UUID、删除日志、修复 Flyway 历史或打开 ORM DDL 来绕过恢复。`provision` 和 `broker` 已接入逐步安装日志及回执核对；缺少回执的 RUNNING 执行者退役确认 CLI、旧进程的协调退役以及正式运行验收仍属于剩余交付工作。单个命令成功不等于单体已上线。
