# simplepoint-boot

SimplePoint 的 Boot 基座，提供统一的 Spring Boot 启动注解与环境处理，以及 Web/WebFlux 启动器。

## 模块说明
- `simplepoint-boot-starter`：基础启动器，提供 `@Boot` 组合注解、`Application.run(...)` 入口、环境变量处理与 Jackson 扩展（空串序列化预留）。
- `simplepoint-boot-starter-web`：基于 Spring MVC 的 Web 启动器，聚合 Actuator、Web 等依赖。
- `simplepoint-boot-starter-webflux`：基于 Spring WebFlux 的启动器。

## 核心能力
- `@Boot`：封装 `@SpringBootApplication`，默认扫描 `org.simplepoint.**`，支持原生属性代理。
- `Application.run(...)`：预设自定义 `ClassLoader`，简化启动入口。
- `SimpleEnvironmentPostProcessor`：从 `CoreProperties` 读取并映射 `server.address/port`、`spring.application.name`、`spring.profiles.active`，支持 SPI 扩展 `EnvironmentConfiguration`，同时透传 `primitive.` 前缀的原始变量。
- Jackson 扩展：提供 `NullToEmptyStringSerializer` 与 `SimpleBeanSerializerModifier`，用于将 `String` 的 `null` 序列化为空串（默认未启用，可按需在模块中注册）。

## 快速使用
```java
import org.simplepoint.boot.starter.Application;
import org.simplepoint.boot.starter.Boot;

@Boot
public class DemoApplication {
  public static void main(String[] args) {
    Application.run(DemoApplication.class, args);
  }
}
```

## 环境变量映射示例
- `SIMPLEPOINT_ADDR` → `server.address`
- `SIMPLEPOINT_PORT` → `server.port`
- `SIMPLEPOINT_NAME` → `spring.application.name`
- `SIMPLEPOINT_ACTIVE` → `spring.profiles.active`
- `primitive.*` 前缀：去除前缀后直接注入到 Spring 配置

## 构建
在仓库根目录执行：
```shell
./gradlew :simplepoint-boot:build
```

更多设计细节见 `doc/architecture/`。

