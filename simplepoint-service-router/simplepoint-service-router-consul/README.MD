# simplepoint-service-router-consul

`simplepoint-service-router-consul` 是 Service Router 的 Consul 扩展，负责把本服务发布的 routed capability 自动写入
Consul 实例元数据，供 Consumer 在路由解析时做自动映射。

## 自动注册元数据

Provider 启动后，模块会将本服务能力写入元数据：

- `sp-router-capabilities`
  - 形如 `user.UserService:1.0`

- `sp-router-mappings`
  - 形如 `user.UserService:1.0=user-service,order.OrderService:1.0=order-service`

### 映射关系

`serviceName + version -> applicationName`

例如：

```
user.UserService:1.0 -> user-service
order.OrderService:1.0 -> order-service
```

其中：

- `serviceName` 来自 `@RoutedService.name`
- `version` 来自 `@RoutedService.version`
- `applicationName` 来自 `spring.application.name`

## 使用方式

在 provider 和 consumer 工程中添加依赖后，消费者端会在发现服务时优先按该映射查找 serviceId；
找不到映射时回退到配置项 `simplepoint.service-router.routes`。
