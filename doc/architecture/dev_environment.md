# 开发环境与规范

## 1. 环境准备
- 操作系统：Linux / macOS / Windows（推荐 Linux/macOS）。
- JDK：17（README 要求 JDK 17+）。
- 构建：Gradle（wrapper 已提供 `./gradlew`）。
- Node.js：如需前端/文档构建请准备（README 提及）。
- Git：版本控制，提交前请确保本地格式化与检查。
- 可选：Docker / Docker Compose（`docker/docker-compose.yaml` 用于本地依赖编排）。

### 基础安装检查
```shell
java -version
./gradlew -v
node -v   # 若需前端
```

## 2. 依赖与组件
- Spring Boot / Spring Web / WebFlux（按模块选择）。
- 数据访问：JDBC、JPA、R2DBC、Calcite、MongoDB、AMQP RPC 等模块按需引用。
- 云与配置：Consul、LoadBalancer（`simplepoint-cloud-*`）。
- 安全：OAuth2/OIDC 相关模块（`simplepoint-security-*`）。
- 插件：`simplepoint-plugin` 框架 + 内置插件 `simplepoint-plugins-*`。
- 工具与规范：Checkstyle（`checkstyle/google_checks.xml`），版本管理 `buildSrc/libs.versions.toml`。

## 3. 本地开发流程
1) 拉取代码并同步依赖：
```shell
git clone <repo>
cd open-simplepoint-dashboard
./gradlew projects
```
2) 编译与测试：
```shell
./gradlew build
./gradlew test
```
3) 选择模块运行示例（如 AMQP RPC Provider）：
```shell
./gradlew :simplepoint-examples:simplepoint-amqprpc-examples:simplepoint-amqprpc-example-provider:bootRun
```
4) 需要本地中间件时，可使用 Docker Compose：
```shell
cd docker
docker compose up -d
```

## 4. 代码规范
- 语言：Java/Kotlin，遵循 Google Checkstyle（见 `checkstyle/google_checks.xml`）。
- 格式化：提交前运行 Checkstyle/IDE 格式化，保持 import 有序、无未使用代码。
- 包扫描：`@Boot` 默认扫描 `org.simplepoint.**`，新增模块确保包路径符合约定。
- 命名与分层：接口/DTO 放 `simplepoint-api`；启动器/基座放 `simplepoint-boot`；数据访问放 `simplepoint-data`；插件在 `simplepoint-plugin`/`simplepoint-plugins`；示例在 `simplepoint-examples`。
- 配置：公共配置通过 `conf/` 或配置中心；环境变量优先使用 `SIMPLEPOINT_*` 或 `primitive.*` 透传。
- 安全：敏感信息不入库/不写日志；必要时加密/脱敏。

## 5. 提交与质量
- 提交前自检：`./gradlew check`（若定义）、`./gradlew test`。
- 代码评审：保持变更最小化，附上必要注释与测试。
- 版本管理：遵循语义化提交（建议），遵循仓库贡献指南 `CONTRIBUTING.md`。

## 6. 常用路径速查
- 架构与设计：`doc/architecture/`
- 设计细节：`doc/design/`
- 权限：`doc/permission/`
- 数据库：`doc/database/`
- API：`doc/api/`
- 部署：`doc/deployment/`
- Docker：`docker/`

如需新增依赖，请在对应模块的 `build.gradle.kts` 中声明，并同步更新 `buildSrc/libs.versions.toml`（如使用版本库）。

