# 应用工作台服务

`simplepoint-service-app` 是与 AI、DNA 平级的独立服务，使用 `app` 服务名和 `/app` 网关前缀。
前端 `simplepoint-app` 通过 `/app/mf/mf-manifest.json` 提供页面。“应用平台”下的工作台现提供租户应用列表、创建应用和添加附属页面；应用模板和应用市场仍为空页面。

资源清单位于 `src/main/resources/META-INF/simplepoint/resources/app.json`，三个页面权限点分别是
`app.workbench.view`、`app.templates.view`、`app.marketplace.view`。内置组织标准套餐包含
`simplepoint-app` 应用，新页面资源在注册后关联到该应用并授予示例组织所有者与管理员。

工作台使用 `simpoint_app_definitions` 和 `simpoint_app_pages` 两张表存储租户隔离的应用与页面元数据。创建应用时同步创建唯一主页面；附属页面可以继续添加。此阶段页面均为草稿，`resource_id` 和 `resource_code` 仅预留绑定位，不会自动进入角色授权目录。后续发布时应先创建稳定的运行时页面路由与租户资源，再写入绑定标识，未发布页面不能借工作台权限直接打开。

后续表单定义、版本和运行时，以及流程定义、实例和任务，应各自通过独立领域接口接入这个服务；
两者通过稳定的表单版本和提交记录标识协作，不共用领域表。

表单提交数据规划采用“每个表单一张物理表”（用户于 2026-09-29 确认），以支持直接 SQL 查询。
表单发布需要根据版本化定义生成建表或改表计划，规划经 DNA 的 Calcite 扩展解析和统一 DDL 入口，
交由目标数据源 JDBC 原生执行，
并在成功后刷新元数据。实现时还需确定字段变更与存量数据迁移、租户数据隔离、权限及运行时 CRUD 的约束。
当前尚未实现表单定义、发布或运行时；此存储决策不代表现有应用页面各自对应一张表。

本地构建：

```bash
./gradlew :simplepoint-services:simplepoint-service-app:installDist
cd simplepoint-react && pnpm build:app
```
