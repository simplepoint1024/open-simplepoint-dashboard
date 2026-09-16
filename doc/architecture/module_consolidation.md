# P0/P1 模块合并记录

合并后保留 95 个实际构建模块。为支持 IDEA 按领域导入为树状结构，另有 24 个
仅用于分组的父项目，共 119 个子项目；分组节点不创建 Java 源集、测试或 JAR。
统计不包含根项目和 `buildSrc`；可运行服务及服务端口不变。
P2 的 i18n、notification、OIDC、cache、
data-cp-endpoint 未合并。

## P0：目录与 Gradle 项目分离

- `settings.gradle.kts` 显式列出 95 个叶子模块，项目 ID 与物理目录层级一致。
- 删除 21 个父目录构建文件；Gradle 根据嵌套路径建立 24 个轻量分组父项目。
- 分组节点只应用 `base` 插件；Java、Checkstyle、JaCoCo 和依赖约定仅应用到实际构建模块。
- 父目录中的 group 和服务 JDBC 依赖约定集中到
  `gradle/project-conventions.gradle.kts`。现存模块的 Maven group 保持不变。
- 仍保留有依赖聚合作用的数据和安全模块，避免隐式改变消费者的依赖。
- 新增模块时必须登记到 `moduleDirectories`；不存在的目录和重复名称会使配置失败。
- `-PexcludeProjects` 仍按物理目录的 glob 匹配，但消费者依赖的模块不能单独排除。

Gradle 命令使用与目录对应的完整项目路径，例如：

```bash
./gradlew :simplepoint-services:simplepoint-service-host:run
./gradlew :simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-agent-implementation:test
./gradlew :simplepoint-services:simplepoint-service-ai:test
```

仓库内的开发脚本、镜像构建脚本、项目依赖和文档入口已同步。外部脚本与 IDE
自定义运行配置需要重新导入 Gradle 或更新任务路径。在 IDEA 的 Gradle 工具窗口
执行“Reload All Gradle Projects”后，可按 `simplepoint-services`、
`simplepoint-plugins → simplepoint-plugin-ai` 等层级展开模块。

## P1：合并映射

下表数量仅统计各领域的叶子模块，不计 P0 已移除的分组父项目。

| 领域 | 合并前 | 合并后 | 实际布局 |
| --- | ---: | ---: | --- |
| AI | 32 | 16 | 8 个领域各保留 `-api`、`-implementation`；管理接口和对应测试归入 AI 服务 |
| RBAC | 12 | 7 | core/router/tenant 各保留 API 和 implementation，Router 仓储额外独立；管理接口归入 Common |
| Auditing | 13 | 8 | logging 的 API/implementation/monitor；rate-limit 的 API/implementation/gateway；redis 的 API/implementation |
| DNA | 9 | 5 | core、federation 各 API/implementation，JDBC driver 独立 |
| Storage | 6 | 3 | API、implementation、HTTP client |

具体规则：

- AI 的 core/catalog/knowledge/mcp/runtime/skill/agent/workflow：原 repository 与
  service 合入同领域 implementation。原 REST 源码、配置及测试移入
  `simplepoint-services/simplepoint-service-ai/src`，Java 包名不变。
- RBAC core、tenant：repository 与 service 合入 implementation；Router service
  更名为 implementation，repository 单独保留。三组 REST 源码移入
  `simplepoint-services/simplepoint-service-common/src`，Java 包名不变。
- Auditing logging、rate-limit：repository/service/rest 合入 implementation；
  redis：service/rest 合入 implementation。
- DNA core、federation：repository/service/rest 合入 implementation。
- Storage：repository/service/rest/s3 合入 implementation；S3 自动配置注册一起迁移。

## 服务装配与插件描述

两处独立消费边界保留：授权服务只需要 Router 仓储，不能因此加载 Common 的
Router 业务服务；Host 和其他服务只需要审计采集，不能因此加载审计管理接口。
因此 Router repository 和 logging monitor 保留独立，数量与最初估算略有不同。

AI 的管理接口不进入 Agent/Workflow Worker 的运行时类路径。RBAC 的管理接口
不进入授权服务。API 模块的名称、Java 包、实体、请求地址和数据库结构均保持原状。

RBAC core/router 的 service 插件 ID 保留。原两个 REST 插件描述归并为 Common
中的 `org.simplepoint.rbac.web`，统一声明 core/router/tenant 管理接口扫描路径，
并依赖原 service 插件 ID。若外部部署单独分发旧 REST 插件包，需要改用 Common
承载这些端点，不能继续加载旧 REST JAR。

## 验证

```bash
./gradlew check -Psimplepoint.frontend.skip=true
./gradlew verifyModuleBoundaries
./gradlew :simplepoint-services:simplepoint-service-ai:installDist -Psimplepoint.frontend.skip=true
cd simplepoint-react
pnpm i18n:check
pnpm typecheck
```

`verifyModuleBoundaries` 已接入根 `check`：检查 Worker/Authorization/Host 的运行时
JAR 中没有意外引入的管理端点或审计服务实现，并检查 MCP Gateway 不依赖领域
implementation 或数据库驱动。共享 Core 目前仍传递 Hibernate 依赖；这项既有耦合
未包含在此次模块合并中。

带 `simplepoint.frontend.skip=true` 的发行包用于后端验证，不包含构建生成的前端
页面。正式部署应省略该参数，重新构建完整镜像或发行包。
