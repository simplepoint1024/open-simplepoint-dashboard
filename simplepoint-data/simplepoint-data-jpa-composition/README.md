# 模块持久化装配

`simplepoint-data-jpa-composition` 将选定逻辑模块的 JPA、JDBC 和事务绑定到存储组，复用现有 Spring Data Repository 与平台 `BaseRepositoryImpl`。模块实体、仓储和事务不再由物理服务名称决定。

## 接入

新宿主在启用 `@EnablePlatformModules` 后添加 `@EnableModulePersistence`。不再同时使用 `@EnableRepository`、全局 `@EntityScan` 或额外的业务 `@EnableJpaRepositories`。

默认无需模块存储配置：所有已选模块使用 Spring Boot 提供的 `dataSource`，共用一个持久化单元与事务管理器。组件和仓储扫描来自 `ModulePlan` 的已选模块闭包，类路径上的其他模块不会被一起启用。

普通模块的实体直接从它的组件包发现。以下两种不能推导的事实由模块声明：

```java
@ModulePersistence(
    entityPackages = "example.shared.entities",
    sameStoreAs = "customer")
@PlatformModule(
    id = "customer-documents",
    packages = "example.documents",
    requires = @ModuleDependency(id = "customer"))
public final class CustomerDocumentsModule {}
```

- `entityPackages`：共享库中由本模块拥有的额外持久化类型包；只发现实体，不扫描其中组件。类型重复归属会阻止启动。
- `sameStoreAs`：必须共用存储与本地事务的模块；目标须同时声明为直接本地依赖。共库关系会传递，配置其中一个模块便会自动作用于整个组。冲突配置会阻止启动。
- JDBC/事件专用模块没有实体时，可以声明空的 `@ModulePersistence` 来显式要求存储。

当前额外实体归属包括 identity 的安全实体、i18n 的公共消息实体、bootstrap-registry 的初始化登记实体。当前强制共库依赖包括 storage → identity、ai-knowledge → ai-core、ai-mcp → ai-runtime、ai-catalog → ai-mcp、dna-federation → dna-core。解除这些约束需要先改造直接仓储访问及其事务语义。工作流部分读模型仍直接查询身份表和原生 Flowable 表，尚未完成独立存储适配；该缺口由总体计划 G22 跟踪。

## 分库配置

```yaml
simplepoint:
  persistence:
    modules:
      identity: security
      dna-core: dna
      dna-runtime: dna
    sources:
      security: securityDataSource
      dna: dnaDataSource
```

宿主提供 `securityDataSource` 和 `dnaDataSource` 两个标准 `DataSource` Bean；其余模块沿用 `dataSource`。数据源 URL、账号和秘密继续由部署环境提供，不放入模块声明。不同存储组不能绑定同一个数据源 Bean 或其别名。

模块只负责声明必要的逻辑关系；不用为每个 Repository、实体包、事务管理器或事件通道重复编写配置。不声明远程接口的本地依赖不会因此变成可远程调用。

## 注入与事务

- 在模块拥有的组件或配置工厂中通过构造参数注入 `EntityManager`、`EntityManagerFactory`、`DataSource`、`JdbcTemplate`/`JdbcOperations`、`PlatformTransactionManager`，自动获得本模块对应的实例。
- 资源选择依据静态模块归属，不依据请求参数、当前租户或线程变量，也不会因其他数据源被标记为 `@Primary` 而改变。
- 模块的 `@Transactional` 保留原有传播、只读和回滚规则，只绑定所属管理器。指向其他管理器的显式事务限定符会被拒绝。
- Spring Data 自己注册仓储、片段、查询和平台基础类，每组仓储明确指定所属 EntityManagerFactory 和事务管理器。
- 不通过 `@PersistenceContext`/`@PersistenceUnit`、`@Resource` 的名称注入或全局 `getBean` 重新选择模块资源。现有 AI 模型、身份及存储服务的六处遗留 JPA 字段注入已迁移为构造注入。共享基础设施若没有模块归属，应显式接收组资源，不能依赖全局主数据源来冒充业务归属。
- `ModuleStorageBindings` 是与框架无关的不可变 Bean 名称映射；事件适配器自动复用它。遗留事件存储覆盖项只有在解析到相同实例时才被接受。业务、inbox 与 outbox 的原子性仍由同一个本地数据库事务保证。

直接调用本地实现且共用存储时，普通事务传播可以加入外层事务。拆到独立存储后是各自的本地事务，不提供跨库原子提交。通过平台 RPC 路由调用时仍遵循既有本地/远程调用隔离，不会因共库就扩大远程契约事务。

## 表结构与现阶段范围

模块入口默认 `ddl-auto=validate`，不会因组合改变而默认创建或修改业务表。支持现有 `spring.jpa` / `spring.jpa.hibernate` 属性和 `HibernatePropertiesCustomizer`。测试可显式使用 `create-drop`。

本库当前提供实体/仓储发现、存储绑定和事务装配，版本化迁移由独立的 [simplepoint-data-migrations](../simplepoint-data-migrations/README.md) 提供，当前十九个模块已有 PostgreSQL 迁移声明，Redis 管理模块显式声明无 SQL 表；共享事件表已按存储自动加入同一迁移计划。**原生引擎贡献、已有数据库基线接管与安装器联动仍未完成**，也不提供自动数据搬迁。相关工作由总体计划 G06/G22 跟踪。原微服务入口尚未统一切换本适配器；引入此库不等于正式单体已经可运行。

## 验证

```bash
./gradlew \
  :simplepoint-data:simplepoint-data-jpa-composition:test \
  :simplepoint-platform:simplepoint-platform-composition-verification:test \
  :simplepoint-events:simplepoint-events-composition:test \
  -Psimplepoint.frontend.skip=true
```

测试覆盖：选中模块扫描、继承映射、共库回滚、分库隔离、主数据源不能覆盖模块绑定、检查型异常回滚、只读事务、JPA/JDBC/outbox 同事务、默认只校验表结构、全局扫描和错误事务限定符拒绝，以及事件存储自动绑定与错误覆盖拒绝。

实际领域产物检查涵盖全部实体归属和跨模块仓储依赖；用隔离 H2 数据库启动全部领域映射与仓储，分别验证一个共享存储组和三组独立存储。H2 证据不替代生产 PostgreSQL/MySQL、原微服务/单体/混合部署及完整 HTTP/浏览器验收。

组合验证模块另有真实 PostgreSQL 测试：`AuthenticationStoragePostgresTest` 联合调用
OIDC 的客户端/令牌/同意适配器与 identity 的 MFA 设置服务，验证共库及独立 schema
的数据库与事务归属。数据库映射和适配器调用都是真实实现；TOTP/Redis 控制使用替身，
不把这些结果当作完整登录或跨机器部署验收。
