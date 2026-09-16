# 容器镜像构建规范

## 命名

平台自行构建的镜像统一使用 `somesimpled/open-simplepoint-<service>:<tag>`：

| Bake target | 默认本地镜像 |
| --- | --- |
| `postgres` | `somesimpled/open-simplepoint-postgres:local` |
| `bootstrap` | `somesimpled/open-simplepoint-bootstrap:local` |
| `authorization` | `somesimpled/open-simplepoint-authorization:local` |
| `common` | `somesimpled/open-simplepoint-common:local` |
| `auditing` | `somesimpled/open-simplepoint-auditing:local` |
| `dna` | `somesimpled/open-simplepoint-dna:local` |
| `ai` | `somesimpled/open-simplepoint-ai:local` |
| `mcp-gateway` | `somesimpled/open-simplepoint-mcp-gateway:local` |
| `tool-egress-proxy` | `somesimpled/open-simplepoint-tool-egress-proxy:local` |
| `tool-image-verifier` | `somesimpled/open-simplepoint-tool-image-verifier:local` |
| `tool-runtime` | `somesimpled/open-simplepoint-tool-runtime:local` |
| `runtime-pki` | `somesimpled/open-simplepoint-runtime-pki:local` |
| `host` | `somesimpled/open-simplepoint-host:local` |

根目录 Compose 项目名固定为 `open-simplepoint`，容器名由 Compose 生成为
`open-simplepoint-<service>-<replica>`。不要为服务增加 `container_name`，否则同一服务不能扩展多个副本；
带宿主机固定端口的服务还需配合反向代理或端口发布策略才能实际扩展。

## Dockerfile 归属

每个可运行 Java 服务在自己的目录维护 Dockerfile：

```text
simplepoint-services/simplepoint-service-authorization/Dockerfile
simplepoint-services/simplepoint-service-common/Dockerfile
simplepoint-services/simplepoint-service-auditing/Dockerfile
simplepoint-services/simplepoint-service-dna/Dockerfile
simplepoint-services/simplepoint-service-ai/Dockerfile
simplepoint-services/simplepoint-service-mcp-gateway/Dockerfile
simplepoint-services/simplepoint-service-tool-egress-proxy/Dockerfile
simplepoint-services/simplepoint-service-tool-image-verifier/Dockerfile
simplepoint-services/simplepoint-service-tool-runtime-node/Dockerfile
simplepoint-services/simplepoint-service-host/Dockerfile
docker/runtime-pki/Dockerfile
```

Java 服务共用的依赖等待入口位于 `docker/runtime/docker-entrypoint.sh`。
Tool Runtime、Egress Proxy 和 Image Verifier 是独立 Go 服务；Runtime 与 Proxy 使用
distroless nonroot 运行时，Verifier 使用 nonroot Alpine 并固定 Cosign、Trivy 供应链
镜像的版本与 digest。Runtime PKI 镜像只用于
Compose 开发环境生成短期证书；PostgreSQL/pgvector 和 Consul bootstrap 不是 Java
服务，Dockerfile 分别位于 `docker/postgres/` 和 `docker/bootstrap/`。

## 本地构建

构建全部镜像并加载到本地 Docker Engine：

```bash
./scripts/shell/build_images.sh --load
```

只构建指定目标：

```bash
./scripts/shell/build_images.sh --load authorization common host
```

构建脚本通过根目录 `docker-bake.hcl` 并行调度 BuildKit。7 个 Java 服务 Dockerfile
具有相同的多模块构建阶段，BuildKit 只执行一次完整 Gradle 任务图并复用 Gradle/pnpm
cache mount；各运行时阶段随后只复制本服务的 `installDist`，不会把其他服务或构建工具带入镜像。
三个 Tool 基础设施服务均在各自的 Go 多阶段构建中执行 `go test ./...` 并生成静态二进制。

## OCI 发布

推送模式默认使用 `somesimpled` 命名空间：

```bash
SIMPLEPOINT_IMAGE_TAG=1.0.0 \
./scripts/shell/build_images.sh --push
```

发布到私有仓库时，可设置
`SIMPLEPOINT_IMAGE_REGISTRY=registry.example.com/simplepoint` 覆盖默认前缀。

发布模式的镜像输出使用 OCI media types，并生成：

- `org.opencontainers.image.*` 标准镜像元数据；
- BuildKit SLSA provenance；
- SPDX 格式的软件物料清单（SBOM）。

Registry 必须支持 OCI image index/referrers，才能完整保存镜像随附的 attestations。
生产发布应在构建推送后追加 Cosign 签名及签名后的 SPDX attestation：

```bash
SIMPLEPOINT_IMAGE_TAG=1.0.0 \
./scripts/shell/build_images.sh --push --sign
```

`--sign` 只允许与 `--push` 一起使用。脚本先解析 Registry 返回的不可变 digest，再对
digest 签名、用 Trivy 生成 SPDX JSON 并通过 Cosign 发布 attestation。设置
`COSIGN_KEY` 时使用密钥签名；不设置时使用 CI/OIDC keyless 身份。生产 Verifier 的
证书身份正则和 OIDC issuer 必须与实际发布工作流一致。执行签名的 CI Runner 需要
预装 Cosign、Trivy 与 Docker Buildx，并具备对应仓库的推送权限。

离线交付可导出 OCI image-layout tar：

```bash
SIMPLEPOINT_IMAGE_TAG=1.0.0 \
./scripts/shell/build_images.sh --oci build/oci
```

## 构建变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SIMPLEPOINT_IMAGE_TAG` | `local` | 全部平台镜像的 tag |
| `SIMPLEPOINT_IMAGE_REGISTRY` | `somesimpled` | 镜像仓库或命名空间前缀 |
| `SIMPLEPOINT_IMAGE_VERSION` | 镜像 tag | OCI `version` |
| `SIMPLEPOINT_IMAGE_REVISION` | 当前 Git commit | OCI `revision` |
| `SIMPLEPOINT_IMAGE_SOURCE` | GitHub 仓库地址 | OCI `source` |

单个镜像名仍可通过 `SIMPLEPOINT_*_IMAGE` 变量覆盖，适合 Compose 或私有仓库部署。

## 运行时基线

- Java 运行时镜像采用 JRE 21；
- Java 服务使用固定 UID/GID `10001:10001` 非 root 运行；
- Tool Runtime 镜像使用 distroless nonroot；节点进程不挂载 Docker Socket，只通过
  节点内部按 OCI Index digest 固定的受限 Socket Proxy 访问允许的 Engine API；
- Runtime 在镜像检查和拉取前通过节点 mTLS 身份调用独立 Image Verifier；Verifier
  校验 Cosign 签名、签名 SBOM 和阻断级漏洞，并按 image digest 与策略摘要短时缓存结果；
- Egress Proxy 和 Image Verifier 均以非 root、只读根文件系统、Capability 全删除和
  `no-new-privileges` 运行，不承载平台业务代码；
- Compose 开发环境通过一次性 Runtime PKI 容器生成短期 mTLS 身份，生产证书必须由
  正式 CA 或 Secret 系统签发和轮换；
- 每个服务镜像声明自己的端口、健康检查、停止信号和启动脚本；
- 构建阶段合并多服务 Gradle 任务图，并使用 Gradle 与 pnpm cache mount；
- 运行时镜像只复制 `installDist` 产物，不包含源码和构建工具。
