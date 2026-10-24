# 模块组合规划

本库只依赖 JDK 和 `simplepoint-remoting-core`。它读取模块声明、计算本地依赖闭包、验证完整部署的能力归属，并派生远程路由，不创建业务对象、不连接数据库或注册中心。

Spring 装配适配位于相邻的 `simplepoint-platform-composition-spring`。现有服务仍采用原启动方式；不能把规划验证通过视作完整服务组合可运行。

## 模块接入

在实现模块的配置类上添加 `@PlatformModule`，并将该类的完整名称写入资源 `META-INF/simplepoint/modules.imports`，每行一个类名。多个 JAR 的索引会合并，重复 ID 被拒绝。

```java
@PlatformModule(
    id = "orders",
    packages = "example.orders",
    requires = @ModuleDependency(id = "catalog"),
    provides = OrderOperations.class,
    consumes = PaymentOperations.class
)
@Configuration(proxyBeanMethods = false)
public class OrdersConfiguration {
}
```

- `requires`：必须同宿主装配的依赖，例如直接使用的本地领域基础设施；精确模块修订号默认 `1`。只有该关系参与依赖环检查。
- `provides` / `consumes`：填写现有 `@RemoteContract` 接口类型。能力名和版本从接口读取，不手写第二份映射。消费方可以远程调用，也允许两个模块双向消费接口。
- `packages`：该模块独占的组件包，不允许通配符；整个 catalog 的包归属不能重叠，防止选中父包时意外启动其他模块。
- 模块 ID 与契约修订号独立于宿主名称、实例数量、应用发行版本。不得将 repository、持久化资源或必须原子的内部操作声明为可远程分离的接口。

索引来自受信任的构建产物，不是用户上传的插件清单。发现元数据不初始化配置类。单个索引最多 64 KiB，总计最多 256 个模块。

模块可在 `META-INF/simplepoint/modules/{id}.properties` 提供非秘密的 `simplepoint.*` 默认配置。装配入口在扫描组件前加载选中闭包的默认值，优先级低于部署配置；重复资源、重复键、不同模块的冲突值和超限内容会被拒绝。不得在默认文件中写凭据或自动扩大授权。宿主端口、数据库地址等环境参数不能通过该默认文件设置。

例如身份模块默认启用自身声明的受保护资源管理接口。若部署显式关闭该接口，默认文件不会重新打开它；完整身份模块的能力校验会指出声明与实际提供方不一致。

## 已接入的领域装配

| 模块 | 必需本地依赖 / 当前边界 |
| --- | --- |
| identity | 身份、租户、资源授权共享仓储，作为一个边界 |
| i18n、oidc、personal | 各自封装 API、仓储和服务；HTTP 入口分别通过显式可选模块装配 |
| notification | personal；租户通知使用个人收件箱；通知 HTTP/SSE 是独立可选模块，不自动开放个人事项 HTTP |
| storage | identity；配额管理目前直接访问租户仓储和关联读模型 |
| dna-core | 数据源、驱动和方言基础设施 |
| dna-federation | dna-core |
| dna-runtime | dna-core、dna-federation；发布后必须失效联邦元数据缓存 |
| app-form、workflow | 通过公开接口互相调用，并消费 DNA、通知和身份能力 |
| audit-logging、audit-rate-limit、audit-redis | 三个独立领域模块 |
| ai-core、ai-runtime、ai-mcp、ai-catalog、ai-knowledge | runtime → core；mcp → core/runtime；catalog → core/runtime/mcp；knowledge → core |
| bootstrap-registry | 平台初始化登记的持久化提供者，已从 Common 启动工程移出 |

共 20 个领域模块，另有 `workflow-http`、`identity-http`、`oidc-http`、`i18n-http` 四个可选入口模块。多工程领域使用 `*-module` 组合包，宿主复用同一组依赖；必需本地依赖同时进入运行时依赖闭包。Common 已改用这些组合包，原启动方式暂时保留。

这些边界反映当前代码。`storage` 与身份、DNA Runtime 与 Federation 暂不支持独立分离；若要拆开，必须先替换跨边界的仓储/缓存依赖，不能只修改部署映射。Web 入口、数据源、事件身份与认证的组合适配仍在后续阶段。

DNA 用户选择和授权快照已改用身份模块的 `UserDirectory` 标量 DTO 查询；
Federation JDBC 登录已改为 `security.credentials:1`，凭据/MFA 验证在 identity 中执行，
DNA 每次服务器操作复核会话、租户与授权；生产安装和完整部署验证仍待完成。
其模块描述只表达当前已声明的依赖，不能据此声称已经通过分库、独立认证和真实部署验证。
资源/国际化启动注册已有显式模块贡献机制；identity、i18n、oidc、storage 的领域语言声明已接入。
bootstrap 登记执行器已接入 identity、i18n、storage；OIDC/通知字典已接入独立注册初始化器。
服务工程中的剩余菜单/语言资源、共享 locale 查询、字典旧登记接管及正式安装仍需完成（G21），
不能将这些领域声明的验证视为现有服务入口已完整迁移。

远程接口与领域管理接口分开：国际化仅导出查询/声明注册，资源仅导出声明注册；
实体 CRUD 使用本地管理接口，复用原实现。资源注册升级为 v3（强制持久部署权威），国际化注册的纯 DTO 协议保持 v2，
租户目录也升级为 v2，工作流和 DNA 使用有界目录契约，租户实体 CRUD 留在本地。
用户目录新增独立 v1 契约；旧用户认证实体接口已删除，改为独立凭据 DTO 契约。提供方及对应消费者需要协调升级。
具体迁移和未完成清单见组合计划 §3.3.1。

## 规划与路由

```java
ModuleCatalog catalog = ModuleCatalog.discover(applicationClassLoader);
ModulePlan local = catalog.plan(List.of("orders"));
DeploymentPlan deployment = catalog.deploy(Map.of(
    "business-service", List.of("orders"),
    "payment-service", List.of("payments")
));
Map<String, String> routes = deployment.routesFor("business-service");
```

`plan` 验证本地装配，保留尚待外置服务提供的能力；`deploy` 表示完整拓扑，必须包含全部被消费能力的提供方。上述示例要求 classpath 同时包含 orders、catalog 和 payments 的模块声明及接口。

完整拓扑中，每个逻辑模块只属于一个物理服务，每个能力/版本只有一个提供方。一个服务的多个副本使用同一个服务 ID。缺失能力、不同宿主重复装配同一模块、竞争提供方及 Java 契约不兼容均被拒绝。

`routesFor` 只返回该宿主需要的远程能力映射，可用于现有 ServiceRouter 的 capability/version → service ID 路由配置。将提供者合入同一宿主后，该项远程映射自动消失。这是配置规划 API，目前尚未接入整个平台的安装和 Consul 配置发布。

## Spring 入口

```java
@Configuration(proxyBeanMethods = false)
@EnablePlatformModules({"orders"})
public class BusinessModules {
}
```

宿主加入 `simplepoint-platform-composition-spring`。`simplepoint.modules.active` 可以覆盖注解的默认根模块，支持逗号列表或索引属性。没有选中任何模块时拒绝启动。

入口根据索引计算闭包，只扫描选中模块拥有的包，自动为其提供和消费的接口创建统一路由代理。启动结束前检查实际提供的能力是否与声明一致；缺失和未声明的能力均拒绝启动。本地与远程消费使用同一路由机制。新装配入口自动强制启用隔离本地调用；显式关闭时以
`MODULE_CALL_ISOLATION_REQUIRED` 拒绝启动。隔离传输使用相同 JSON 协议和不继承请求/事务的虚拟线程，
资源服务器适配验证模块自己的服务令牌。调用模块由标准 Spring 注入点和模块声明共同确定；实际部署迁移与端到端凭据链路验收仍未完成，不能据此直接部署完整聚合宿主。

### 调用模块与凭据

新组合入口为每个契约注册 prototype 客户端工厂。Spring 的构造参数、字段、`@Bean` 参数、`@Lazy` 和 `ObjectProvider` 注入点决定归属；
代理在创建时固定模块，之后从后台线程调用也不改变身份。无需给业务组件增加身份切换代码或类代理，final 业务组件也可使用。
归属必须匹配选中模块的独占包及配置类 ClassLoader，契约必须进入该模块的消费声明
（直接 `consumes` 或下述初始化器的声明）；提供自己的能力不自动意味着消费它。
未知归属、未声明消费和把客户端工厂替换为 singleton 均拒绝装配。不要通过 `ApplicationContext.getBean(某RPC接口)` 创建没有调用方的客户端。
共享基类中的字段若无法确定模块归属，应改为模块构造器或模块配置工厂注入，不能猜测一个宿主身份。

框架为选中消费者建立独立令牌缓存。默认客户端 ID 是 `simplepoint-module-{模块ID}`，环境可显式覆盖；同一宿主的不同模块不能配置同一个客户端 ID。
身份规则由纯规划 `ModuleClientPlan` 统一派生，完整部署规划也会拒绝跨宿主共用模块身份。
`ModuleResourcePlan.registrationClients(deployment, overrides)` 使用同一份身份规则生成资源拥有方到注册客户端的精确策略，
不会因更换宿主改变历史拥有方或凭据身份。该 API 不创建秘密、不授予其他领域权限，也尚未替代正式安装器的配置发布。
部署发布使用 `ModuleClientPlan.deploymentIdentities(deployment, overrides, operationId)` 派生本次客户端，
`ModuleResourcePlan.deploymentRegistrationClients` 同步派生拥有方策略。UUID 来自安装日志，重试复用；
下一次发布/回退必须使用新的操作、客户端及秘密。结果作为运行期 `.client-id` 覆盖输入，身份格式由同一纯规划校验。
identity V5 在目录事务内核对当前获准客户端，保留停用历史，兼容静态策略不能覆盖已管理世代。
这些 API 不会自动创建 OAuth 客户端、分发秘密或证明旧进程已经退出。见[部署世代设计](../../doc/design/resource_registration_deployments.md)。

当前安装侧需提供：

```properties
simplepoint.service-router.internal-auth.mode=oauth2
simplepoint.service-router.internal-auth.oauth2.token-uri=${SERVICE_TOKEN_URI}
simplepoint.modules.credentials.app-form.client-secret=${APP_FORM_CLIENT_SECRET}
simplepoint.modules.credentials.workflow.client-secret=${WORKFLOW_CLIENT_SECRET}
```

这是环境引用示例，不提供默认秘密。模块客户端仅请求 `service-router.invoke`；不会回退到宿主的 client-id/secret。
已有 OAuth 客户端可通过 `simplepoint.modules.credentials.{模块ID}.client-id` 对接，但提供方 caller allowlist 必须与实际客户端 ID 一致。
安装器自动创建客户端/安全传递秘密、派生跨宿主 caller policy 和配置发布仍属后续交付；不能据此认为当前全部业务角色策略已经迁移。

本地切线程前只捕获已经绑定的凭据提供者；令牌在工作线程中取得并验证，浏览器身份和请求上下文不传播。
嵌套调用改用被调用模块注入的客户端，完成或失败后恢复外层绑定；远程 HTTP 与本地隔离调用使用同一个凭据选择器。
底层采用 [Spring InjectionPoint](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/beans/factory/InjectionPoint.html) 的标准注入上下文，不依赖调用栈猜测或业务线程上的用户标记。

只需本地实现与远程消费、无需对外提供 RPC 的宿主，可使用 `simplepoint.service-router.provider.enabled=false`。该设置同时关闭 RPC HTTP 入口及 Consul 能力导出，不删除本地实现，也不禁用消费方。完整拓扑规划中的提供者必须实际启用导出；当前 `DeploymentPlan` 尚未校验宿主运行时属性，需要在安装配置集成阶段衔接。

宿主自身及基础设施配置仍需明确导入。**不要同时使用覆盖所有业务包的 `@Boot` / `@ComponentScan`**，也不要在模块配置中引入越界包扫描。数据库、事件和 HTTP 通过各自模块适配器接入，仍需完成正式安装与宿主配置；不能仅凭此入口直接合并当前生产服务。

## 模块启动贡献

共享注册器不是一个必须独占某台服务的业务领域，也不能隐式借用宿主账号。
模块通过 `@PlatformModule(initializers = {...})` 选择可复用的 `ModuleInitializer`，
初始化器用 `@ModuleInitialization(consumes = {...})` 声明所需 RPC。
规划器自动把这些接口加入所属模块的消费集合，参与缺失提供者、类型冲突、
路由和凭据检查。开发者不需要再手工抄写一份注册能力列表。

Spring 入口只创建选中模块使用的初始化器。初始化器可以注入本地基础设施，
RPC 则从 `ModuleInitializationContext.client` 获得：客户端固定绑定所属模块身份，
且只允许该初始化器自己声明的契约。即使同模块的另一个初始化器消费某个接口，
也不能因此借用它。此绑定只由装配框架从受信声明创建，不接受请求参数选择调用模块。
共享初始化器应保持无状态；所有模块相关内容通过传入的上下文获得。

先准备全部选中贡献，解析并验证必需声明。执行时先运行 `BOOTSTRAP` 基础数据阶段，
再运行默认的 `REGISTRATION` 声明阶段；同阶段按任务优先级排序，同优先级保持模块依赖和声明顺序。
普通初始化器继续返回一个动作；基础数据适配器通过 `prepareTasks` 保留既有贡献的跨模块 `order`。
必需资源缺失、重复、格式错误或远程登记失败
会使 Boot 启动失败。跨模块远程登记不是原子事务；初始化器必须支持部分成功后重启，
不能因超时自动重放不明确的写入。

资源放在 `META-INF/simplepoint/modules/{id}/`：

- 资源注册模块用 `@ModuleResourceOwner("历史资源拥有方")` 声明完整清单的稳定身份，
  并选择一个 `@ModuleInitialization(registersResources = true)` 初始化器，通常为
  `ResourceModuleInitializer`。缺少拥有方/注册器、多个注册器或多个模块共用拥有方在规划时失败。
- `ResourceModuleInitializer` 从当前模块路径读取 `resources.json`，格式为
  `{"module":"历史资源拥有方","resources":[...]}`；`module` 可省略，存在时必须与声明拥有方一致。
  模块 ID、注册拥有方及物理服务名允许不同，RPC 凭据始终绑定执行初始化的模块。
  树内资源代码不得重复，不能借 `pluginId` 改为其他拥有方。权限描述复用平台本地 catalog。
  初始化器在准备阶段通过框架上下文登记代码，同一宿主跨模块的重复代码会在所有远程动作之前失败。
  注册清单为完整快照，不能让不同宿主分别提交同一个拥有方的部分清单；`resourceHosts()` 检查全拓扑拥有方唯一性。
- `I18nModuleInitializer` 读取 `i18n.imports`，每行例如 `zh-CN/customers.json`，
  对应 `i18n/zh-CN/customers.json`。使用明确索引，不扫描其他模块的文件；
  保留已经注册、可能由管理员修改的消息，仅补充缺失项。
- 每个被读取的路径必须在 classpath 中恰有一份；缺失、重复、越界路径和超过 2 MiB
  的文件均拒绝。国际化索引至多 512 项。

目前 identity、i18n、oidc、storage 共 46 份语言声明已迁移。
旧资源/国际化自动配置在模块入口存在时退出；旧服务继续读取历史路径及新模块路径。
国际化拥有方的历史修复逻辑在模块模式下也只读取自己的声明。
workflow-http 已接入资源与翻译初始化，其历史资源拥有方为 workflow。
其余服务工程的资源仍需随 HTTP/前端贡献归属迁移，不能为了让启动通过恢复全 classpath 注册。
组合模式下资源范围目录只读取选中模块声明；缺失、重复、损坏或错误拥有方失败关闭。
注册提供方现已独立校验可信服务客户端与拥有方策略，并在写入前核对代码、路径及层级拥有方；
注册契约在旧宿主也强制隔离本地调用。本地身份域管理接口不再隐式导出为 RPC。
资源目录的数据库串行化与持久部署世代保护已接入；正式安装预检、凭据创建、配置发布和旧实例退役仍待完成，不能把声明规划或域内保障当作完整部署编排。

`PlatformBootstrapModuleInitializer` 使用稳定的 `module/{id}` 登记键及模块身份，
不因宿主改名而重新执行已完成的相同修订。未迁移的旧宿主记录与不确定的 RUNNING 状态会明确阻止重放。
迁移自动化、恢复协议和生产方言验收仍未完成，详见[初始化边界与状态说明](../simplepoint-platform-bootstrap/README.md)。

## 验证

- `:simplepoint-platform:simplepoint-platform-composition:check`：规划、异常诊断、索引发现、完整拓扑及依赖纯净性。
- `:simplepoint-platform:simplepoint-platform-composition-spring:check`：真实 Spring 容器中的选择装配、代理、未选模块隔离和声明/实现匹配。
- `:simplepoint-platform:simplepoint-platform-composition-verification:check`：使用真实领域产物核对三种部署计划、全部公开 RPC 契约/组件提供者，以及跨模块仓储引用的本地依赖。该检查不启动真实业务服务。
- `:simplepoint-service-router:simplepoint-service-router-core:test`：路由行为回归。
- `verifyModuleBoundaries`：现有服务的实现依赖边界。

完整交付范围见 [模块组合部署计划](../../doc/design/modular_service_composition_plan.md)。

## 模块持久化

已选模块的实体、Repository、JPA/JDBC 资源与事务由 `simplepoint-data-jpa-composition` 装配，默认共库，可显式分组。共库约束由 `@ModulePersistence` 声明并传递；事件存储复用 `ModuleStorageBindings`。详见[接入与验证范围](../../simplepoint-data/simplepoint-data-jpa-composition/README.md)。版本化迁移与正式宿主接入仍由总体计划跟踪。

## HTTP 适配贡献

可选 HTTP 模块使用 `@ModuleHttp(namespace = "/workflow")` 声明稳定公共前缀，并通过本地依赖引用领域实现。
规划阶段检查格式、重复和父子重叠；完整部署的 `httpRoutes()` 自动生成路径到宿主的映射。
Servlet 挂载、旧宿主兼容和当前验收边界见[模块 HTTP 适配器](../simplepoint-platform-http-servlet/README.md)。

共用历史前缀时可设置 `@ModuleHttp` 的 `routes` 元数据读取器，由控制器声明派生互斥子树。
纯规划层只依赖 `ModuleHttpRoutes` 接口，默认仍独占整个命名空间；MVC 读取器由 Servlet 适配模块提供，
不把 Spring 引入纯规划层。`namespaces()` 是模块挂载前缀，可重复；`routes()` 是模块实际拥有的公共子树，
`DeploymentPlan.httpRoutes()` 按后者产生到宿主的映射。读取器必须无运行期副作用，不构造领域组件或解析环境占位符。

## 字典元数据归属

选中模块可用 `@ModuleDictionaries` 指向领域拥有的纯声明工厂。
`ModuleDictionaryPlan.declarations(plan)` 返回不可变元数据，规划时检查模块归属及跨模块/跨宿主字典编码冲突。
当前 OIDC 和通知共 8 个字典、27 个选项已接入，Common 兼容初始化器使用同一声明。
`DictionaryModuleInitializer` 派生注册契约消费，身份提供方验证安装批准的模块客户端和精确内容，
整批字典与持久完成状态同事务提交。新世代保留相同内容的完成状态，避免换宿主重新启用或覆盖管理员设置。
旧成功登记和字典数据已有独立本地接管端口，保留历史行及管理员覆盖。
纯模块选择不会写入字典；正式安装器的批准/凭据发布及接管编排仍待完成。
参见[声明 API](../simplepoint-platform-dictionary-api/README.md)和[注册契约及安装边界](../simplepoint-platform-dictionary-registration/README.md)。
