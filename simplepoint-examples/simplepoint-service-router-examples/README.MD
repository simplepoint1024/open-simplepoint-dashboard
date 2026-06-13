# simplepoint-service-router 示例

该示例包含两个服务：

1. `simplepoint-service-router-example-provider`：提供 `GreetingService` 的本地实现
2. `simplepoint-service-router-example-consumer`：通过 `@EnableServiceRouter` 注入 `GreetingService` 进行远程调用

### 运行说明

1. 启动 Provider：

```bash
./gradlew :simplepoint-examples:simplepoint-service-router-examples:simplepoint-service-router-example-provider:run
```

2. 启动 Consumer：

```bash
./gradlew :simplepoint-examples:simplepoint-service-router-examples:simplepoint-service-router-example-consumer:run
```

3. 访问 Consumer：

```bash
curl "http://localhost:28181/example/greet?name=Alice"
```

该示例默认开启自动映射：`sample.GreetingService:1.0` 会自动映射到对应的服务名
（示例中映射到 `service-router-provider`），无需配置 `simplepoint.service-router.routes`。
