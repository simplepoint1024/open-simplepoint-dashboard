# 平台可靠事件

业务只依赖 `simplepoint-events-api`；服务宿主依赖 `simplepoint-events-rabbit`。`jdbc` 管理本地事务与恢复，`rabbit` 管理传输。现存 `simplepoint-data-amqp` 是未纳入当前构建的低层/旧 RPC 封装，不具备业务事务可靠投递语义；不将旧 RPC 自动切换为事件。

## 强制契约

- `EventPublisher.publish` 必须与业务修改使用同一个 DataSource 和事务管理器，并处于可写事务。事务回滚则 outbox 同时回滚。重复 eventId 不得描述不同事实。
- `EventHandler` 只执行本地事务效果。inbox 完成、业务状态和回执 outbox 一起提交；远程调用不能放在处理事务里。每种独立业务效果有稳定 subscription ID；增加订阅要先迁移拓扑再启用发布。
- Rabbit 接收端先持久化 inbox 再 ACK，业务处理由独立 worker 完成。ACK 和 publisher confirm 都不是业务完成。重要流程必须等待业务回执。
- 事件为显式 JSON 信封，最大 32 KiB（UTF-8），只允许对象载荷，不启用任意 Java 类型反序列化。包含 tenant、resource、generation、operation、correlation；禁止凭据与用户授权快照。载荷仍须由业务处理器验证。
- 每个服务 Rabbit 用户名必须等于 `simplepoint.events.service`。消息 user_id 由 broker 验证，接收端检查 receivedUserId、来源 exchange、route 和事件版本。生产凭据不得授予 impersonator 或其他服务的 exchange 写权限。
- 至少一次投递。确认不确定重发原身份；多实例使用条件抢占和租约 token，旧 worker 不能覆盖新状态。业务乱序由业务对象代次和操作回执处理。

## 启用

1. 用迁移账号在每个服务数据库执行一次 `simplepoint-events-jdbc/src/main/resources/META-INF/simplepoint/events/schema-v1.sql`。已有数据库按迁移清单核对，运行服务不自动创建表。
2. 运维账号预置所有 producer 的 durable direct exchange `sp.events.{producer}` 和消费者 quorum queue `sp.events.{service}.{subscriptionId}`，以 `{eventType}.v{schemaVersion}` 绑定。每个逻辑订阅独立队列；服务副本共享队列。设置 `x-delivery-limit=-1`，基础设施断连不按 broker delivery 次数静默丢弃；业务重试由数据库控制。
3. 生产服务账号 configure 权限为空，write 仅本服务 exchange，read 仅本服务订阅队列；服务启动不会声明拓扑。开发/验证可用 `declare-topology=true`，生产订阅不能依赖消费者首次启动才创建。
4. 设置以下配置，凭据由环境或秘密管理系统提供：

```properties
simplepoint.events.enabled=true
simplepoint.events.service=app
simplepoint.events.declare-topology=false
spring.rabbitmq.publisher-confirm-type=correlated
spring.rabbitmq.publisher-returns=true
spring.rabbitmq.username=${MQ_SERVICE_USER}
spring.rabbitmq.password=${MQ_SERVICE_PASSWORD}
spring.rabbitmq.host=${MQ_HOST}
spring.rabbitmq.virtual-host=/simplepoint
```

默认每方向一个 worker，每批最多 20 项、轮询间隔 1 秒；confirm 上限 10 秒、租约 60 秒；消费者 prefetch 8、并发 1–2。租约过期后可恢复，消费者业务事务锁住 inbox 行防止同一效果并行提交。不同业务对象可由不同服务副本处理；不能依赖全局消息顺序。

## 失败与运维

- 发布无路由、nack、确认超时保留 outbox，业务消费失败回滚本地事务。指数退避从 2 秒至 5 分钟，最多 12 次进入 DEAD；不悄悄删除。
- 无效来源/版本/格式/身份冲突进入 `sp_event_quarantine_v1`，持久化后 ACK，截取最多 32 KiB 原始字节。隔离数据仅运维可读，不能经普通业务接口返回；不自动重新解释或执行不可信消息。
- `JdbcEventStore.replay` 是受控运维 SPI，调用入口必须鉴权，要求操作者和原因，并同事务记录 `sp_event_replays_v1`；仅 DEAD 可重放，DONE 不可重置。原事件身份/代次保持不变，重放不能恢复已过期授权。
- 监控 `simplepoint.events.jobs{direction,state}` 的 PENDING/PROCESSING/DEAD，另监控 broker queue 深度、磁盘/内存水位和本地 quarantine。DEAD 和持续积压必须告警。
- DONE inbox 是去重证据；没有明确最大投递/人工重放窗口前不得清除。业务历史保留策略不等于事件去重保留策略。数据库与 broker 同时备份；回滚应用前先阻止新事件，再处理在途版本，不能删除未知版本消息。
- 单节点 quorum queue 可验证持久化及进程重启，不能抵御主机/磁盘丢失；生产高可用需至少三个独立节点及备份恢复演练。

## 验证

模块 JUnit 覆盖事务回滚、并发重复、租约恢复、旧 worker fencing、业务效果/完成事件原子提交、有界重试/审计重放及不可信消息隔离。设置 `SP_EVENT_TEST_POSTGRES_PASSWORD` 可在隔离 PostgreSQL `127.0.0.1:35432/event_verification` 运行存储用例；设置 `SP_EVENT_TEST_RABBIT_PASSWORD` 可在隔离 RabbitMQ `127.0.0.1:35672`、用户 `app` 运行真实确认/路由/离线消费/断开重投/身份验证用例。不要指向业务数据库。

可靠性依据：[RabbitMQ confirms](https://www.rabbitmq.com/docs/confirms)、[可靠性指南](https://www.rabbitmq.com/docs/reliability)。

## 服务就绪与消息通道健康

APP、Common、DNA 使用 `/actuator/health/readiness` 作为容器与 Consul 的业务就绪检查，包含 `readinessState,db`。`/actuator/health` 仍保留 RabbitMQ 健康并用于基础设施监控；MQ 故障时聚合健康 DOWN，但本地数据库可用的服务仍能可靠保存 outbox、读取业务数据和接受恢复操作，避免 RPC 提供者连锁退注册。部署如果设置了 `spring.cloud.consul.discovery.health-check-url`，也必须将其路径改成 readiness；它会覆盖 health-check-path。不要用就绪检查替代 MQ/DEAD/积压告警。

配置依据：[Spring Boot 健康分组与就绪探针](https://docs.spring.io/spring-boot/4.0/reference/actuator/endpoints.html)。
