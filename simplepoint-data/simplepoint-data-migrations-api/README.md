# 迁移声明 API

本库只包含 `ModuleMigrationPlan` 和 `ModuleMigrationContributor`，依赖中立组合元数据。
发现所选模块、读取受控 SQL 资源和生成安装计划不加载 Flyway、数据库驱动或 Spring。

业务模块携带的共享 schema 适配器依赖本库。安装工具显式依赖
[`simplepoint-data-migrations`](../simplepoint-data-migrations/README.md) 获得执行器；
不要仅为了声明 SQL 资产把执行器带进业务服务。否则 Flyway 出现在 classpath 后，
宿主的 Spring Boot 默认自动配置可能先于平台安装协议运行。

包名和声明契约保持不变；执行器通过 `api` 依赖导出本库，已有安装调用方无需修改 import。
元数据与完整性测试由执行器项目的 `ModuleMigrationContributorTest` 覆盖；
原生表及事件适配器另有打包发现和真实数据库验证。
