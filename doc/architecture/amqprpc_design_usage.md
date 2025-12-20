# AMQP RPC 设计与使用

本文介绍 SimplePoint 的 AMQP RPC 能力、设计要点以及如何在示例项目中使用。

## 1. 设计概览
- 目标：基于消息队列实现跨服务的远程调用，降低耦合、提升异步解耦与可靠性，同时提供接近 HTTP RPC 的开发体验。
- 角色：
  - Provider：暴露 RPC 服务，消费请求队列，返回响应。
  - Consumer：调用 RPC 接口，通过 MQ 将请求发送到指定队列，等待响应。
  - Broker：AMQP 消息中间件（如 RabbitMQ），提供队列、路由和可靠传输。
- 主要组件：
  - 注解 `@AmqpRemoteService`：标记提供方实现类。
  - 注解 `@AmqpRemoteClient`：标记接口，生成远程代理供消费方注入调用。
  - 注解 `@EnableAmqpRemoteClients`：开启远程客户端扫描。
  - 模块 `simplepoint-data-amqp-rpc`：提供底层 RPC 框架与自动配置。

## 2. 工作流程
1) Provider 启动后扫描 `@AmqpRemoteService`，将实现与接口绑定到队列（由注解 `to` 或默认命名决定）。
2) Consumer 端通过 `@EnableAmqpRemoteClients` 扫描 `@AmqpRemoteClient` 接口，生成代理 Bean。
3) Consumer 代码调用接口方法 → 序列化请求 → 发送到对应队列。
4) Provider 监听队列，收到请求后调用本地实现，序列化返回值并应答。
5) Consumer 接收响应消息并反序列化为返回类型。

## 3. 示例结构（`simplepoint-examples/simplepoint-amqprpc-examples/`）
- `simplepoint-amqprpc-example-api/`：接口与 DTO。
  - `MessageExample`：示例消息 DTO。
  - `MessageExampleService`：接口，`@AmqpRemoteClient(to = "messages")`。
- `simplepoint-amqprpc-example-provider/`：服务提供方。
  - `ProviderApplication`：`@Boot` 启动。
  - `MessageExampleServiceImpl`：`@AmqpRemoteService` 实现，处理 echo 逻辑。
- `simplepoint-amqprpc-example-consumer/`：服务消费方。
  - `ConsumerApplication`：`@Boot` + `@EnableAmqpRemoteClients(basePackages = "org.simplepoint.amqprpc")`。
  - `MessageExampleController`：注入 `MessageExampleService`，通过 HTTP 触发 RPC 调用。

## 4. 关键代码片段
### 定义接口（API 模块）
```java
@AmqpRemoteClient(to = "messages")
public interface MessageExampleService {
  MessageExample echo(String message);
}
```

### Provider 实现
```java
@AmqpRemoteService
public class MessageExampleServiceImpl implements MessageExampleService {
  public MessageExample echo(String message) {
    MessageExample m = new MessageExample();
    m.setMessage("Echo: " + message);
    return m;
  }
}
```

### Consumer 启用与调用
```java
@Boot
@EnableAmqpRemoteClients(basePackages = "org.simplepoint.amqprpc")
public class ConsumerApplication { /* main(...) */ }
```
```java
@RestController
@RequestMapping("/message")
public class MessageExampleController {
  private final MessageExampleService messageExampleService;
  // constructor omitted
  @GetMapping
  public MessageExample message() {
    return messageExampleService.echo("Hello from Consumer!");
  }
}
```

## 5. 配置要点
- 依赖：
  - API 模块：`api(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-rpc"))`
  - Provider/Consumer：引入 `simplepoint-data-amqp-rpc`、Starter（Web/WebFlux）、Consul/LoadBalancer 视需要。
- 队列命名：由 `@AmqpRemoteClient(to = "<queue>")` 指定；Provider 默认监听同名队列。
- 序列化：基于框架默认序列化（遵循 DTO 的可序列化约束）。
- 包扫描：Consumer 需在 `@EnableAmqpRemoteClients` 中指定接口所在包。

## 6. 本地运行（示例）
1) 准备 AMQP Broker（如 RabbitMQ），并在配置中指向正确地址（参考 `simplepoint-data-amqp-rpc` 的默认配置项）。
2) 启动 Provider：
```shell
./gradlew :simplepoint-examples:simplepoint-amqprpc-examples:simplepoint-amqprpc-example-provider:bootRun
```
3) 启动 Consumer：
```shell
./gradlew :simplepoint-examples:simplepoint-amqprpc-examples:simplepoint-amqprpc-example-consumer:bootRun
```
4) 调用示例接口：
```shell
curl http://localhost:8080/message
```
预期返回：`{"message":"Echo: Hello from Consumer!"}`。

## 7. 最佳实践
- DTO 坚持可序列化（`Serializable`），避免循环引用。
- 接口幂等设计；必要时在 Provider 侧做幂等校验。
- 超时与重试：根据业务设置 RPC 超时、重试与死信策略；可结合 MQ 自带 DLQ。
- 监控与追踪：为请求/响应添加 CorrelationId，结合日志/Tracing 排查链路。
- 安全：必要时为队列配置鉴权、TLS 连接；敏感数据加密或脱敏。

## 8. 关联文档
- 核心架构：`doc/architecture/system_overview.md`
- 组件设计：`doc/architecture/component_design.md`
- 项目结构：`doc/architecture/project_structure_diagram.md`
- 部署指南：`doc/deployment/`
- 数据库/权限/API：`doc/database/`、`doc/permission/`、`doc/api/`

