[![CI](https://github.com/simplepoint1024/open-simplepoint-dashboard/actions/workflows/ci.yml/badge.svg)](https://github.com/simplepoint1024/open-simplepoint-dashboard/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

# open-simplepoint-dashboard

`open-simplepoint-dashboard` 是一个面向企业后台与平台型应用的开源框架仓库，当前形态是：

- 一个动态装配的 **Gradle 多模块后端工作区**
- 一个内嵌在仓库中的 **React + Nx + Module Federation 前端工作区**
- 一套围绕 **认证、授权、多租户、插件化、Schema 驱动 UI、数据接入** 组织起来的服务与基础设施约定

它不是单体后台模板，而是更偏“平台内核 + 可组合业务能力”的工程基座。

## 核心能力

- **认证与授权**：内置 OAuth2 / OIDC 授权服务、资源服务、安全上下文解析与 RBAC 能力
- **多租户模型**：租户上下文贯穿实体、仓储、服务与权限解析链路
- **插件化扩展**：支持按模块拆分能力，并通过插件运行时加载与组装
- **Schema 驱动后台**：后端基础服务会生成表单 / 动作元数据，前端按约定渲染页面
- **微前端集成**：`host` 壳应用按菜单与服务路由动态注册 `common` / `auditing` / `dna` / `ai` 等 remote
- **云原生依赖集成**：当前开发与部署链路围绕 PostgreSQL、Redis、Consul 组织，服务间远程调用通过 service-router + Consul 发现完成

## 仓库结构

| 路径 | 说明 |
| --- | --- |
| `simplepoint-api/` | 共享接口、DTO、基础契约 |
| `simplepoint-boot/` | Boot 注解、starter 与运行时启动支撑 |
| `simplepoint-core/` | 基础实体、控制器、服务、响应封装、通用工具 |
| `simplepoint-data/` | JPA、JDBC、JSON Schema 等数据层能力 |
| `simplepoint-plugin/` | 插件运行时与 Spring 集成 |
| `simplepoint-plugins/` | RBAC、OIDC、i18n、tenant、DNA 等业务能力模块 |
| `simplepoint-security/` | OAuth2 Server / Resource Server、安全域模型与鉴权基础设施 |
| `simplepoint-services/` | 可运行服务：`host`、`authorization`、`common`、`auditing`、`dna`、`ai` |
| `simplepoint-react/` | 前端 Nx 工作区，包含 host shell 与多个 remote |
| `doc/` | 架构、部署、权限、设计、排障文档 |
| `docker/` | 本地开发与 Swarm 部署资产 |
| `scripts/` | 本地开发、配置初始化、Swarm 启动等脚本 |

## 运行时服务

| 服务 | 默认端口 | 主要职责 |
| --- | --- | --- |
| `simplepoint-service-host` | `8080` | WebFlux 网关、登录入口、前端壳应用静态资源 |
| `simplepoint-service-authorization` | `9000` | OAuth2 / OIDC 授权服务、登录页、Token / OIDC 端点 |
| `simplepoint-service-common` | `7000` | 默认业务聚合服务，承载菜单、权限、租户、字典、i18n 等能力 |
| `simplepoint-service-auditing` | 见服务配置 | 审计日志、限流规则、运维相关能力 |
| `simplepoint-service-dna` | 见服务配置 | 数据接入、JDBC 驱动、方言与 DNA 相关能力 |
| `simplepoint-service-ai` | `2888` | AI 模型接入、独立知识库、pgvector 混合检索与 AI 工作台 remote |

如果你只想跑通最小链路，优先启动：`authorization`、`common`、`host`。

## 环境要求

- **JDK 21**（当前构建基线）
- **Git**
- **Docker / Docker Compose**（推荐，用于本地依赖编排）
- **Node.js 22.22+ + Corepack + pnpm 11.15.1**（仅当前端工作区开发或构建时需要）
- **Terraform / Consul CLI**（仅走本机开发脚本链路时需要）

## 后端快速开始

### 1. 获取代码

```bash
git clone https://github.com/simplepoint1024/open-simplepoint-dashboard.git
cd open-simplepoint-dashboard
```

### 2. Docker Compose 一键启动

推荐先用根目录 compose 拉起最小完整链路：

```bash
docker compose up --build
```

首次启动会在容器内构建各业务服务，并自动拉起 PostgreSQL、Redis、Consul、MinIO 与配置初始化容器。
PostgreSQL 与 Redis 默认只暴露在 compose 内部网络，不占用宿主机 `5432` / `6379` 端口；如果 Consul 的宿主机端口冲突，可通过 `SIMPLEPOINT_CONSUL_HTTP_PORT` 调整。
MinIO API 默认映射到 `19000`，管理控制台默认映射到 `19001`；初始化容器会自动创建 `simplepoint` Bucket，
平台首次启动时会把该连接写入“对象存储 → OSS 配置”，并在没有其他默认连接时设为系统默认 OSS。
如果本机已执行过对应服务的 `installDist`，compose 镜像会优先复用这些构建产物以加快启动；如需强制在容器内重建，可设置 `SIMPLEPOINT_USE_PREBUILT=false`。

启动完成后访问：

| 地址 | 说明 |
| --- | --- |
| `http://localhost:8080` | Host UI |
| `http://localhost:9000` | Authorization |
| `http://localhost:7000` | Common API |
| `http://localhost:8500` | Consul UI |
| `http://localhost:19001` | MinIO Console |

默认开发账号：

| 账号 | 邮箱 | 密码 | 初始化身份 |
| --- | --- | --- | --- |
| 系统管理员 | `simplepoint@mail.com` | `123456` | 平台管理员、默认组织所有者 |
| 租户管理员 | `manager@simplepoint.local` | `123456` | 默认组织租户管理员 |
| 普通成员 | `member@simplepoint.local` | `123456` | 默认组织普通成员 |

首次启动会同时创建“SimplePoint 示例组织”、组织标准版/个人基础版套餐、核心/对象存储/AI 应用和三类租户角色。
三个账号都拥有个人工作空间，租户资源会在各微服务注册资源目录时自动加入相应的默认应用。

如需修改默认登录账号，可在启动前设置环境变量：

```bash
SIMPLEPOINT_ADMIN_EMAIL=admin@example.com SIMPLEPOINT_ADMIN_PASSWORD=change-me \
SIMPLEPOINT_TENANT_MANAGER_PASSWORD=change-me \
SIMPLEPOINT_TENANT_MEMBER_PASSWORD=change-me docker compose up --build
```

对象存储的 Access Key / Secret Key 由系统管理员在“对象存储 → OSS 配置”中维护，
Secret Key 会使用 `SIMPLEPOINT_STORAGE_CREDENTIAL_ENCRYPTION_KEY` 加密后保存。生产环境必须显式设置并稳定保管该值；
直接更换主密钥会导致已有 OSS 凭证无法解密，需要先完成凭证迁移或重新录入。

```bash
SIMPLEPOINT_STORAGE_CREDENTIAL_ENCRYPTION_KEY='replace-with-a-long-random-secret' docker compose up --build
```

本地 MinIO 默认账号为 `simplepoint` / `simplepoint123`。可以通过
`SIMPLEPOINT_MINIO_ROOT_USER`、`SIMPLEPOINT_MINIO_ROOT_PASSWORD`、`SIMPLEPOINT_MINIO_BUCKET`、
`SIMPLEPOINT_MINIO_API_PORT` 和 `SIMPLEPOINT_MINIO_CONSOLE_PORT` 覆盖；生产环境必须修改默认凭据。

停止并清理容器：

```bash
docker compose down
```

如果需要同时清空 PostgreSQL、Redis 与 MinIO 数据：

```bash
docker compose down -v
```

### 3. 查看模块与基础校验

```bash
./gradlew projects
./gradlew checkstyleMain checkstyleTest
./gradlew test
```

CI 当前也是按 **Checkstyle + Backend Test + Frontend Typecheck/Build** 这条链路执行。

### 4. 本机开发：启动本地依赖

推荐直接使用仓库自带的 compose：

```bash
docker compose -f docker/docker-compose.yaml up -d
./scripts/shell/init_profile.sh
```

这一步会把开发态需要的基础依赖拉起来，并把开发配置写入 Consul。

### 5. 本机开发：按顺序启动核心服务

分别打开三个终端：

```bash
./gradlew :simplepoint-services:simplepoint-service-authorization:run
./gradlew :simplepoint-services:simplepoint-service-common:run
./gradlew :simplepoint-services:simplepoint-service-host:run
```

启动完成后，默认入口如下：

| 地址 | 说明 |
| --- | --- |
| `http://127.0.0.1:8080` | Host UI |
| `http://127.0.0.1:9000` | Authorization |
| `http://127.0.0.1:7000` | Common API |
| `http://127.0.0.1:8500` | Consul UI |

### 6. 开发态默认账号

`common` 服务在 `dev` 下会灌入系统管理员、租户管理员和普通成员三个账号；默认邮箱和密码见上面的 Docker Compose 章节。
平台启动贡献还会创建示例组织、套餐、应用、成员关系和角色授权，使平台、组织租户和个人工作空间都能直接验证。

仅用于本地开发验证，请不要带入生产环境。

## 前端工作区

前端不是独立仓库依赖，而是当前仓库下的 `simplepoint-react/` 子工作区。

### 安装与校验

```bash
cd simplepoint-react
corepack enable
corepack prepare pnpm@11.15.1 --activate
pnpm install --frozen-lockfile
pnpm typecheck
pnpm build
```

### 常用开发命令

```bash
pnpm dev:host
pnpm dev:common
pnpm dev:audit
pnpm dev:dna
pnpm dev:ai
```

### 构建并回填到后端静态资源

```bash
./scripts/shell/builder.sh
```

这个脚本会构建 `host`、`common`、`audit`、`dna` 四个前端应用，并把产物复制到对应服务的 `src/main/resources/static/` 目录下。

## 一键拉起完整环境

如果你的目标不是逐个服务调试，而是尽快得到一个完整可访问环境，可以直接使用：

```bash
./scripts/shell/start_swarm.sh
```

该脚本会在本地 / 单机 Swarm Manager 上编排 PostgreSQL、Redis、Consul、bootstrap、authorization、common、host。

## 文档入口

| 文档 | 说明 |
| --- | --- |
| `doc/deployment/local_development.md` | 当前最准确的本地开发启动路径 |
| `doc/deployment/docker_swarm_deployment.md` | Docker Swarm 一键部署说明 |
| `doc/architecture/service_topology.md` | 服务边界、职责与前后端映射 |
| `doc/architecture/project_structure_diagram.md` | 当前仓库目录与模块分层 |
| `doc/architecture/plugin_architecture.md` | 插件运行时与装配模型 |
| `doc/architecture/multi_tenant_model.md` | 多租户约定与上下文传递 |
| `doc/architecture/schema_driven_ui.md` | Schema 驱动 UI 的后端与前端约定 |
| `doc/architecture/frontend_microfrontend.md` | 微前端与模块联邦约定 |
| `doc/resource/` | 资源授权模型、授权上下文等说明 |
| `doc/troubleshooting/` | 常见问题与排障路径 |

`doc/quick_start.md` 正在持续完善；当前以 `local_development.md` 和 `service_topology.md` 作为更可靠的入口。

## 贡献

请先阅读 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

建议至少在提交前执行：

```bash
./gradlew test
./gradlew check
```

如果改动了前端，再补充执行：

```bash
cd simplepoint-react
pnpm typecheck
pnpm build
```

## 许可证

本项目采用 Apache 2.0 许可，详见 [`LICENSE`](LICENSE) 与 [`NOTICE.md`](NOTICE.md)。
