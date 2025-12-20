# 项目结构图（Project Structure Diagram）

本文件描述 open-simplepoint-dashboard 的源码分层与模块关系，便于快速定位代码与依赖。

## 1. 顶层模块概览
- `simplepoint-api/`：公共 API 契约与 DTO。
- `simplepoint-boot/`：Spring Boot 启动与 Web/WebFlux 通用 Starter。
- `simplepoint-core/`：核心领域与通用组件（基础设施、通用工具）。
- `simplepoint-data/`：数据访问层实现（JDBC/JPA/R2DBC/Calcite 等）。
- `simplepoint-data-extend/`：数据能力扩展（如 CP endpoint）。
- `simplepoint-plugin/`：插件框架（API、核心、Spring、WebMVC 适配）。
- `simplepoint-plugins/`：内置插件实现（如 i18n、OIDC、RBAC 等）。
- `simplepoint-cloud/`：云原生集成（Consul、负载均衡、OAuth 客户端、租户支持）。
- `simplepoint-security/`：安全相关组件（缓存、核心、OAuth2 客户端/资源服务器）。
- `simplepoint-services/`：业务/服务侧模块（若有子服务放置于此）。
- `simplepoint-examples/`：示例与演示工程（插件、AMQP RPC 等）。
- `plugins/`：外部插件入口（独立发布的插件包）。
- `docker/`：Docker 与 Consul 相关部署配置。
- `doc/`：文档（架构、设计、DB、API、权限等）。
- `buildSrc/`：版本库（`libs.versions.toml`）与构建脚本复用。

## 1.1 关键子模块清单（按目录）
- `simplepoint-boot/`
  - `simplepoint-boot-starter`：`@Boot` 组合注解、`Application.run(...)`、环境变量处理、Jackson 扩展预留。
  - `simplepoint-boot-starter-web`：MVC 场景启动器，聚合 Actuator、Web、Starter。
  - `simplepoint-boot-starter-webflux`：WebFlux 场景启动器。
- `simplepoint-plugin/`
  - `simplepoint-plugin-api`：插件契约、SPI 入口。
  - `simplepoint-plugin-core`：核心插件运行时。
  - `simplepoint-plugin-spring`：Spring 集成。
  - `simplepoint-plugin-webmvc`：WebMVC 适配。
- `simplepoint-plugins/`
  - `simplepoint-plugins-i18n`：多语言插件。
  - `simplepoint-plugins-oidc`：OIDC 相关插件。
  - `simplepoint-plugins-rbac`：RBAC 权限插件。
- `simplepoint-data/`
  - `simplepoint-data-jdbc` / `-jpa` / `-r2dbc`：多种数据访问实现。
  - `simplepoint-data-calcite`：Calcite 支持。
  - `simplepoint-data-json`：JSON 数据支持。
  - `simplepoint-data-amqp` / `-mongodb` / `-initializer` / `-cp` 等：消息、文档库、初始化、CP 扩展等。
- `simplepoint-data-extend/`
  - `simplepoint-data-cp-endpoint`：CP Endpoint 扩展。
- `simplepoint-cloud/`
  - `simplepoint-cloud-consul`：注册/配置中心。
  - `simplepoint-cloud-loadbalancer`：负载均衡。
  - `simplepoint-cloud-oauth-client-flux`：OAuth 客户端（WebFlux）。
  - `simplepoint-cloud-tenant`：多租户支持。
- `simplepoint-security/`
  - `simplepoint-security-core`：安全核心能力。
  - `simplepoint-security-cache`：安全缓存。
  - `simplepoint-security-oauth2-client` / `-resource`：OAuth2 客户端与资源服务器。
- `simplepoint-services/`：具体业务服务（按需扩展）。
- `simplepoint-examples/`
  - `simplepoint-plugin-examples`：插件示例。
  - `simplepoint-amqprpc-examples`：AMQP RPC 示例。
- `docker/`
  - `docker-compose.yaml`：本地/测试环境编排。
  - `consul/`：Consul 相关配置。

## 2. 模块关系示意（Mermaid）
```mermaid
graph LR
  subgraph API & Contracts
    API[simplepoint-api]
  end

  subgraph Boot & Core
    BOOT[simplepoint-boot]
    CORE[simplepoint-core]
  end

  subgraph Data Layer
    DATA[simplepoint-data]
    DATAEXT[simplepoint-data-extend]
  end

  subgraph Plugin Framework
    PLUGIN[simplepoint-plugin]
    PLUGINS[simplepoint-plugins]
    EXT[plugins (external)]
  end

  subgraph Cloud & Security
    CLOUD[simplepoint-cloud]
    SEC[simplepoint-security]
  end

  subgraph Services & Examples
    SRV[simplepoint-services]
    EX[simplepoint-examples]
  end

  API --> BOOT
  API --> CORE
  BOOT --> CORE
  CORE --> DATA
  CORE --> PLUGIN
  BOOT --> PLUGIN
  PLUGIN --> PLUGINS
  PLUGINS --> EXT

  CORE --> CLOUD
  BOOT --> CLOUD
  CORE --> SEC
  BOOT --> SEC

  DATA --> SRV
  DATAEXT --> SRV
  CLOUD --> SRV
  SEC --> SRV
  PLUGINS --> SRV

  SRV --> EX
```

## 3. 依赖层次与职责
- API 定义公共契约，减少服务间耦合。
- Core/Boot 提供基础设施、启动器与通用中间件封装。
- Data 层统一数据访问抽象；Data-Extend 扩展场景化能力。
- Plugin + Plugins 提供可插拔扩展与内置能力，支持 SPI 方式增强。
- Cloud/Security 聚焦云原生与安全基座（注册发现、负载均衡、OAuth2/OIDC、资源服务器）。
- Services 组合上述能力实现业务；Examples 用于演示最佳实践。

## 4. 研发与部署提示
- Starter 模块优先被业务/服务引用，确保与 API 契约兼容。
- 插件与内置插件分离管理，外部插件可独立发布于 `plugins/`。
- 数据与安全相关改动需关注跨模块兼容（API、Data、Security、Cloud）。
- 构建入口：根目录 `settings.gradle.kts` 动态 include 所有子项目，`buildSrc/libs.versions.toml` 管理版本。
- 需要排除子模块时可使用 `-PexcludeProjects` 参数，匹配 settings 脚本中的处理逻辑。

## 5. 关联文档
- 系统概览：`doc/architecture/system_overview.md`
- 组件设计：`doc/architecture/component_design.md`
- 部署架构：`doc/architecture/deployment_diagram.md`
- 权限模型：`doc/permission/`
- 数据库设计：`doc/database/`
- API 契约：`doc/api/`
- 部署指南：`doc/deployment/`
