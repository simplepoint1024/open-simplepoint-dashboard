# 通用 MCP Runtime 标准化与落地路线图

状态：执行中

最后更新：2026-08-05

## 1. 目标

将 Open SimplePoint 从依赖私有镜像约定的 OCI MCP Runtime，演进为由标准描述驱动的通用 MCP 运行平台：

```text
官方/社区 server.json
        │
        ▼
MCP Server Descriptor（上游不可变描述）
        │
        ▼
SimplePoint Runtime Profile（平台运行与安全补充）
        │
        ▼
Deployment Revision（不可变部署快照）
        │
        ▼
Runtime Workload Instance（实际 OCI 工作负载）
        │
        ▼
MCP Gateway → 授权调用方
```

最终必须满足：

- 支持 MCP 标准 `stdio` 和 Streamable HTTP；旧 HTTP+SSE 仅作为迁移兼容。
- 支持从官方 MCP Registry 的 `server.json` 导入 package 和 remote server。
- 支持发布者 OCI 镜像及 npm、PyPI package；npm、PyPI 后续通过受控制品流程转换为 OCI。
- 运行第三方服务时不要求包含 SimplePoint 私有标签。
- 配置、密钥、目录、网络、沙箱和会话均使用平台标准 Profile 描述。
- 调用方只能调用已经授权、发布且通过策略校验的 MCP Tool。
- 最终以 GitHub、Filesystem、Git、PostgreSQL、Docker、Playwright 六个社区 MCP 完成端到端验收。

参考标准：

- [MCP 2025-11-25 Transport 规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [官方 MCP Registry](https://github.com/modelcontextprotocol/registry)
- [Registry 发布快速入门](https://modelcontextprotocol.io/registry/quickstart)

## 2. 硬性边界

### 2.1 原版社区镜像

“原版镜像”在本计划中必须同时满足：

1. 镜像由项目发布者发布，或由 Docker MCP Catalog 从明确的上游仓库和 commit 构建发布。
2. SimplePoint 直接拉取该镜像，并在部署前将 tag 解析、锁定为 OCI manifest digest。
3. 不在 SimplePoint 仓库中复制第三方实现，不 fork，不重新编译，不构建二次镜像，不添加 wrapper layer。
4. Runtime Profile、密钥文件和工作目录均位于镜像外部；不能通过修改镜像获得兼容性。
5. 部署快照必须保存镜像引用、digest、上游仓库、上游版本、来源和准入报告。
6. 更新版本必须创建新的 Deployment Revision，不能使运行中的 revision 随 tag 漂移。

若上游只有 npm/PyPI 而没有发布原版 OCI，该服务不能冒充“原版 OCI”进入六服务验收清单。通用 npm/PyPI 转 OCI 流程仍会实现，但生成物必须标记为 `PLATFORM_BUILT`，不能标记为 `UPSTREAM_ORIGINAL`。

### 2.2 安全边界

- 不接受 shell 字符串，只接受 entrypoint、command、args 数组。
- 不允许普通用户配置任意 host bind mount、Docker capability、seccomp 或 bridge 网络。
- 不把明文密钥写入 Docker `Config.Env`、命令参数、镜像标签或日志。
- 默认禁止网络；HTTP/TCP 访问必须由 Profile 声明并经过策略审批。
- Runtime Node 继续只通过受限 Docker Socket Proxy 管理自己的工作负载。
- MCP 工作负载永远不能获得 Runtime Node 的 Docker socket。
- Filesystem 和 Git 只访问平台分配的 workspace，不允许以 `/`、用户 home 或仓库根目录作为不受控挂载。

## 3. 标准领域模型

### 3.1 MCP Server Descriptor

Descriptor 对应上游不可变事实，优先原样保存官方 `server.json`，并建立只读索引：

- schema URL、name、title、description、version；
- repository、website、publisher/source；
- packages：registry type、identifier、version、transport；
- package arguments、environment variables 及其 required/secret/schema 信息；
- remotes：transport、URL 和标准 header 描述；
- 原始 JSON、内容摘要、同步来源及发布时间。

Descriptor 不保存租户配置、真实密钥、资源限制、网络白名单或副本数。

### 3.2 SimplePoint Runtime Profile

Profile 是平台扩展，不修改上游 `server.json`：

```json
{
  "descriptorRef": "io.github.github/github-mcp-server@1.2.3",
  "artifact": {
    "source": "UPSTREAM_ORIGINAL",
    "type": "OCI",
    "image": "ghcr.io/github/github-mcp-server@sha256:..."
  },
  "transport": {
    "type": "STDIO"
  },
  "process": {
    "entrypoint": [],
    "command": [],
    "args": ["stdio"],
    "workingDirectory": "/workspace"
  },
  "configuration": [],
  "secrets": [],
  "storage": [],
  "network": {
    "mode": "HTTP_EGRESS",
    "allowlist": ["api.github.com:443"]
  },
  "session": {
    "mode": "DEDICATED",
    "maxSessions": 1
  },
  "sandbox": {
    "profile": "STRICT"
  }
}
```

Profile 的标准枚举：

| 维度 | 标准值 |
| --- | --- |
| Artifact | `OCI`、`NPM`、`PYPI`、`REMOTE` |
| 来源 | `UPSTREAM_ORIGINAL`、`CATALOG_ORIGINAL`、`PLATFORM_BUILT` |
| Transport | `STDIO`、`STREAMABLE_HTTP`、`SSE_LEGACY` |
| Secret target | `FILE`、`ENV_AT_EXEC` |
| Storage | `TMPFS`、`EPHEMERAL`、`WORKSPACE_RO`、`WORKSPACE_RW`、`PERSISTENT_VOLUME`、`OBJECT_SNAPSHOT` |
| Network | `NONE`、`HTTP_EGRESS`、`TCP_EGRESS`、`INTERNAL_SERVICE` |
| Session | `DEDICATED`、`POOL_AFFINE`、`SHARED_STATELESS` |
| Sandbox | `STRICT`、`DATA`、`BROWSER`、`WORKSPACE`、`LEGACY` |

### 3.3 Deployment Revision

每次部署创建不可变 revision，至少包含：

- Descriptor name/version/content hash；
- Runtime Profile 完整快照和 profile hash；
- 解析后的 OCI manifest digest；
- 配置引用和 Secret ID/Version 引用，但不保存密钥明文；
- 供应链准入、协议探测和兼容性报告；
- 创建人、作用域、发布时间和上一个 revision；
- rollout、rollback 和当前激活状态。

Pool 只引用当前 revision，不再承担通用 package 描述和全部部署配置。

### 3.4 Workload Instance

Workload 是 revision 的一次运行实例，只保存实际状态：

- revision、pool、node、container、lease、fencing token；
- 实际 digest、启动时间、退出状态、健康状态；
- MCP session 数量、session affinity 和最后活动时间；
- 观测到的协议版本及 tools/resources/prompts 能力摘要。

## 4. Runtime 标准能力

### 4.1 Process

控制面必须完整传递：

- entrypoint override；
- command、args；
- working directory；
- 普通 environment；
- image 原始 Entrypoint/Cmd 的保留或覆盖策略。

所有字段必须经过长度、数量、字符和占位符校验，禁止 `/bin/sh -c` 等绕过数组约束的隐式 shell。

### 4.2 Generic Launcher

提供由 Runtime 挂载的受信任 launcher，不写入第三方镜像。它负责：

1. 读取普通配置和 `0400` 密钥文件。
2. 将 `ENV_AT_EXEC` 密钥仅写入 MCP 子进程环境。
3. `exec` 原版镜像命令并正确转发 signal、退出码。
4. 保证 stdio 模式下 stdout 只包含 MCP JSON-RPC，日志进入 stderr。
5. 在启动失败时输出脱敏、结构化错误。

若镜像没有执行 launcher 所需的兼容基础，必须改用原镜像入口直接执行和 `FILE` secret；不能因此重建镜像。

### 4.3 Transport SPI

```text
McpTransportAdapter
├── StdioTransportAdapter
├── StreamableHttpTransportAdapter
└── LegacySseTransportAdapter
```

- `STDIO`：一行一个 UTF-8 JSON-RPC message，stderr 日志，支持初始化、通知、取消和超时。
- `STREAMABLE_HTTP`：Runtime 内部反向代理 container port/path，不发布宿主机端口。
- `SSE_LEGACY`：仅用于迁移，不能作为新部署默认值。
- 协议兼容性通过 `initialize` 握手和准入探测确定，不再信任 SimplePoint 私有镜像标签。

### 4.4 Secret Binding

- `FILE`：首选方式，服务直接读取密钥文件。
- `ENV_AT_EXEC`：兼容固定环境变量的原版服务；由 launcher 从文件注入子进程。
- 不支持 secret command argument，因为它会暴露在进程信息和审计记录中。
- OAuth access/refresh token 必须按主体和授权连接保存，不能作为全局 Server Definition 字段复用。
- token refresh 产生新 secret version，并通过安全重启/rollout 生效。

### 4.5 Storage

- 所有挂载来自平台管理的 workspace/volume ID，不能接收任意宿主路径。
- 默认只读；写权限必须由 Profile 单独声明。
- 为 `HOME`、XDG cache、浏览器 profile、下载目录提供有限额的 tmpfs/ephemeral volume。
- Filesystem 和 Git 的允许目录必须与实际 mount target 一致，并进入审计记录。

### 4.6 Network

- `NONE`：默认。
- `HTTP_EGRESS`：继续使用签名 DNS allowlist 和 HTTP/HTTPS proxy。
- `TCP_EGRESS`：增加 host/IP、port、TLS 和私网策略，服务 PostgreSQL 等 TCP MCP。
- `INTERNAL_SERVICE`：只允许访问经过 Consul 解析和策略审批的服务实例。
- 禁止把 `bridge` 等同于任意网络访问。

### 4.7 Sandbox Profile

| Profile | 主要用途 | 核心差异 |
| --- | --- | --- |
| `STRICT` | GitHub、Docker Hub、纯 API | 只读根目录、无额外 mount、HTTP allowlist |
| `WORKSPACE` | Filesystem、Git | 受控 workspace、路径配额、读写策略 |
| `DATA` | PostgreSQL | 受控 TCP egress、只读数据库身份优先 |
| `BROWSER` | Playwright | 有界 `/dev/shm`、浏览器 seccomp、ephemeral profile |
| `LEGACY` | 不兼容服务 | 仅管理员审批，不能成为租户默认 |

## 5. GitHub OAuth2 对接设计

GitHub 原版 local MCP 支持通过 `GITHUB_PERSONAL_ACCESS_TOKEN` 使用 access token。平台负责 OAuth 授权，原版镜像不修改：

```text
用户点击“连接 GitHub”
        │
        ▼
SimplePoint 发起 GitHub Authorization Code + PKCE
        │
        ▼
GitHub callback → 校验 state/PKCE → 交换 access token
        │
        ▼
按 user/tenant/serverConnection 加密保存 token
        │
        ▼
Secret Broker 生成短期 0400 文件
        │
        ▼
Launcher ENV_AT_EXEC → GITHUB_PERSONAL_ACCESS_TOKEN
        │
        ▼
ghcr.io/github/github-mcp-server 原版进程
```

实现要求：

- 复用平台已有 OAuth discovery、Authorization Code + PKCE、token exchange 和 refresh 基础设施，但新增“Provider OAuth Credential”，不能把远程 MCP OAuth 与本地 package 凭据混为一个实体。
- GitHub local stdio MCP 使用用户级 `DEDICATED` session/workload，避免不同用户 token 在共享进程中混用。
- scope 使用最小授权；默认启用 GitHub MCP read-only/lockdown，写操作需要显式策略和 调用授权。
- token 不进入 Pool、Revision JSON、Docker Env、日志或前端响应。
- 断开 GitHub 后撤销或删除 token，并终止关联 workload/session。

上游依据：

- [GitHub 官方 MCP Server](https://github.com/github/github-mcp-server)
- [GitHub MCP host OAuth 集成说明](https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md)

## 6. 六个社区 MCP 验收基线

下面的 tag 只表示上游位置。实际部署必须解析为 digest，文档和代码不能长期固定 `latest`。

| 服务 | 验收镜像 | 来源规则 | 关键 Runtime 能力 | 工具验收动作 |
| --- | --- | --- | --- | --- |
| GitHub MCP | `ghcr.io/github/github-mcp-server` | GitHub 发布者原版 | stdio、OAuth token `ENV_AT_EXEC`、HTTP egress、用户专属 workload | 查询当前用户和仓库；受控创建/读取 issue |
| Filesystem MCP | `mcp/filesystem` | Docker MCP Catalog 从 `modelcontextprotocol/servers` 构建 | workspace mount、command args、Roots/允许目录 | 列目录、读文件；经审批写入测试文件 |
| Git MCP | `mcp/git` | Docker MCP Catalog 从 `modelcontextprotocol/servers` 构建 | workspace mount、Git working tree、command args | status、log、diff；经审批创建测试分支 |
| PostgreSQL MCP | `bytebase/dbhub` | Bytebase 发布者原版 | stdio/HTTP、`ENV_AT_EXEC`、TCP egress 5432、DATA profile | schema 检索、只读查询；写 SQL 默认拒绝 |
| Docker MCP | `mcp/dockerhub` | Docker Inc. 官方 Docker Hub MCP | stdio、Docker Hub PAT、HTTP egress | 查询 namespace、repository、tag |
| Playwright MCP | `mcr.microsoft.com/playwright/mcp` | Microsoft 发布者原版 | BROWSER profile、`/dev/shm`、ephemeral profile、HTTP egress、会话亲和 | 打开测试页、读取页面、点击并验证结果 |

上游和镜像依据：

- [Filesystem Reference MCP](https://hub.docker.com/r/mcp/filesystem/)
- [Git Reference MCP](https://hub.docker.com/mcp/server/git/overview)
- [Bytebase DBHub](https://github.com/bytebase/dbhub)
- [Docker Hub MCP](https://hub.docker.com/mcp/server/dockerhub/overview)
- [Microsoft Playwright MCP](https://github.com/microsoft/playwright-mcp)

本路线图中的“Docker MCP”基线明确指 Docker 官方的 Docker Hub MCP，不是能够控制本机 Docker Engine 的 MCP。若后续要求调用方创建、删除容器或部署 Compose，必须另立高风险设计：通过独立、最小权限的 Docker Operation Broker 暴露受限业务操作，绝不能把 Runtime Docker socket 挂载给社区 MCP 容器。

## 7. 当前验证范围

契约、主体隔离、网络/存储边界与镜像准入测试继续保留。社区 MCP runner 验证
Profile 发布、工具发现与直接调用、调用台账以及资源回收。历史业务编排验收已退役；
真实 OAuth/工具回归需要明确提供独立环境与授权。参见 README 的当前运行命令。
