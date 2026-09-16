# 本地开发环境（无 Docker）

本文给出仓库的标准本地启动路径。目标是：机器已安装 Consul、Redis、PostgreSQL 和
JDK 后，克隆代码、修改一个环境文件即可初始化并启动，不要求 Docker、Terraform、
Consul CLI、系统 Node.js 或系统 pnpm。

## 1. 前置条件

| 组件 | 要求 / 默认地址 | 说明 |
| --- | --- | --- |
| JDK | 21+ | 后端构建与运行基线 |
| Gradle | 8.12+ | 本地脚本默认使用系统 `gradle`；也可在环境文件中改为 `./gradlew` |
| Consul | `http://127.0.0.1:8500` | 配置与服务发现 |
| PostgreSQL | `127.0.0.1:5432` | 账号需要访问目标库；自动建库时需要 `CREATEDB` |
| Redis | `127.0.0.1:6379` | 有密码时本机需有 `redis-cli` 用于预检 |
| curl、psql | PATH 可用 | 初始化和诊断使用 |

核心链路不依赖 RabbitMQ、MinIO。前端正式资源由 Gradle 下载并管理固定版本的
Node.js 24.19.0 和 pnpm 11.15.1；使用 `--backend-only` 时完全跳过前端构建。

AI 组合额外要求 PostgreSQL 已安装 `pgvector`，初始化脚本会创建 `vector` 与
`pg_trgm` 扩展。

## 2. 首次启动

```bash
git clone https://github.com/simplepoint1024/open-simplepoint-dashboard.git
cd open-simplepoint-dashboard
mkdir -p .simplepoint
cp config/dev.env.example .simplepoint/dev.env
```

只需编辑 `.simplepoint/dev.env`。通常需要修改 PostgreSQL 的地址、数据库、用户和
密码；非默认端口或启用 ACL 时再修改 Redis、Consul 项。该文件已被 Git 忽略，禁止
把真实凭据写入 `acp.json` 或提交到仓库。

启动核心平台：

```bash
./dev doctor core
./dev init core
./dev up core
```

启动包含 AI 工作台、MCP Gateway、Agent Runtime、Workflow Runtime 的组合：

```bash
./dev doctor ai
./dev init ai
./dev up ai
```

首次完整构建会下载 Node.js、pnpm 和项目依赖，之后使用本地缓存。只调试 API
且不需要页面时可执行：

```bash
./dev up ai --backend-only
```

## 3. 命令说明

| 命令 | 作用 |
| --- | --- |
| `./dev doctor [core\|ai\|full]` | 检查 JDK、Consul、PostgreSQL、Redis、扩展与端口冲突 |
| `./dev init [core\|ai\|full]` | 创建数据库、创建 AI 扩展、幂等同步 Consul 并校验 |
| `./dev config plan` | 显示配置的新增、变化、旧键清理和未变化项 |
| `./dev config apply` | 仅同步差异；覆盖或清理前备份旧值 |
| `./dev config verify` | 校验 Consul 内容与仓库配置完全一致 |
| `./dev status` | 显示服务 PID、健康状态与地址 |
| `./dev logs host --follow` | 查看或持续跟踪单个服务日志 |
| `./dev restart common` | 重启单个服务 |
| `./dev down [core\|ai\|full]` | 按反向依赖顺序停止服务 |

运行状态、PID、日志和 Consul 备份都位于 `.simplepoint/`。

## 4. 配置模型

Consul 配置只有一个仓库来源：

```text
config/consul/
├── base/                 # 所有环境共享
└── profiles/
    ├── dev/              # 本地开发环境覆盖
    └── compose/          # Docker Compose 覆盖
```

本地进程只使用 `dev` Spring Profile。Consul 的公共键和服务键先加载，随后由
`*-dev` 键覆盖地址与端口。`./dev config apply` 直接调用 Consul HTTP API，不依赖
Terraform 或 Consul CLI，并在 `simplepoint/bootstrap/dev/status` 写入配置摘要、Git
版本和更新时间。重复执行是安全的；未变化配置不会重写。

从旧版脚本升级时，首次执行会把 `.simplepoint/local.env` 自动迁移为 `dev.env`，并在
备份后删除 Consul 中由旧脚本管理的 `*-local` 键；后续不再存在额外的 local 环境层。

本地端口如下：

| 服务 | 端口 |
| --- | ---: |
| authorization | 9000 |
| common | 7000 |
| host | 8080 |
| auditing / dna | 6000 / 2777 |
| ai / mcp-gateway | 2888 / 2890 |
| agent-runtime / workflow-runtime | 2894 / 2895 |

## 5. 启动组合与能力边界

- `core`：authorization、common、host，适合权限、租户、菜单和普通业务开发。
- `ai`：core + ai、mcp-gateway、agent-runtime、workflow-runtime，支持模型接入、知识库、
  Skill、Agent 和工作流设计/执行。
- `full`：ai + auditing、dna。

无 Docker 的 `ai` 组合支持远程 MCP Server 和内建能力。受管 OCI Tool Runtime 本身以
隔离容器作为安全边界，仍需要 Docker/容器运行时，不会在本地模式中降级为不安全的
宿主进程执行。因此 dev 配置默认关闭 OCI Runtime 的数据库调度；以后启动了 Runtime
Node，再在 `.simplepoint/dev.env` 中设置
`SIMPLEPOINT_TOOL_RUNTIME_SCHEDULING_ENABLED=true` 并执行 `./dev config apply`。

AI 的持久化任务 Worker 在队列为空时会自动指数降频，本地上限默认是 5 秒；一旦发现
任务会立即恢复原始轮询频率。可通过
`SIMPLEPOINT_AI_POLLING_MAXIMUM_IDLE_INTERVAL` 调整空闲间隔，通过
`SIMPLEPOINT_AI_ADAPTIVE_POLLING_ENABLED=false` 完全关闭自适应降频。开发环境默认不打印
Hibernate SQL；需要临时排查 SQL 时设置 `SIMPLEPOINT_JPA_SHOW_SQL=true`。

## 6. 验证与排障

启动成功后访问：

- Host UI：`http://127.0.0.1:8080`
- Authorization：`http://127.0.0.1:9000`
- Common：`http://127.0.0.1:7000`
- Consul UI：`http://127.0.0.1:8500`

默认开发账号为 `simplepoint@mail.com` / `123456`；另有
`manager@simplepoint.local` 和 `member@simplepoint.local`，默认密码相同。仅限本地使用。

启动失败时先执行 `./dev status`，再看 `./dev logs <service>`。常见处理：

- Consul 不一致：`./dev config plan && ./dev config apply`。
- 数据库不存在：`./dev database create`；账号无建库权限时由 DBA 手工创建。
- AI 扩展失败：先为 PostgreSQL 安装 pgvector，再执行 `./dev database extensions`。
- 端口被占用：根据 `./dev doctor` 输出停止冲突进程或调整配置与端口映射。
- 前端下载受代理影响：配置 Gradle 的 HTTP/HTTPS 代理，或使用 `--backend-only`。

## 7. IntelliJ IDEA

先完成 `./dev init`。运行配置中设置 `SPRING_PROFILES_ACTIVE=dev`，并从
`.simplepoint/dev.env` 导入环境变量，然后直接运行对应服务主类。不要把本地密码放进
项目共享的 Run Configuration。
