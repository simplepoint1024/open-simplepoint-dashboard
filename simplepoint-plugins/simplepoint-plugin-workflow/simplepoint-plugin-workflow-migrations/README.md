# Flowable 原生表迁移

本库为工作流提供独立的 `flowable-process` schema 贡献，复用平台
`ModuleMigrationContributor`、Flyway 锁、历史和校验和。不加载 Flowable 引擎、
Spring/JPA、业务实现或后台执行器。

领域入口声明 `@FlowableProcessSchema`。安装计划依据所选模块和存储绑定，
每个使用引擎的存储生成一份贡献；宿主和存储组改名不会改变迁移历史身份。
缺少适配器、重复表归属及未声明存储沿用平台完整性门禁。
工作流实现自动依赖本库，无需宿主重复列出原生表或脚本。

## 基线与版本

当前支持 PostgreSQL、Flowable 发行版 **8.0.0**，原生 schema 版本 **8.0.0.0**。
V1 按依赖顺序组合供应方五份建库 SQL：common、identity、eventregistry、engine、history，
共 **45 张表**。各供应方脚本文本保持原样，只增加来源注释；整个基线作为一个
Flyway 版本在 PostgreSQL 事务内执行。

原生 IDM 表是 Flowable 的内部表，不是平台 identity 领域表，也不用于消除工作流跨领域查询依赖。
引擎启动仍会正常写入 `cfg.*` 配置记录和分配 ID；禁止运行期 DDL 不等于禁止这些引擎业务写入。

资源和来源位于：

```text
META-INF/simplepoint/modules/flowable-process/
  LICENSE-Flowable.txt
  NOTICE.txt
  db/sources.json
  db/manifest.properties
  db/postgresql/V1__flowable_8_0_0.sql
```

`sources.json` 记录供应方坐标、精确资源路径和 SHA-256。测试核对基线与当前固定版本
供应方资源的完整字节内容，并在真实 PostgreSQL 上对比原生建库与受管基线的列、约束、
索引、版本/配置记录。升级供应方依赖必须提供新的版本化迁移并更新兼容性验证；
不得覆盖已经发布的 V1、自动 repair 或让引擎在运行期补表。

## 安装与边界

使用通常的 `ModuleMigrationPlan.discover` 和 `ModuleMigrationEngine.migrate` 即可，
无需启动临时流程引擎。安装结束后运行期保持 `databaseSchemaUpdate=false`。
数据库账号、schema 和 PostgreSQL 连接仍由安装环境提供。

任一已有原生表没有受管历史时，完整计划在写入其他模块前以
`LEGACY_SCHEMA_ADOPTION_REQUIRED` 拒绝；不会把原生版本记录直接当作平台迁移历史。
旧库接管工具、正式宿主安装/就绪编排仍待完成。
原独立服务的显式旧初始化入口保持兼容，不因此自动接管现有业务库。

真实数据库验证包括完整结构对比、并发安装、失败 DDL 回滚与恢复、
关闭自动建表后的流程执行、重启后继续办理，以及未受管旧表拒绝。
工作流 PostgreSQL 全量回归使用同一安装贡献；H2 回归保留测试专用原生建库，
这不代表本库提供 H2 生产安装基线。
