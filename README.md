[![CI](https://github.com/simplepoint1024/open-simplepoint-dashboard/actions/workflows/ci.yml/badge.svg)](https://github.com/simplepoint1024/open-simplepoint-dashboard/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

# open-simplepoint-dashboard

`open-simplepoint-dashboard` 是一个面向企业后台与平台型应用的开源框架仓库，当前形态是：

- 一个显式登记叶子模块的 **Gradle 多模块后端工作区**
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
| `docker/` | Docker Compose、镜像与运行时资产 |
| `scripts/` | 本地开发、配置初始化与镜像构建脚本 |

## 运行时服务

| 服务 | 默认端口 | 主要职责 |
| --- | --- | --- |
| `simplepoint-service-host` | `8080` | WebFlux 网关、登录入口、前端壳应用静态资源 |
| `simplepoint-service-authorization` | `9000` | OAuth2 / OIDC 授权服务、登录页、Token / OIDC 端点 |
| `simplepoint-service-common` | `7000` | 默认业务聚合服务，承载菜单、权限、租户、字典、i18n 等能力 |
| `simplepoint-service-auditing` | 见服务配置 | 审计日志、限流规则、运维相关能力 |
| `simplepoint-service-dna` | 见服务配置 | 数据接入、JDBC 驱动、方言与 DNA 相关能力 |
| `simplepoint-service-ai` | `2888` | AI 模型接入、独立知识库、pgvector 混合检索与 AI 工作台 remote |
| `simplepoint-service-mcp-gateway` | `2890`（内部） | 独立 MCP 协议终止、远程 Server 连接与工具调用 |
| `simplepoint-service-tool-runtime-node` | `2891`（内部） | 独立 Go OCI MCP Server 节点运行时与容器沙箱 |
| `simplepoint-service-tool-egress-proxy` | `2892`（内部） | 独立的工作负载出站域名策略代理 |
| `simplepoint-service-tool-image-verifier` | `2893`（内部） | 独立的 Cosign、SBOM 与漏洞准入服务 |

如果你只想跑通最小链路，优先启动：`authorization`、`common`、`host`。

## 环境要求

- **JDK 21**（当前构建基线）
- **Gradle 8.12+**（无 Docker 本地开发）
- **Git**
- **本地开发中间件**：Consul、PostgreSQL、Redis
- **curl、psql**（本地初始化与诊断使用）
- **Docker / Docker Compose**（仅容器部署或受管 OCI Tool Runtime 需要）
- Node.js 24.19.0 与 pnpm 11.15.1 由 Gradle 自动下载；无需全局安装

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

Compose 项目名固定为 `open-simplepoint`，容器名统一生成为
`open-simplepoint-<service>-<replica>`；不使用固定 `container_name`，保留编排层的副本扩展能力。
需要在 Compose 下扩展带宿主机固定端口的服务时，还需先改为反向代理入口或调整端口发布策略。
每个 Java 服务都在自己的服务目录维护 `Dockerfile`，首次启动会构建各业务服务，并自动拉起
PostgreSQL、Redis、Consul、MinIO 与配置初始化容器。
PostgreSQL 与 Redis 默认只暴露在 compose 内部网络，不占用宿主机 `5432` / `6379` 端口；如果 Consul 的宿主机端口冲突，可通过 `SIMPLEPOINT_CONSUL_HTTP_PORT` 调整。
MinIO API 默认映射到 `19000`，管理控制台默认映射到 `19001`；初始化容器会自动创建 `simplepoint` Bucket，
平台首次启动时会把该连接写入“对象存储 → OSS 配置”，并在没有其他默认连接时设为系统默认 OSS。
7 个 Java 服务共享同一套多模块构建层：Gradle 一次解析完整任务图，BuildKit 复用
Gradle/pnpm 缓存，各运行时镜像只复制自己服务的 `installDist` 产物。
Tool Runtime 使用独立 Go 多阶段构建和 distroless nonroot 运行时。

也可以通过 Buildx Bake 并行构建全部平台镜像：

```bash
./scripts/shell/build_images.sh --load
```

发布到镜像仓库时会使用 OCI media types，并附带 SLSA provenance 与 SPDX SBOM：

```bash
SIMPLEPOINT_IMAGE_TAG=1.0.0 \
./scripts/shell/build_images.sh --push
```

需要让生产 Runtime 接受平台镜像时，应同时发布 Cosign 签名和签名后的 SPDX
attestation。`COSIGN_KEY` 未设置时使用 CI 的 keyless OIDC 身份：

```bash
SIMPLEPOINT_IMAGE_TAG=1.0.0 \
./scripts/shell/build_images.sh --push --sign
```

平台镜像默认发布到 `somesimpled/open-simplepoint-*`；私有仓库部署时可通过
`SIMPLEPOINT_IMAGE_REGISTRY` 覆盖仓库或命名空间前缀。

若需要离线 OCI image-layout 归档：

```bash
SIMPLEPOINT_IMAGE_TAG=1.0.0 \
./scripts/shell/build_images.sh --oci build/oci
```

镜像构建约定和变量详见 [`doc/deployment/container_images.md`](doc/deployment/container_images.md)。

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

### 4. 本机开发：无 Docker 快速启动

本机启动 Consul、PostgreSQL、Redis 后，只配置一个被 Git 忽略的环境文件：

```bash
mkdir -p .simplepoint
cp config/dev.env.example .simplepoint/dev.env
# 编辑 .simplepoint/dev.env
./dev doctor core
./dev init core
./dev up core
```

`./dev init` 会创建数据库、幂等写入并校验 Consul 配置，不依赖 Terraform 或 Consul
CLI；`./dev up` 会按顺序启动、等待健康检查并管理 PID 和日志。

包含 AI、MCP、Skill、Agent 与 Workflow Runtime 的组合：

```bash
./dev doctor ai
./dev init ai
./dev up ai
```

AI 组合要求 PostgreSQL 安装 pgvector。只调试后端时可使用 `./dev up ai --backend-only`。
详细说明见 [`doc/deployment/local_development.md`](doc/deployment/local_development.md)。

### 5. 本机开发：访问与管理

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

## 文档入口

| 文档 | 说明 |
| --- | --- |
| `doc/deployment/local_development.md` | 当前最准确的本地开发启动路径 |
| `doc/deployment/container_images.md` | 镜像命名、OCI 构建、SBOM 与发布约定 |
| `doc/architecture/service_topology.md` | 服务边界、职责与前后端映射 |
| `doc/architecture/project_structure_diagram.md` | 当前仓库目录与模块分层 |
| `doc/architecture/module_consolidation.md` | P0/P1 模块合并映射、Gradle 新路径与服务装配边界 |
| `doc/architecture/plugin_architecture.md` | 插件运行时与装配模型 |
| `doc/architecture/multi_tenant_model.md` | 多租户约定与上下文传递 |
| `doc/architecture/schema_driven_ui.md` | Schema 驱动 UI 的后端与前端约定 |
| `doc/architecture/frontend_microfrontend.md` | 微前端与模块联邦约定 |
| `doc/ai/model_api.md` | OpenAI Chat/Responses、Anthropic 兼容模型 API 与 API Key 管理 |
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
