# MCP Tool 与 Skill 独立开发

Open SimplePoint 的扩展面分为两类：

- Tool 是标准 MCP Server，以独立 OCI 镜像运行；平台只负责准入、调度、网关和审计。
- Skill 是 `simplepoint.io/v1alpha1` 声明式 OCI Artifact；只能编排已固定的 MCP
  Tool、Prompt、Resource，不能嵌入脚本、命令或容器代码。

两者都不需要修改、重新编译或重启平台主服务。扩容时 Tool Runtime 节点提供新的
OCI 执行容量，AI/Agent/Workflow Runtime 继续通过数据库租约横向领取任务。

## 创建项目

创建 Tool：

```bash
./scripts/shell/create_ai_extension.sh tool order-query-mcp \
  --output ../order-query-mcp
```

创建 Skill 前，先在 AI 工作台发现并发布 MCP Server 能力快照，然后使用固定 ID：

```bash
./scripts/shell/create_ai_extension.sh skill summarize-order \
  --server-id <MCP_SERVER_ID> \
  --snapshot-id <CAPABILITY_SNAPSHOT_ID> \
  --tool-name query-order \
  --tool-alias query-order \
  --output ../summarize-order
```

生成的目录是完整独立项目，包含测试、Dockerfile 或 Artifact 打包脚本，以及
GitHub Actions 验证和发布模板。

## Tool 发布契约

模板使用官方 MCP SDK，并在 CI 内真实验证 `initialize`、`tools/list` 和
`tools/call`。镜像必须：

- 使用不可变 OCI Digest；
- 声明 `org.opencontainers.image.*` 来源、版本、修订和许可证；
- 声明 `io.simplepoint.mcp.transport=stdio`；
- 声明 `io.simplepoint.mcp.protocol-version=2025-11-25`；
- 以非 root 用户运行，不内置 Secret；
- 生成 SPDX SBOM，并对 Digest 和 SBOM 做 Cosign 签名/证明。

在 AI 工作台“MCP Servers”创建 `MANAGED_OCI` Server，填入镜像 Reference 与
Digest，配置资源上限、网络域名白名单、Secret 引用和副本范围。平台完成签名、
SBOM、漏洞与标签准入后，由独立 Tool Runtime 拉取和运行。

## Skill 发布契约

Skill CI 使用固定媒体类型打包：

```text
Artifact       application/vnd.simplepoint.skill.v1+json
Config         application/vnd.simplepoint.skill.config.v1+json
Manifest Layer application/vnd.simplepoint.skill.manifest.v1+json
```

Config 固定 Manifest 原始字节的 SHA-256。CI 对 OCI Artifact Digest 做 keyless
Cosign 签名。平台导入时重新校验媒体类型、每层 Digest、Config 引用、Manifest
内容、禁止执行字段、作用域、能力快照和描述符 Hash。发布后的 Skill Version
不可原地修改。

## 本仓库模板回归

```bash
./scripts/shell/verify_ai_extension_templates.sh

# 同时构建并检查模板 OCI 镜像标签
AI_EXTENSION_VERIFY_DOCKER=true \
  ./scripts/shell/verify_ai_extension_templates.sh
```

模板内置的 CI 面向 GitHub Container Registry。使用其他 Registry 时只需替换
登录步骤和镜像/Artifact 名称，不改变 MCP 或 Skill 媒体契约。
