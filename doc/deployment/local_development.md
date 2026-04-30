# 本地开发环境（Local Development）

## 1. 背景与目标

本文档描述**当前仓库已经具备的本地开发路径**，重点回答三个问题：

1. 本地最少要准备哪些中间件和工具。
2. 配置中心、密钥服务和业务服务在开发态是怎么接起来的。
3. 什么时候应该走“本地进程调试”，什么时候应该直接用 Docker Swarm。

如果你的目标是逐个服务调试 Java 进程、排查权限 / 菜单 / 登录链路，优先看本文；如果你的目标是把基础设施和主服务一口气全拉起来，直接看 `doc/deployment/docker_swarm_deployment.md`。

## 2. 当前仓库支持的两种本地方式

| 方式 | 适用场景 | 主要特点 |
| --- | --- | --- |
| 本地进程调试（推荐） | 需要单步调试 `authorization` / `common` / `host`，或逐项确认配置来源 | Java 服务用 `./gradlew ...:run` 启动；中间件可以本机安装，也可以部分用 Docker。 |
| Docker Swarm 一键拉起 | 想快速得到一个完整可访问环境，不想手工准备 Consul / Vault 初始化 | 基础设施、配置初始化和主服务一并拉起，见 `doc/deployment/docker_swarm_deployment.md`。 |

本文主要展开第一种。

## 3. 配置来源与依赖关系

### 3.1 Spring Profile 与配置中心

当前服务默认会先加载两类环境配置：

1. `application-consul.properties` / `application-consul-dev.properties`
2. `application-vault-dev.properties`（授权服务会额外依赖）

这意味着：

- `dev` 是当前默认本地 profile。
- Consul 是本地开发的主配置入口。
- Vault 是授权服务本地启动链路的一部分，而不是只在 Swarm / 生产环境里才出现。

### 3.2 `init_profile.sh` 做了什么

仓库根目录下的 `./scripts/shell/init_profile.sh` 会：

1. 等待本地 `Vault` 和 `Consul` 可访问；
2. 进入 `infrastructure/`；
3. 执行 `make apply ENV=dev`；
4. 通过 Terraform 把 `infrastructure/consul/config/simplepoint/config/**/*` 写入 Consul；
5. 在 Vault 里初始化 transit mount 与签名 key。

所以，本地开发不只是“把中间件端口开起来”就够了，还要把配置和密钥初始化进去。

## 4. 本地依赖清单

### 4.1 必备工具

| 组件 | 默认地址 / 版本期望 | 用途 |
| --- | --- | --- |
| JDK 17+ | 本机安装 | 运行 Gradle 和所有 Java 服务。 |
| Gradle Wrapper | `./gradlew` | 构建、测试、启动各服务。 |
| Consul CLI | `127.0.0.1:8500` | `start_dev_consul.sh` 使用本机 `consul` 命令启动 dev agent。 |
| Vault CLI | `127.0.0.1:8200` | `start_dev_vault.sh` 使用本机 `vault` 命令启动 dev server。 |
| Terraform 1.5+ | 本机安装 | `infrastructure/Makefile` 会调用 `terraform init` / `terraform apply`。 |
| curl / make | 本机安装 | `init_profile.sh` 用于可达性检查和 Terraform 包装。 |

### 4.2 中间件与默认地址

| 组件 | 默认地址 | 说明 |
| --- | --- | --- |
| PostgreSQL | `localhost:5432` | 默认开发数据源来自 Consul 配置。 |
| Redis | `localhost:6379` | 本地开发默认直接走 Spring 默认主机地址。 |
| RabbitMQ | `localhost:5672` / 管理台 `localhost:15672` | 本地开发默认也走 Spring 默认主机地址。 |
| Consul | `http://127.0.0.1:8500` | `application-consul-dev.properties` 指向本机。 |
| Vault | `http://127.0.0.1:8200` | `application-vault-dev.properties` 指向本机，token 默认是 `root`。 |

### 4.3 一个必须提前知道的前提

当前的 `docker/docker-compose.yaml` 已经把本地开发常用依赖补齐到了：

1. PostgreSQL 凭据与默认 Consul 开发配置对齐（`postgres/postgres`）。
2. Compose 内已包含 Consul 与 Vault。

但这**不等于**服务已经能直接启动。  
本地服务仍然依赖 `init_profile.sh` 把 `infrastructure/consul/config/simplepoint/config/**/*` 写入 Consul，并在 Vault 里准备授权服务所需的 transit key。

## 5. 推荐启动路径

### 5.1 启动本地依赖容器

当前仓库自带的 `docker/docker-compose.yaml` 可以直接拉起本地开发需要的基础依赖：

```bash
docker compose -f docker/docker-compose.yaml up -d
```

说明：

- `simple_point_mysql` 也在 compose 里，但默认开发配置并不使用它。
- 如果你已经有本地 PostgreSQL / Redis / RabbitMQ / Consul / Vault，也可以直接复用现成实例，只要最终地址、凭据和开发配置保持一致即可。

### 5.2 初始化配置中心与密钥服务

起完 compose 后，再执行：

```bash
./scripts/shell/init_profile.sh
```

这个脚本会：

1. 等待 `127.0.0.1:8500` 的 Consul 可访问；
2. 等待 `127.0.0.1:8200` 的 Vault 可访问；
3. 通过 Terraform 把开发配置写入 Consul；
4. 在 Vault 中准备授权服务依赖的 key。

如果你更习惯完全走本机 CLI 的方式，而不是 compose 起 Consul / Vault，那么再使用：

```bash
./scripts/shell/start_developer.sh
```

不要在 compose 已经占用 `8500` / `8200` 端口时，再执行 `start_dev_consul.sh` 或 `start_dev_vault.sh`，否则会发生端口冲突。

### 5.3 校验后端工程

在正式起服务前，先跑一次后端测试是当前仓库推荐的最小校验方式：

```bash
./gradlew test
```

### 5.4 按顺序启动核心服务

推荐开三个终端，分别执行：

```bash
./gradlew :simplepoint-services:simplepoint-service-authorization:run
./gradlew :simplepoint-services:simplepoint-service-common:run
./gradlew :simplepoint-services:simplepoint-service-host:run
```

推荐顺序仍然是：

1. `authorization`
2. `common`
3. `host`

补充说明：

- `authorization` 先起来，OIDC issuer 和登录入口就先就位了。
- `common` 在 `dev` 下会执行数据初始化；默认超级管理员账号也是在这里灌进去的。
- `host` 最后起来后，浏览器可以直接从 `http://127.0.0.1:8080` 进入完整登录链路。

### 5.5 可选：单独启动前端工作区

如果仓库里存在 `open-simplepoint-dashboard-react/`，并且你需要微前端热更新，可以额外启动前端工作区：

```bash
cd open-simplepoint-dashboard-react
corepack enable
corepack prepare pnpm@9 --activate
pnpm install --frozen-lockfile
pnpm dev:host
pnpm dev:common
```

常见后端联调场景里，这一步不是必选项；先把 `authorization`、`common`、`host` 三个后端服务跑通，再决定是否需要前端单独热更新，通常更稳妥。

## 6. 默认端口与入口

| 服务 / 组件 | 默认地址 |
| --- | --- |
| Host UI | `http://127.0.0.1:8080` |
| Authorization | `http://127.0.0.1:9000` |
| Common API | `http://127.0.0.1:7000` |
| Consul UI | `http://127.0.0.1:8500` |
| Vault UI | `http://127.0.0.1:8200/ui` |
| RabbitMQ 管理台 | `http://127.0.0.1:15672` |

在开发态配置里，host / common 的 OAuth2 issuer 和 redirect URI 都默认指向：

- issuer：`http://127.0.0.1:9000`
- redirect：`http://127.0.0.1:2555/login/oauth2/code/oidc`

## 7. 初始化数据与默认账号

`simplepoint-service-common` 的 `application-dev.properties` 会打开开发态数据初始化，并内置一个超级管理员账号：

| 项目 | 默认值 |
| --- | --- |
| 邮箱 | `simplepoint@mail.com` |
| 密码 | `123456` |
| 账号状态 | 已验证邮箱 |
| 权限 | `super-admin=true` |

首次验证时，建议按下面顺序做：

1. 确认 `common` 已经启动完成并完成初始化。
2. 打开 `http://127.0.0.1:8080`。
3. 走 host -> authorization 的登录跳转。
4. 用默认账号登录，确认菜单、路由和页面能正常加载。

## 8. 常见卡点

### 8.1 只起了中间件，没有执行 `init_profile.sh`

这种情况下，Consul 里没有开发配置，Vault 里也没有必要密钥；服务通常会在配置导入、JWT issuer 或密钥读取阶段失败。

### 8.2 Vault 没有起，但授权服务已经启动

当前授权服务的 `dev` profile 会主动加载 `application-vault-dev.properties`，所以 Vault 不可达时，授权服务并不是“少一个可选增强”，而是启动链路不完整。

### 8.3 PostgreSQL 能连端口，但用户名 / 密码不一致

虽然当前 `docker-compose.yaml` 已经默认使用 `postgres/postgres`，但如果你复用了旧容器、旧数据卷，或直接连接了另一套本地 PostgreSQL，仍然可能和 Consul 开发配置不一致，进而导致 JPA 服务认证失败。

### 8.4 只开了 `authorization`，还没开 `common`

登录入口虽然已经存在，但默认开发账号和很多菜单 / 权限相关初始化是在 `common` 侧完成的。首次验证时，请至少保证 `common` 已经完整启动一次。

## 9. 何时直接改用 Swarm

如果你更在意“尽快得到一个完整可访问环境”，而不是逐个服务排查问题，建议直接改走：

```bash
./scripts/shell/start_swarm.sh
```

Swarm 方案会把 PostgreSQL、Redis、RabbitMQ、Consul、Vault、bootstrap、authorization、common、host 一起编排起来，并自动完成配置初始化。完整说明见 `doc/deployment/docker_swarm_deployment.md`。

## 10. 关联文档

- 快速开始：`doc/quick_start.md`
- 服务拓扑：`doc/architecture/service_topology.md`
- Docker Swarm 部署：`doc/deployment/docker_swarm_deployment.md`
- 权限模型：`doc/permission/permission_model.md`
- 常见问题：`doc/troubleshooting/common_issues.md`
