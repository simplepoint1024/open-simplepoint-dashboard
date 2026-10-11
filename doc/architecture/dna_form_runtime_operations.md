# DNA 表单 Runtime 支持矩阵与运维指南

> 更新日期：2026-09-30<br>
> 适用范围：DNA Runtime API、表单物理表、结构迁移、Calcite Record 执行和平台治理页

## 1. 稳定执行边界

APP 通过版本化的 DNA Runtime API 管理物理模型和表单记录。调用方只提交
`physicalModelId`、目标逻辑结构或结构化记录命令。数据源、Schema、表名、租户字段、
审计字段和权限谓词由服务端解析。

- Record 查询与标准 `INSERT`、`UPDATE`、软删除统一经过 Calcite 校验、规划和 JDBC 下推。
- 同一事务命令组固定使用一个物理连接，任一步骤失败都会回滚全部步骤。
- DDL 由 Runtime Schema Planner 计算差异，方言 SPI 生成原生语句，目标 JDBC 执行。
- 外发 DNA JDBC 驱动保持只读。写入使用 Runtime Record API，结构变更使用 Runtime
  Schema Publication API。
- SQL 控制台、外发 JDBC 和表单 Runtime 是独立授权入口，不能互相替代。

## 2. 数据库支持矩阵

| 数据库 | 正式版本范围 | 实际验证版本 | Record CRUD | 初始发布 | 字段演进 | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL | 13–17 | 17 | 支持 | 支持 | 支持 | 时间字段使用 `TIMESTAMP(6) WITH TIME ZONE` |
| MySQL | 8.0–8.4 | 8.4 | 支持 | 支持 | 支持 | 时间字段使用 `DATETIME(6)`；连接时区应固定为 UTC |

平台记录 ID 和逻辑 UUID 在两个方言中统一存为 `VARCHAR(36)`。这是 Calcite JDBC DML
下推的可移植边界，可避免 PostgreSQL 原生 UUID 在 Calcite `JdbcTableModify` 中无法生成
可执行参数类型。逻辑 API 仍严格校验 UUID 格式。

Record API 的 `DATETIME` 值统一按 UTC 写入，并规范化到秒精度。物理列保留 6 位小数能力，
但当前 Calcite 1.40 的 MySQL JDBC DML 下推会把时间类型建模为秒精度；API 不声明跨方言的
亚秒保存能力。

Oracle、SQL Server、达梦、ClickHouse、Hive、Presto 和 Trino 没有 Runtime 写入与迁移的
正式支持声明。缺少能力描述器或真实数据库测试时，规划阶段直接拒绝。

## 3. Record API 能力

### 3.1 查询

- 分页、计数、按 ID 查询和字段投影。
- `AND`、`OR`、`NOT` 组合，以及等值、集合、模糊、范围、空值判断。
- 最多 32 个条件节点、4 层嵌套、3 个排序字段和每页 100 条记录。
- 服务端追加稳定的 `id ASC` 排序，分页与 count 使用相同租户、软删除、行权限和业务条件。
- 隐藏或掩码字段不能参与筛选和排序；掩码字段返回固定脱敏值。

Record API 不接收任意 SQL，也不提供跨模型 JOIN、聚合或窗口函数。此类查询使用受控的
DNA 查询模型或联邦查询入口。

### 3.2 写入与事务

- 新增由服务端生成 UUID、初始版本、租户、组织和审计字段。
- 更新与软删除必须携带 `expectedVersion`。版本冲突返回
  `RECORD_VERSION_CONFLICT`，权限范围内不存在则返回 `RECORD_NOT_FOUND`。
- 一次事务允许 1–100 个同模型命令，超时范围为 1–120000 毫秒；普通单记录命令默认
  30000 毫秒。
- 当前事务只覆盖一个物理模型和一个数据源。跨数据源写入、分片、分批写入及分布式事务
  保留在后续规划中。

### 3.3 权限与审计

运行时取当前页面资源、持久化的模型页面绑定、平台行权限和动态字段权限的交集。列表、
count、详情、更新和删除使用同一权限谓词。客户端不能提交物理目标或租户字段。

成功和失败审计保存租户、操作者、模型、APP、表单、版本、数据源、操作、影响行数、耗时、
结果和稳定错误码。审计与慢操作日志不保存业务字段值、SQL、密码或连接凭据。

## 4. Schema 发布与演进

### 4.1 普通发布支持

- 首次发布：建表、系统字段、主键、普通/唯一/组合索引、表注释和字段注释。
- 版本演进：增加字段、字段重命名、类型/长度/精度/默认值/可空性调整、删除字段、索引
  增删和注释调整。
- 新增必填字段会检查表是否为空及默认值；改为非空会检查现有 NULL；收窄长度、精度或
  不兼容类型会标记为破坏性。
- 每次发布执行“读取实际结构 → 比对活动版本 → 生成确定计划 → 持久化步骤 → 执行 →
  重新读取校验 → 精确失效缓存 → 激活版本”。

物理表名由模型 ID 生成并保持不变。表单改名只更新展示元数据，不执行表重命名。唯一约束
通过唯一索引表达；业务外键和检查约束尚未进入表单逻辑结构契约。

### 4.2 破坏性操作

字段删除、类型收窄和不兼容转换会生成 `DESTRUCTIVE` 计划。普通发布在持久化或执行任何
步骤前返回 `SCHEMA_DESTRUCTIVE_CHANGE_REQUIRES_APPROVAL`。APP 提交和普通迁移重试不能
绕过该门禁。

`TRUNCATE`、`DROP TABLE` 和物理表重命名不属于表单发布能力，也没有 Record API 入口。
数据清理和物理生命周期操作必须由独立管理流程实现，并保留审批与审计；在该流程落地前，
管理员只能按数据库变更流程处理，不能把原始 DDL 暴露给 APP。

### 4.3 失败恢复

1. 在“表单数据底座 → 结构迁移”查看失败码和步骤状态。
2. 数据库不可用或瞬时执行失败时，恢复依赖后使用服务端提供的“重试迁移”动作。
3. 进程退出后迁移可能停留在非终态。确认原执行节点已经停止，并等待服务端无活动阈值后，
   使用“恢复中断迁移”将其隔离并置为 `FAILED`。恢复会轮换执行令牌，旧进程不能继续推进
   控制面状态；目标数据库中已经发出的 DDL 不能撤回，因此必须先检查实际表结构，再重试。
4. 出现 `SCHEMA_DRIFT_DETECTED` 时，先把实际表结构恢复到当前活动版本的结构快照；禁止
   通过强制刷新掩盖漂移。
5. 重试会生成新的执行令牌，重新规划并校验步骤 checksum。已满足步骤标记为 `SKIPPED`，
   不会重复执行。
6. 复核或缓存失效失败时目标版本不会激活。问题修复后从同一失败迁移重试。

## 5. 配置和可观测性

```yaml
simplepoint:
  dna:
    runtime:
      execution:
        max-concurrent-per-tenant: 16
        slow-threshold-ms: 2000
      publication:
        stale-after-ms: 900000
```

`max-concurrent-per-tenant` 允许 1–1000，超限返回 HTTP 429 和
`RECORD_RATE_LIMITED`。`slow-threshold-ms` 允许 1–3600000。`stale-after-ms` 允许
60000–86400000，只控制人工恢复入口的最短无活动时间，默认 15 分钟。

Micrometer 指标 `simplepoint.dna.runtime.operation` 使用 `operation` 和 `outcome` 标签，避免
把租户和模型放进高基数指标。持久化审计及结构化慢日志包含租户、模型、数据源、操作和耗时，
用于精确定位问题。

## 6. 升级顺序与回滚

PostgreSQL 控制面按顺序执行：

1. `doc/deployment/sql/20260929-dna-form-runtime-foundation.sql`
2. `doc/deployment/sql/20260929-dna-form-runtime-publication.sql`
3. `doc/deployment/sql/20260930-dna-record-runtime.sql`

第三个脚本只增加模型与页面资源的授权绑定表，不修改业务物理表。删除该表会丢失 Record
页面授权关系，只能在停止 Runtime 调用并确认无需保留绑定后执行脚本中的破坏性回滚。

若曾使用本功能开发期的 PostgreSQL 原生 UUID 物理表，应在接入 Record 写入前将系统 `id`
和业务 UUID 字段按数据库变更流程转换为 `VARCHAR(36)`，并重新生成活动结构快照。正式版本
从首次发布开始直接使用 `VARCHAR(36)`，不需要该兼容处理。

## 7. 验证命令

```bash
JAVA_HOME=/path/to/jdk-21 PATH=/path/to/jdk-21/bin:$PATH \
./gradlew \
  :simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-runtime-api:check \
  :simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-runtime-implementation:check \
  :simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-jdbc-driver:check \
  :simplepoint-services:simplepoint-service-dna:test \
  -Psimplepoint.frontend.skip=true

corepack pnpm@11.15.1 --dir simplepoint-react run typecheck:dna
corepack pnpm@11.15.1 --dir simplepoint-react run build:dna
corepack pnpm@11.15.1 --dir simplepoint-react run check:frontend
```

真实数据库测试通过 `DNA_RUNTIME_TEST_POSTGRES_*` 和 `DNA_RUNTIME_TEST_MYSQL_*` 环境变量
启用。正式支持结论必须同时包含 Schema 发布/演进与 Calcite Record 提交/回滚测试。
