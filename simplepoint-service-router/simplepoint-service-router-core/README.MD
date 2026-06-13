# simplepoint-service-router-core 设计文档

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
