# simplepoint-service-router-core 设计文档

## 当前实现与组合部署约束（2026-10-20）

下面原有章节包含早期设计建议，不代表所有列出的治理能力都已实现。组合部署以
[模块组合部署计划](../../doc/design/modular_service_composition_plan.md) 及当前代码为准。

- `@RemoteContract` 和 `@RoutedService` 接口均注册 primary 路由代理，本地实现不再绕过调用边界；
  `@Bean` 工厂声明和懒加载提供方同样被识别。默认本地优先，`consumer.local-first=false` 可显式选择远程。
- 同一宿主只能有一个能力名称/版本的本地提供方，重复时拒绝启动；注册表不允许外部修改。
- 远程选择依次使用 `routes[能力名:版本]`、`routes[能力名]`、实例声明的映射/能力。
  自动发现多个不同宿主提供同一能力时返回配置冲突，不按发现顺序选择；同一宿主服务的多副本仍正常负载均衡。
  没有能力声明时保留旧的能力名称前缀回退。显式选择的宿主不可用时不会偷偷切到另一宿主。
- 配置冲突不会触发业务 fallback 或调用重试。通过显式路由选择拥有方后才允许调用。
- 操作默认不自动重放。仅 `@RemoteOperation(idempotent = true)` 允许使用接口配置的重试次数，
  在传输中断、HTTP 5xx 或没有可用路由时重试；耗尽后才允许配置的 fallback。
  声明幂等需要业务依据，写操作必须先实现持久幂等；相同 trace ID 仅用于追踪，不是去重凭据。
  授权拒绝、提供者错误响应、协议/类型转换错误及本地提供者异常均不触发重试或降级。
  OAuth 客户端保留原有的单次 401 凭据刷新，只有取得不同的新令牌才重试认证。
- RPC 客户端及 MVC/WebFlux 入口统一使用独立 JSON codec，不修改应用全局 ObjectMapper。
  非定型参数/结果中的十进制数字保留 BigDecimal 精度；泛型按实际契约接口解析，
  Optional 结果、Java 时间类型和 Unicode 文本沿用明确的线协议。
  非预期提供者异常返回通用错误，不将内部异常消息暴露给调用端。
- 上述行为不等于任意业务已支持本地组合。服务身份、事务和事件的对等适配仍由组合部署计划跟踪。
  原装配中 DNA/Auditing 均携带审计实现的情况必须完成能力归属/路由兼容处理后才能部署新规则。

显式路由示例（物理宿主名仅在部署覆盖中出现）：

```properties
simplepoint.service-router.routes[dna.form-runtime:1]=business-aggregate
```

### 本地服务隔离

`@EnablePlatformModules` 自动启用并强制保持 `consumer.local-isolation=true`。
原 `@EnableServiceRouter` 宿主仍保留兼容默认值 `false`，必须迁移遗留实体契约后再切换。
隔离模式下本地调用走相同的请求/响应 codec 和错误解码器，并在不继承 ThreadLocal 的独立虚拟线程执行。
调用方的 Spring 事务、Servlet 请求、用户认证与后台授权上下文不会隐式进入提供方；提供方自己的事务注解仍有效。

每宿主默认最多 256 个本地调用树，嵌套深度最多 16，沿用 HTTP `read-timeout` 作为整棵调用树的时间预算。
必要时可显式调整 `consumer.local-max-concurrent` / `consumer.local-max-depth`。
嵌套调用共用额度，不因等待子调用耗尽工作池；超时或停止时请求中断，但不将中断解释为写入回滚。
只有整棵树的执行线程实际退出才归还额度。过期认证不会再开始业务方法；本地调用不自动重试或降级。
宿主关闭时停止接收新调用并取消等待；不合作的业务代码仍可能在中断后完成，必须依赖业务幂等和操作状态确认结果。

`LocalInvocationContext` 提供受信上下文扩展：`prepare` 只捕获已经确定的宿主/模块绑定，不安装身份；在隔离线程按顺序打开、逆序关闭。
资源服务器模块自动加入 OAuth 上下文：从选中的模块（旧宿主为宿主）的服务凭据提供者获取令牌，使用同一 JWT 解码器、受众/撤销策略验证，
仅接受带调用 scope 的 `client_credentials`，不复用浏览器令牌；认证失败最多刷新一次，发生在业务执行之前。
HTTP OAuth RPC 入口同样要求服务身份，仅拥有相同 scope 的用户令牌不再允许进入。
共享静态令牌的旧远程入口仍保持兼容，但不能用于平台的隔离本地调用。

**新模块装配已按注入点绑定逻辑调用模块，分别获取和缓存服务令牌；原服务仍使用原宿主凭据方式。**
模块客户端必须声明 `consumes`，不能根据请求参数或动态全局 Bean 查询选择身份。独立令牌的安装配置、领域 caller allowlist 及旧宿主迁移仍待完成。
不能因为隔离传输通过，就宣称 APP/DNA/Workflow 的聚合授权和全部领域事务已经验收。
`UsersService` 仍是唯一包含实体 CRUD 的遗留 RPC，不能直接用于新聚合宿主的完整认证流程。
用户目录已分离为纯 DTO；租户目录、资源/国际化注册已升级为窄 v2 契约，提供方和对应消费者需协调升级。
领域管理使用本地接口及原有实现，不通过 RPC 共享可变实体或事务。

## 早期设计背景

## 1. 背景

当前 Spring Cloud 工程采用 Gradle 多模块拆分，不同业务域通常包含：

* api：接口、DTO、请求响应模型
* service：业务服务实现
* repository：数据访问实现
* controller：HTTP 接口入口

随着业务增长，同一套业务能力需要在不同微服务中自由组合。有些服务希望直接引入本地实现，有些服务只引入 API，通过远程方式调用服务提供方。

因此需要一个统一的 Service 路由模块，实现：

> 本地有实现时走本地调用，本地没有实现时自动走远程调用。

模块名称：

```text
simplepoint-service-router-core
```

---

## 2. 设计目标

`simplepoint-service-router-core` 提供一套轻量级 Service 路由能力：

1. 统一 Service 接口声明
2. 自动发现本地实现
3. 自动生成远程代理
4. 优先调用本地 Bean
5. 本地不存在时自动远程调用
6. 基于 Consul 发现远程服务
7. 支持 Service 级能力注册
8. 支持超时、重试、熔断、降级
9. 对业务代码透明

业务代码：

```java
@Autowired
private UserService userService;
```

无需关心：

* 本地实现
* 远程实现

---

## 3. 核心原则

### 3.1 本地优先

调用顺序：

```text
Local Bean > Remote Proxy > Fail
```

### 3.2 API 只暴露稳定契约

api 模块允许：

* Service Interface
* DTO
* Request
* Response
* Enum
* ErrorCode

不允许：

* Entity
* Repository
* Mapper
* 数据库模型

### 3.3 不依赖包名作为协议

推荐：

```java
@RoutedService(
    name = "user.UserService",
    version = "1.0"
)
public interface UserService {

    @RoutedMethod("getUserById")
    UserDTO getUserById(String userId);
}
```

不推荐：

```text
包名 + 类名 + 方法名
```

---

## 4. 总体架构

```text
Consumer Service
        |
        | 注入 API Interface
        v
Service Router Proxy
        |
        +---- Local Bean Exists
        |            |
        |            v
        |      Local Invocation
        |
        +---- Remote Proxy
                     |
                     v
              Consul Discovery
                     |
                     v
               HTTP Invocation
                     |
                     v
              Provider Service
```

---

## 5. 模块划分

```text
simplepoint-service-router-core
simplepoint-service-router-provider
simplepoint-service-router-consumer
simplepoint-service-router-consul
simplepoint-service-router-spring-boot-starter
```

### Core

负责：

* 注解
* 元数据模型
* 路由逻辑
* 调用抽象
* 统一异常

### Provider

负责：

* Service 扫描
* Service 注册
* 统一远程调用入口
* 调用本地 Bean

### Consumer

负责：

* 自动代理生成
* Consul 查询
* HTTP 调用

### Consul

负责：

* 服务发现
* 能力元数据管理
* 本地缓存

### Starter

负责：

* 自动装配
* 自动配置
* Spring Boot 集成

---

## 6. Service 定义

```java
@RoutedService(
    name = "user.UserService",
    version = "1.0",
    timeout = 3000,
    retries = 1
)
public interface UserService {

    @RoutedMethod("getUserById")
    UserDTO getUserById(String userId);
}
```

实现：

```java
@Service
public class UserServiceImpl implements UserService {

    @Override
    public UserDTO getUserById(String userId) {
        return new UserDTO(userId, "Tom");
    }
}
```

---

## 7. Provider 启动流程

```text
1. 扫描 Spring Bean
2. 找到 RoutedService
3. 解析元数据
4. 生成能力清单
5. 注册 Consul
6. 发布能力元数据
```

能力清单：

```json
{
  "application": "user-service",
  "instanceId": "user-service-10.0.0.1-8080",
  "host": "10.0.0.1",
  "port": 8080,
  "capabilities": [
    {
      "name": "user.UserService",
      "version": "1.0",
      "methods": [
        {
          "name": "getUserById",
          "methodId": "getUserById"
        }
      ]
    }
  ]
}
```

---

## 8. Consumer 调用流程

```text
1. 注入 Service Interface
2. 判断是否存在本地实现
3. 存在则直接调用
4. 不存在则创建远程代理
5. 查询 Consul
6. 获取 Provider 实例
7. 负载均衡
8. HTTP 调用
9. 返回结果
```

---

## 9. 远程调用协议

统一入口：

```http
POST /_simplepoint/service-router/invoke
```

请求：

```json
{
  "service": "user.UserService",
  "version": "1.0",
  "method": "getUserById",
  "args": ["10001"],
  "traceId": "trace-001"
}
```

返回：

```json
{
  "success": true,
  "data": {
    "id": "10001",
    "name": "Tom"
  }
}
```

失败：

```json
{
  "success": false,
  "errorCode": "REMOTE_SERVICE_ERROR",
  "message": "invoke failed"
}
```

---

## 10. HTTP 客户端选型

推荐：

```text
RestClient
```

抽象：

```java
public interface RemoteInvoker {

    RemoteResponse invoke(RemoteRequest request);

}
```

默认实现：

```java
public class RestClientRemoteInvoker
        implements RemoteInvoker {

}
```

后续扩展：

```text
WebClientRemoteInvoker
OkHttpRemoteInvoker
GrpcRemoteInvoker
```

---

## 11. Consul 职责

Consul：

* 服务注册
* 健康检查
* 服务发现

Service Router：

* Service 注册
* Service 路由
* Service 代理
* Service 调用

Redis：

* 缓存
* 限流

不承担服务发现职责。

---

## 12. 负载均衡

默认：

```text
RoundRobin
```

后续支持：

* Random
* Weight
* SameZone
* LeastActive
* GrayRoute

---

## 13. 服务治理

支持：

```java
@RoutedService(
    timeout = 3000,
    retries = 1
)
```

治理能力：

* timeout
* retry
* fallback
* circuit breaker
* bulkhead
* rate limit

推荐：

```text
Resilience4j
```

---

## 14. Fallback

```java
@RoutedService(
    fallback = UserServiceFallback.class
)
```

```java
@Component
public class UserServiceFallback
        implements UserService {

}
```

触发：

* 超时
* 熔断
* 服务不存在
* 网络异常

---

## 15. Trace

透传：

* traceId
* spanId
* callerApplication
* targetService
* targetMethod

推荐：

```text
Micrometer Tracing
OpenTelemetry
```

日志：

```text
[ServiceRouter]
user.UserService#getUserById
-> user-service/10.0.0.1:8080
cost=35ms
status=success
```

---

## 16. 配置

```yaml
simplepoint:
  service-router:
    enabled: true

    provider:
      enabled: true
      expose-path: /_simplepoint/service-router/invoke

    consumer:
      enabled: true
      local-first: true

    discovery:
      type: consul
      cache-ttl: 10s

    http:
      client: rest-client
      connect-timeout: 1000ms
      read-timeout: 3000ms
```

---

## 17. 包结构

```text
com.simplepoint.service.router

├── annotation
├── metadata
├── invocation
├── registry
├── routing
├── proxy
├── loadbalance
├── resilience
├── tracing
└── exception
```

---

## 18. MVP 范围

一期实现：

* RoutedService
* RoutedMethod
* Provider 扫描
* Consumer 代理
* Consul 集成
* RestClient
* 统一 invoke
* timeout
* 日志

暂不实现：

* 灰度
* 熔断
* 权重
* gRPC
* Streaming

---

## 19. 后续规划

支持：

* gRPC
* WebClient
* 多协议
* 多租户
* 灰度发布
* 能力中心集成
* MCP Tool 注册
* MCP Skill 注册
* 服务拓扑图

---

## 20. 总结

simplepoint-service-router-core 的核心价值：

> 将本地模块调用与远程微服务调用统一为同一种 Service Interface 调用方式。

最终开发体验：

```java
@Autowired
private UserService userService;
```

无需关心：

```text
本地实现
还是
远程实现
```

框架自动完成：

```text
本地优先
↓
服务发现
↓
远程调用
↓
返回结果
```
