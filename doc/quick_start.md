# 快速开始

本路径不使用 Docker。请先在本机启动 Consul、Redis、PostgreSQL，并安装 JDK 21+、
Gradle 8.12+、`curl` 和 PostgreSQL 客户端；Node.js 与 pnpm 由 Gradle 构建自动下载。

## 三步启动核心平台

```bash
git clone https://github.com/simplepoint1024/open-simplepoint-dashboard.git
cd open-simplepoint-dashboard
mkdir -p .simplepoint
cp config/dev.env.example .simplepoint/dev.env
```

编辑 `.simplepoint/dev.env` 中的 PostgreSQL、Redis、Consul 地址和凭据，然后执行：

```bash
./dev doctor core
./dev init core
./dev up core
```

打开 `http://127.0.0.1:8080`，使用 `simplepoint@mail.com` / `123456` 登录。

## 启动 AI、Skill、Agent 与工作流

PostgreSQL 需预先安装 pgvector；脚本会自动创建 `vector` 和 `pg_trgm` 扩展。

```bash
./dev doctor ai
./dev init ai
./dev up ai
```

该组合包含 authorization、common、ai、mcp-gateway、agent-runtime、workflow-runtime
和 host。若只调试后端接口，可用 `./dev up ai --backend-only` 跳过前端工具链。

## 日常命令

```bash
./dev status
./dev logs common --follow
./dev restart common
./dev config plan
./dev down ai
```

配置同步是幂等的，只更新变化项，旧值备份到 `.simplepoint/backups/`。服务日志位于
`.simplepoint/logs/`。完整参数、端口、配置模型和排障说明见
[`deployment/local_development.md`](deployment/local_development.md)。

默认账号仅用于本地开发。生产或共享环境必须修改密码、服务客户端密钥以及 AI/MCP
相关加密密钥。
