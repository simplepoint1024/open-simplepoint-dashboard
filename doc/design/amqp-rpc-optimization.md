# AMQP RPC 性能与稳定性优化

## 背景

当前仓库内的 AMQP RPC 实现主要集中在 `simplepoint-data-amqp-rpc` 模块。现状可以工作，但在失败语义、回复队列设计、方法分发和消费端调优上仍有明显优化空间，容易在并发上升、服务滚动发布或异常场景下暴露稳定性问题。

## 当前问题

| 顺序 | 问题 | 影响 | 优化方向 |
| --- | --- | --- | --- |
| 1 | 服务端异常只记录日志，调用方依赖超时感知失败 | 上游请求卡住、失败语义不明确、线程占用时间过长 | 为 RPC 响应增加显式错误回包，调用端收到错误立即抛出异常 |
| 2 | 使用共享固定 reply queue | 多实例/多服务下回复路径耦合，扩容和排障成本高 | 改为 `RabbitTemplate` 管理 reply，移除固定 reply queue |
| 3 | 方法分发依赖运行时参数类型反射匹配 | `Map`/`Collection`/父类/接口/无参调用容易失败 | 使用声明参数类型构建精确分发表，并提供兼容回退匹配 |
| 4 | 请求路由使用 `HeadersExchange` | 仅按单一目标服务路由时不够轻量 | 改为 `DirectExchange + routingKey` |
| 5 | 每次请求动态扫描 bean 和反射解析方法 | 增加延迟和 CPU 消耗 | 启动后缓存 `@AmqpRemoteService` 分发表 |
| 6 | 缺少 RPC 专用 listener 并发与 prefetch 调优 | 吞吐与背压表现依赖默认值 | 增加 RPC 专用 listener container 配置 |
| 7 | 缺少 RPC 回归测试 | 线上风险难以及早暴露 | 增加无参、接口参数、异常传播、缓存分发等测试 |

## 执行顺序

1. 先修协议：补显式错误响应、无参调用、声明参数类型。
2. 再修路由：移除固定 reply queue，切到 direct exchange。
3. 再做分发优化：缓存服务和方法映射，避免每次请求反射扫描。
4. 最后做运行参数：缩短过长回复超时，增加 listener 并发和 prefetch 默认值。
5. 同步补回归测试，覆盖前面几项优化。

## 第一阶段完成情况

- [x] 协议已支持显式错误回包，调用端不再只能依赖超时感知失败。
- [x] 无参调用和声明参数类型分发已补齐。
- [x] 请求路由已从 `HeadersExchange` 切换为 `DirectExchange + routingKey`。
- [x] 共享固定 reply queue 已移除，改为由 `RabbitTemplate` 管理 reply。
- [x] `@AmqpRemoteService` 分发表已缓存，不再每次请求重新扫描。
- [x] RPC listener 已具备独立并发和 `prefetch` 默认值。
- [x] 已补无参、接口参数、错误回包和分发缓存测试。

## 第二阶段完成情况

- [x] 已修正 `MessageClient.sendAndReceive(...)` 重载，`replyTo` 与 `type` 不再写反，并已补回归测试。
- [x] 已增加基于 Micrometer 的 RPC 客户端/服务端请求计数与延迟指标。
- [x] 已补成功、远端错误、超时等关键指标的回归测试。

## 第三阶段完成情况

- [x] 已清理 AMQP 验证链路中暴露出来的 Spring 6 过时 API 告警点：移除了 `ApplicationContextProvider` 的 `org.springframework.lang.NonNull`，并将 `BaseServiceImpl` 的 `ObjectNode.fields()` 改为非过时迭代方式。
- [x] RPC 请求与响应已携带显式协议版本头 `sp-protocol-version`。
- [x] 服务端会兼容缺失版本头的旧请求，但会对不支持的协议版本返回显式错误回包。
- [x] 客户端会兼容缺失版本头的旧响应，但会拒绝不支持的协议版本响应。
- [x] 已补协议版本兼容/拒绝场景回归测试。

## 第四阶段完成情况

- [x] 已在共享 RabbitMQ 配置中开启 `publisher-returns`、`template.mandatory` 与保守的 `template.retry.*` 默认值，并同步到 swarm bootstrap 配置。
- [x] RPC 客户端现在会将 broker 返回的 unroutable publish 转换为显式 `RemoteInvocationException`，不再退化为普通 `RuntimeException`。
- [x] 已补 broker returned-message 场景回归测试。

## 第五阶段完成情况

- [x] 已在共享 RabbitMQ 配置中开启 `publisher-confirm-type=correlated`，并为 RPC 调用增加 `CorrelationData` publisher confirm 等待逻辑。
- [x] RPC 客户端现在会将 broker `nack`、confirm 超时、returned publish、传输异常分别转换为显式 `RemoteInvocationException`。
- [x] 已补 publisher `nack` 与无返回值调用 confirm 场景回归测试。
- [x] RPC 请求队列已补 DLX / DLQ 拓扑，异常拒绝的原始请求不会再直接丢失。
- [x] 服务端现在会区分恢复路径：可回包的业务/协议错误继续显式回包；无 `replyTo` 的失败请求、以及 reply/error reply 发布失败场景改为 reject + dead-letter。
- [x] 已补队列 DLQ 拓扑和 listener dead-letter 分流场景测试。

## 第六阶段完成情况

- [x] 不再尝试兼容遗留的 `headers` 类型 RPC exchange；运行时统一切换到新的 direct-only exchange 名称 `simplepoint.arpc.exchange.direct`。
- [x] 共享 Consul / swarm bootstrap 配置已同步为新的 exchange 名，避免与 broker 中旧的 `simplepoint.arpc.exchange` 冲突。
- [x] 相关 RPC 测试断言已同步到新的 exchange 名称，旧 routing 兼容代码未保留。

## 第七阶段完成情况

- [x] 已修复 correlated publisher confirm 引入的运行时回归：request/reply RPC 不再阻塞等待 broker confirm，因此不会再因为 confirm future 未完成而把已经成功拿到 reply 的调用误判为失败。
- [x] one-way RPC 现在仅在 broker confirms 确实开启时才等待 confirm；confirm 超时改为告警，不再直接中断调用。
- [x] 当 request/reply 本身超时时，若 confirm future 已经明确给出 `nack`，仍会优先按 publish 失败显式抛错。
- [x] 已补 request/reply 不阻塞 confirm、publisher confirms 关闭时 one-way 不阻塞、以及现有 nack/returned 场景回归测试。

## 后续可选优化项

| 顺序 | 问题 | 影响 | 优化方向 |
| --- | --- | --- | --- |
| 16 | confirm / DLQ 仍复用通用 `error` 指标结果 | 线上难以快速区分 broker nack、confirm 超时、dead-letter | 为 confirm nack、confirm timeout、dead-letter 增加更细粒度指标和告警 |
| 17 | 若未来引入服务端重试，业务侧幂等保护仍不足 | reply 发布失败或消费端崩溃后，重放消息可能带来重复副作用 | 在关键写操作链路评估幂等键、去重表或 outbox 模式，再考虑更积极的服务端重试 |

## 本次优化范围

- `simplepoint-data/simplepoint-data-amqp/simplepoint-data-amqp-rpc`
- `infrastructure/consul/config/simplepoint/config/**/application.properties`
- `docker/swarm/bootstrap/consul-config/simplepoint/config/**/application.properties`

## 验收关注点

- 无参远程调用可正常返回。
- `Map`/接口类型参数可按声明签名正确分发。
- 服务端异常不再依赖超时暴露，而是即时返回错误。
- 请求通过 direct exchange 按 routing key 路由。
- 不再声明共享固定 reply queue。
- RPC listener 具备专用并发和 prefetch 默认值。
- RPC 模块具备覆盖关键边界的单元测试。
- `MessageClient` 非 RPC 重载不会再写错 `replyTo` 与 `type`。
- RPC 客户端/服务端具备请求计数与延迟指标，客户端可区分 `success` / `remote_error` / `timeout` / `error`。
- RPC 请求/响应具备显式协议版本头，并兼容旧版缺失版本头的消息。
- RPC 默认启用 mandatory publishing、publisher returns 与保守模板重试，并能显式暴露 unroutable publish。
- RPC 调用链已接入 publisher confirm，且请求队列具备 DLX / DLQ 拓扑与明确的 dead-letter 分流策略。
- RPC 运行时 exchange 已迁移到新的 direct-only 名称 `simplepoint.arpc.exchange.direct`，不再复用旧的 headers exchange。
- request/reply RPC 不会再因为 correlated publisher confirm 超时而误报失败；one-way confirm 超时降级为告警。
