# Docker Swarm 部署指南

本文档说明如何使用仓库内新增的 Swarm 资产，在本地或单机 Docker Swarm Manager 上一键启动 SimplePoint 所需的基础设施与核心服务，并在启动后直接访问系统。

---

## 1. 背景与目标

当前仓库原有的 `scripts/shell/start_developer.sh` 主要负责启动开发态的 Consul，并通过 `init_profile.sh` 初始化配置；它并不会把 `host`、`common`、`authorization` 这些应用服务一起拉起。

新增的 Swarm 方案目标是：

- 把 PostgreSQL、Redis、RabbitMQ、Consul 与核心业务服务统一编排；
- 自动完成 Consul KV 初始化；
- 在一次命令执行后，直接通过浏览器访问 Host UI 和 Authorization 服务；
- 尽量复用现有 Gradle 模块、Spring Profile 和仓库中的配置约定。

这套方案当前定位为：

- 本地开发环境；
- 单机或单 Manager 节点的快速验证环境；
- 不面向生产环境直接上线。

---

## 2. 整体架构说明

Swarm 部署会拉起以下两类组件：

- 基础设施：`postgres`、`redis`、`rabbitmq`、`consul`
- 应用服务：`bootstrap`、`authorization`、`common`、`auditing`、`dna`、
  `ai`、`mcp-gateway`、`tool-egress-proxy`、`tool-image-verifier`、
  `tool-runtime`、`host`

其中：

- `authorization` 提供 OAuth2/OIDC 授权、登录页与 JWKS；
- `common` 提供主业务 API、AMQP RPC 服务与微前端 Remote 资源；
- `host` 对外提供网关、前端 Shell 静态资源与登录入口。

```mermaid
flowchart LR
    User[Browser / Client] --> Host[host :8080]
    User --> Auth[authorization :9000]

    Host --> Consul[consul :8500]
    Host --> Common[common :7000]
    Host --> Auth

    Common --> Postgres[(postgres)]
    Common --> Redis[(redis)]
    Common --> RabbitMQ[(rabbitmq)]
    Common --> Consul

    Auth --> Postgres
    Auth --> Redis
    Auth --> Consul

    Bootstrap[bootstrap one-shot] --> Consul
```

---

## 3. 服务清单与职责

| 服务 | 是否对外暴露 | 默认端口 | 主要职责 |
| --- | --- | --- | --- |
| `postgres` | 否 | `5432` | 业务数据存储 |
| `redis` | 否 | `6379` | Session / Cache |
| `rabbitmq` | 管理界面对外 | `15672` | AMQP RPC 与消息通信 |
| `consul` | 是 | `8500` | 配置中心与服务发现 |
| `bootstrap` | 否 | - | 初始化 Consul KV |
| `authorization` | 是 | `9000` | OAuth2/OIDC 授权服务 |
| `common` | 否 | `7000` | 业务 API、Remote 资源、AMQP RPC |
| `ai` | 否 | `2888` | AI 工作台与 MCP/Runtime 控制面 |
| `mcp-gateway` | 否 | `2890` | 独立 MCP 协议网关 |
| `tool-egress-proxy` | 节点内网 | `2892` | OCI Workload 出站域名策略代理 |
| `tool-image-verifier` | 节点内网 | `2893` | Cosign、签名 SBOM 与漏洞准入 |
| `tool-runtime` | 节点内网 | `2891` | 独立 OCI MCP 工作负载节点 |
| `host` | 是 | `8080` | 网关、前端 Shell、登录入口 |

补充说明：

- `host` 已经内置主前端壳子，不需要再额外部署单独的 Nginx/Node 前端；
- `common` 不对外发布端口，但仍然是系统正常工作的关键服务；
- `authorization` 对外暴露是为了满足浏览器登录链路与 OIDC issuer 的可访问性。

---

## 4. 前置条件与环境要求

在执行 Swarm 部署前，请确保：

### 4.1 必备条件

- 已安装 Docker，且本机可正常执行 `docker build`、`docker stack deploy`；
- 当前节点是 Docker Swarm Manager，或者尚未初始化 Swarm；
- 可以访问镜像仓库，以便首次拉取基础镜像；
- 主机防火墙允许访问本文档列出的对外端口；
- 在仓库根目录执行脚本：

```bash
cd open-simplepoint-dashboard
```

### 4.2 推荐版本

- Docker 29+；
- Linux 主机优先；
- 可用网络出口，用于首次构建时拉取 `gradle`、`eclipse-temurin`、`alpine` 等基础镜像。

### 4.3 当前实现的边界

当前实现假设：

- 更偏向本地或单机 Manager 节点；
- 构建出的镜像默认只存在本机 Docker Engine；
- 若要扩展到多节点 Swarm，需要先把镜像推送到可被所有节点访问的镜像仓库。
- `tool-runtime` 以 global 服务运行，每个受信任节点使用 host mode 发布 `2891`，
  但只会调度到带 `simplepoint.runtime=true` 标签的节点。节点 Hostname 必须能被
  AI 控制面解析，并通过防火墙限制为控制面可访问。
- 每个 Runtime 节点必须预先部署固定版本的受限 Docker Socket Proxy；代理只允许
  `info/images/containers` 以及必要的创建、启动、停止、删除操作，不得发布到公网。
- 每个节点必须预置由同一 Runtime CA 签发的节点证书；AI 使用独立控制面证书。
  节点 URI SAN 必须与 Swarm `Node.ID` 精确一致，DNS SAN 必须匹配节点 Hostname。
- Image Verifier 使用独立
  `spiffe://open-simplepoint/tool-image-verifier` 服务身份，并只接受 Runtime
  节点证书。生产 Runtime 默认在拉取镜像前执行 fail-closed 供应链准入。
- 生产工作负载镜像必须固定 digest，并发布符合 Verifier 策略的 Cosign 签名和签名
  SPDX/CycloneDX SBOM；tag 只用于版本发现，不能作为实际执行引用。

---

## 5. 一键部署步骤

### 5.1 准备 Runtime 安全边界

Swarm 不保证普通副本服务与 global Runtime 的节点本地共置，因此 Stack 不内置
Socket Proxy。部署前应在每个节点以主机服务方式运行代理，并只允许 Runtime 节点
访问其 `2375` 端口。不要直接暴露 Docker TCP API。

默认 PKI 目录为 `/etc/simplepoint/runtime-pki`，每个节点均需包含：

```text
ai/identity.p12
ai/trust.p12
node/tls.crt
node/tls.key
node/ca.crt
verifier/tls.crt
verifier/tls.key
verifier/ca.crt
```

所有节点的 `ai/*` 来自同一 AI 控制面身份；每个节点的 `node/*` 必须单独签发，
`verifier/*` 是 Image Verifier 服务身份。生产环境应使用正式 CA/Secret 系统并设置
证书轮换。启动脚本会在部署前检查当前 Manager 的文件、Egress 签名密钥与必要环境
变量，多节点文件和代理状态仍由运维系统保证。

Runtime Secret 使用节点本地 tmpfs volume，平台只保存 AES-GCM 密文和引用；明文仅在
调度时经 mTLS 传输并以 `0400` 文件挂载。需要联网的 Workload 连接内部
`runtime-egress` overlay，只能经 Egress Proxy 使用策略允许的 HTTP/HTTPS 域名。

生产环境应至少准备两个专用 Runtime Worker，并显式打标签：

```bash
docker node update --label-add simplepoint.runtime=true runtime-worker-1
docker node update --label-add simplepoint.runtime=true runtime-worker-2
```

单机快速验证在没有任何已标记节点时，会自动给当前 Manager 添加该标签。生产部署可设置
`SIMPLEPOINT_SWARM_AUTO_LABEL_LOCAL_RUNTIME=false`，强制要求运维先完成节点标记。

```bash
export SIMPLEPOINT_RUNTIME_PKI_STORE_PASSWORD='replace-with-store-password'
export SIMPLEPOINT_RUNTIME_PKI_HOST_DIR=/etc/simplepoint/runtime-pki
export SIMPLEPOINT_TOOL_RUNTIME_DOCKER_HOST='tcp://runtime-socket-proxy.internal:2375'
```

### 5.2 启动 Stack

```bash
./scripts/shell/start_swarm.sh
```

脚本会自动：

1. 检查当前节点是否已加入 Swarm；
2. 如未初始化，则自动执行 `docker swarm init`；
3. 检测当前节点地址并作为对外访问地址；
4. 检查或初始化受信任 Runtime 节点标签；
5. 通过 Buildx Bake 并行构建全部 `somesimpled/open-simplepoint-*` 平台镜像；
6. 部署 `docker/swarm/stack.yml`；
7. 等待 bootstrap 服务初始化 Consul；
8. 启动应用服务。

### 5.3 指定对外访问地址

如果自动探测到的地址不是浏览器真正可访问的地址，请手动指定：

```bash
SIMPLEPOINT_PUBLIC_HOST=192.168.1.10 ./scripts/shell/start_swarm.sh
```

这个地址会被写入：

- OIDC issuer；
- OAuth2 redirect URI；
- 对外访问说明。

### 5.4 指定 Stack 名称

```bash
STACK_NAME=open-simplepoint-dev ./scripts/shell/start_swarm.sh
```

未指定时默认使用：

```bash
open-simplepoint
```

---

## 6. 启动脚本做了什么

`scripts/shell/start_swarm.sh` 的核心流程如下：

### 6.1 Swarm 初始化

- 如果本机尚未加入 Swarm，则自动执行初始化；
- 如果本机已经在 Swarm 中，但不是 Manager，则脚本会直接失败，避免在 Worker 节点误部署。
- Runtime global 服务只调度到 `simplepoint.runtime=true` 的节点；没有标签时，单机
  模式默认自动标记当前节点。

### 6.2 构建本地镜像

脚本通过根目录 `docker-bake.hcl` 并行构建：

- `somesimpled/open-simplepoint-postgres:swarm`
- `somesimpled/open-simplepoint-bootstrap:swarm`
- `somesimpled/open-simplepoint-authorization:swarm`
- `somesimpled/open-simplepoint-common:swarm`
- `somesimpled/open-simplepoint-auditing:swarm`
- `somesimpled/open-simplepoint-dna:swarm`
- `somesimpled/open-simplepoint-ai:swarm`
- `somesimpled/open-simplepoint-mcp-gateway:swarm`
- `somesimpled/open-simplepoint-tool-egress-proxy:swarm`
- `somesimpled/open-simplepoint-tool-image-verifier:swarm`
- `somesimpled/open-simplepoint-tool-runtime:swarm`
- `somesimpled/open-simplepoint-host:swarm`

每个应用镜像使用对应服务目录下的独立 `Dockerfile`，内部执行：

```bash
./gradlew <module>:installDist
```

再把 Gradle 生成的可执行分发目录拷贝到运行时镜像中。

### 6.3 部署 Stack

脚本会执行：

```bash
docker stack deploy -c docker/swarm/stack.yml <stack-name>
```

### 6.4 执行一次性 Bootstrap

`bootstrap` 容器基于 `docker/swarm/bootstrap/init-swarm.sh` 完成两件事：

1. 把 `docker/swarm/bootstrap/consul-config/` 下的配置写入 Consul；
2. 授权服务使用进程内 RSA JWK 签名。

初始化完成后，会在 Consul 中写入：

```text
simplepoint/bootstrap/status = ready
```

### 6.5 应用容器等待依赖就绪

应用服务的 entrypoint 会等待以下依赖就绪后再启动：

- PostgreSQL
- Redis
- RabbitMQ
- Consul
- `simplepoint/bootstrap/status`

这样可以减少“服务先启动但配置尚未写入”的竞态问题。

---

## 7. 配置项说明

### 7.1 启动脚本支持的环境变量

| 变量名 | 默认值 | 作用 |
| --- | --- | --- |
| `SIMPLEPOINT_PUBLIC_HOST` | 自动探测 | 浏览器实际访问的主机 IP 或域名 |
| `STACK_NAME` | `open-simplepoint` | Docker Stack 名称 |
| `SIMPLEPOINT_POSTGRES_IMAGE` | `somesimpled/open-simplepoint-postgres:swarm` | PostgreSQL/pgvector 镜像名 |
| `SIMPLEPOINT_BOOTSTRAP_IMAGE` | `somesimpled/open-simplepoint-bootstrap:swarm` | Bootstrap 镜像名 |
| `SIMPLEPOINT_AUTH_IMAGE` | `somesimpled/open-simplepoint-authorization:swarm` | Authorization 镜像名 |
| `SIMPLEPOINT_COMMON_IMAGE` | `somesimpled/open-simplepoint-common:swarm` | Common 镜像名 |
| `SIMPLEPOINT_AUDITING_IMAGE` | `somesimpled/open-simplepoint-auditing:swarm` | Auditing 镜像名 |
| `SIMPLEPOINT_DNA_IMAGE` | `somesimpled/open-simplepoint-dna:swarm` | DNA 镜像名 |
| `SIMPLEPOINT_AI_IMAGE` | `somesimpled/open-simplepoint-ai:swarm` | AI 镜像名 |
| `SIMPLEPOINT_MCP_GATEWAY_IMAGE` | `somesimpled/open-simplepoint-mcp-gateway:swarm` | MCP Gateway 镜像名 |
| `SIMPLEPOINT_TOOL_EGRESS_PROXY_IMAGE` | `somesimpled/open-simplepoint-tool-egress-proxy:swarm` | Egress Proxy 镜像名 |
| `SIMPLEPOINT_TOOL_IMAGE_VERIFIER_IMAGE` | `somesimpled/open-simplepoint-tool-image-verifier:swarm` | Image Verifier 镜像名 |
| `SIMPLEPOINT_TOOL_RUNTIME_IMAGE` | `somesimpled/open-simplepoint-tool-runtime:swarm` | Tool Runtime 镜像名 |
| `SIMPLEPOINT_HOST_IMAGE` | `somesimpled/open-simplepoint-host:swarm` | Host 镜像名 |
| `SIMPLEPOINT_RUNTIME_PKI_HOST_DIR` | `/etc/simplepoint/runtime-pki` | 各节点 Runtime mTLS 文件目录 |
| `SIMPLEPOINT_RUNTIME_PKI_STORE_PASSWORD` | 无 | AI PKCS12 身份库密码，必须显式设置 |
| `SIMPLEPOINT_TOOL_RUNTIME_DOCKER_HOST` | 无 | 每节点受限 Socket Proxy 地址，必须显式设置 |
| `SIMPLEPOINT_TOOL_EGRESS_SIGNING_KEY` | 无 | Runtime 与 Egress Proxy 共用的随机策略签名密钥 |
| `SIMPLEPOINT_TOOL_RUNTIME_SUPPLY_CHAIN_ENABLED` | `true` | 是否启用拉取前供应链准入 |
| `SIMPLEPOINT_SWARM_AUTO_LABEL_LOCAL_RUNTIME` | `true` | 无 Runtime 标签时是否自动标记当前节点 |
| `SIMPLEPOINT_AI_RUNTIME_MCP_SESSION_DIRECTORY_TTL` | `10m` | 托管 MCP 会话副本绑定 TTL |
| `SIMPLEPOINT_AI_RUNTIME_MCP_FAILURE_QUARANTINE` | `10s` | 连接故障副本的短期隔离时间 |
| `SIMPLEPOINT_TOOL_IMAGE_CERTIFICATE_IDENTITY_REGEXP` | GitHub Actions 工作流 | 允许的 Cosign keyless 证书身份 |
| `SIMPLEPOINT_TOOL_IMAGE_CERTIFICATE_OIDC_ISSUER` | GitHub Actions OIDC | 允许的 Cosign OIDC issuer |

### 7.2 Swarm Profile 相关配置

新增的 Profile 入口包括：

- `simplepoint-boot/simplepoint-boot-config-consul-starter/src/main/resources/application-consul-swarm.properties`

它们会把服务默认访问地址切换为：

- Consul：`consul:8500`

### 7.3 Consul 中初始化的配置

Bootstrap 会把以下目录下的 `.properties` 文件上传到 Consul：

```text
docker/swarm/bootstrap/consul-config/simplepoint/config/
```

其中包含：

- `application/application.properties`
- `application-swarm/application.properties`
- `host/application.properties`
- `host-swarm/application.properties`
- `common-swarm/application.properties`
- `authorization-swarm/application.properties`

其中 `__PUBLIC_HOST__` 占位符会在上传时替换为 `SIMPLEPOINT_PUBLIC_HOST`。

---

## 8. 访问入口与默认端口

部署完成后，默认可访问以下入口：

| 功能 | 地址 |
| --- | --- |
| Host UI / Gateway | `http://<public-host>:8080` |
| Authorization Server | `http://<public-host>:9000` |
| Consul UI | `http://<public-host>:8500` |
| RabbitMQ Management | `http://<public-host>:15672` |

说明：

- `common` 当前不对外发布端口，按设计通过网关、服务发现和内部网络访问；
- 如果浏览器登录跳转地址不正确，优先检查 `SIMPLEPOINT_PUBLIC_HOST`。

---

## 9. 部署后验证方法

### 9.1 查看 Stack 服务状态

```bash
docker stack services simplepoint
docker stack ps simplepoint
```

如果使用了自定义 `STACK_NAME`，请替换为对应名字。

### 9.2 查看 Bootstrap 日志

```bash
docker service logs simplepoint_bootstrap -f
```

成功时应能看到类似含义的日志：

- 等待 Consul 可用；
- 上传 Consul KV 配置；
- 输出 `SimplePoint bootstrap completed.`

### 9.3 检查 Consul 初始化状态

```bash
curl http://<public-host>:8500/v1/kv/simplepoint/bootstrap/status?raw
```

期望返回：

```text
ready
```

### 9.4 验证 OIDC 元数据

```bash
curl http://<public-host>:9000/.well-known/openid-configuration
```

如果能拿到 OpenID Provider 元数据，说明 `authorization` 已经能够对外提供 OIDC 能力。

### 9.5 验证 Host UI

在浏览器中打开：

```text
http://<public-host>:8080
```

如果页面能够打开，并且登录跳转指向 `http://<public-host>:9000`，说明外部地址配置基本正确。

### 9.6 Phase 2 多主机负载与故障验收

仓库提供 `scripts/shell/verify_phase2_runtime_failover.sh`。验收前需要：

- 至少两个 `READY` Runtime Worker，且均带 `simplepoint.runtime=true`；
- 一个 `activationReplicas >= 2`、副本分布在不同节点的托管 OCI MCP Pool；
- 一个发布到 `/mcp/{publication}` 的无副作用测试 Tool，例如 `echo`；
- 对应 Publication 的 OAuth Access Token。

先执行无破坏的多会话分配与亲和验证：

```bash
export PHASE2_MCP_URL='https://simplepoint.example.com/mcp/runtime-smoke'
export PHASE2_MCP_TOKEN='<access-token>'
export PHASE2_MCP_SERVER_ID='<managed-server-id>'
export PHASE2_MCP_SCOPE=SYSTEM
export PHASE2_MCP_TOOL_NAME=echo
export PHASE2_MCP_TOOL_ARGUMENTS='{"value":"phase2"}'
./scripts/shell/verify_phase2_runtime_failover.sh
```

脚本会创建多组标准 MCP `initialize` 会话，调用测试 Tool，验证：

- Redis 目录键不包含原始 Session ID；
- 同一会话保持 Workload、Lease 和 fence 亲和；
- 新会话至少分布到两个 READY Workload；
- 对应托管副本至少落在两个不同的 Runtime 节点。

默认 `PHASE2_REQUIRE_DISTINCT_NODES=true`，这是生产验收模式。单机开发机只能显式
执行无 drain 的多副本验证：

```bash
PHASE2_REQUIRE_DISTINCT_NODES=false \
PHASE2_ALLOW_NODE_DRAIN=false \
PHASE2_SESSION_COUNT=8 \
  ./scripts/shell/verify_phase2_runtime_failover.sh
```

该模式仍要求至少两个 READY Workload 并验证会话分布、亲和、Lease 和 fence，
但允许它们位于同一个 Runtime 节点。它不能作为多主机认证结果，也不能开启节点
drain；脚本会拒绝把单机结果误报为跨节点故障验收。

在维护窗口内显式允许故障注入：

```bash
PHASE2_ALLOW_NODE_DRAIN=true \
  ./scripts/shell/verify_phase2_runtime_failover.sh
```

故障模式只允许 drain 带 Runtime 标签的普通 Worker，拒绝 Manager。脚本验证原会话
重新绑定到另一节点上的 Workload，并通过 `trap` 恢复被 drain 的节点。测试 Tool
必须幂等且无业务副作用；平台本身不会自动重放失败的 Tool 调用。

---

## 10. 常见问题排查

### 10.1 脚本提示当前节点不是 Swarm Manager

原因：

- 当前 Docker 节点已经加入某个 Swarm，但不是 Manager。

处理方式：

- 在 Manager 节点执行脚本；
- 或退出当前 Swarm 后重新在本机初始化（如确有必要）。

### 10.2 应用服务一直等待启动

可先看日志：

```bash
docker service logs simplepoint_host -f
docker service logs simplepoint_common -f
docker service logs simplepoint_authorization -f
```

常见原因：

- `bootstrap` 尚未完成；
- PostgreSQL / Redis / RabbitMQ 尚未 ready；
- Consul  未成功启动。

### 10.3 浏览器跳转到了错误的登录地址

典型现象：

- 登录时跳到 `127.0.0.1:9000`；
- 或跳到一个宿主机不可访问的地址。

处理方式：

- 明确设置 `SIMPLEPOINT_PUBLIC_HOST`；
- 重新执行 `./scripts/shell/start_swarm.sh` 触发新一轮部署。

### 10.4 多节点模式下服务起不来

原因：

- 本方案默认构建本地镜像；
- 多节点 Swarm 上其他节点无法获取本机镜像。

处理方式：

- 先把镜像推到共享 Registry；
- 再通过 `SIMPLEPOINT_*_IMAGE` 指向可拉取的镜像地址。

### 10.5 Bootstrap 失败

请检查：

- `docker service logs simplepoint_bootstrap -f`
- `docker service logs simplepoint_consul -f`

重点确认：

- Consul `8500` 端口是否正常；
- `PUBLIC_HOST` 是否传入；
- 网络是否允许 Bootstrap 容器访问 Consul。

---

## 11. 升级、重部署与清理

### 11.1 重新部署

当 Swarm 配置、应用代码或镜像构建逻辑发生变化时，可直接重新执行：

```bash
./scripts/shell/start_swarm.sh
```

脚本会重新 build 镜像并再次执行 `docker stack deploy`。

### 11.2 移除 Stack

```bash
docker stack rm simplepoint
```

如果使用了自定义 `STACK_NAME`，请替换为对应值。

### 11.3 清理数据卷

Stack 删除后，如需彻底清理数据，请先查看相关卷：

```bash
docker volume ls | grep simplepoint
```

确认无误后再删除对应卷。

### 11.4 退出 Swarm

如果该 Swarm 只用于本地验证，且确认不再需要，可在清理完 Stack 后执行：

```bash
docker swarm leave --force
```

该操作会改变本机 Docker 的 Swarm 状态，请谨慎执行。

---

## 12. 安全性与已知限制

当前实现主要面向开发与验证场景，存在以下已知限制：

### 12.1 非生产级安全设置

- PostgreSQL 默认用户名密码为 `postgres/postgres`；
- RabbitMQ 默认用户名密码为 `simplepoint/simplepoint`；
- OAuth2 Client Secret 当前为 `secret`。

这些默认值都不适合生产环境。

### 12.2 对外端口默认开放

以下服务当前会直接发布到宿主机：

- `8080`
- `9000`
- `8500`
- `15672`

如果部署在共享网络环境中，请自行补充：

- 防火墙策略；
- 反向代理；
- TLS 终止；
- 访问控制。

### 12.3 单机 / 单 Manager 优先

当前脚本更偏向单机或单 Manager 节点使用。若扩展到多节点：

- 需要统一镜像分发方案；
- 需要更严格的服务约束、资源限制和调度策略；
- 需要进一步梳理域名、TLS 与 OIDC 外部地址。

### 12.4 生产化建议

如果后续要走正式环境，建议至少补齐：

- 私有镜像仓库；
- 非默认凭据与 Secret 管理；
- digest 固定、Cosign 签名、签名 SBOM 与漏洞门禁；
- Runtime、Verifier mTLS 证书和 Egress 策略密钥的自动轮换；
- 域名、HTTPS 与证书管理；
- 反向代理与统一入口；
- 更严格的健康检查与监控告警；
- 数据库与消息队列备份恢复方案。

---

## 附录：关键文件位置

| 文件 | 作用 |
| --- | --- |
| `scripts/shell/start_swarm.sh` | 一键启动 Swarm 的入口脚本 |
| `scripts/shell/build_images.sh` | Buildx Bake 本地、Registry 和 OCI 归档构建入口 |
| `scripts/shell/sign_images.sh` | 对已推送 digest 发布 Cosign 签名与 SPDX attestation |
| `docker-bake.hcl` | 全部平台镜像的并行构建定义 |
| `docker/swarm/stack.yml` | Swarm Stack 编排文件 |
| `simplepoint-services/*/Dockerfile` | 各可运行服务的独立镜像定义 |
| `docker/runtime/docker-entrypoint.sh` | 应用启动前等待依赖 |
| `docker/swarm/bootstrap/Dockerfile` | Bootstrap 镜像定义 |
| `docker/swarm/bootstrap/init-swarm.sh` | Consul 初始化脚本 |
| `docker/swarm/bootstrap/consul-config/` | Bootstrap 上传到 Consul 的配置 |
| `application-consul-swarm.properties` | Swarm 下 Consul 地址覆盖 |
