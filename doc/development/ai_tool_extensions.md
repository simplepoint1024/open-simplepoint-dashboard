# MCP Tool 独立开发

Tool 是标准 MCP Server，以独立 OCI 镜像运行。平台负责准入、调度、网关和审计。

## 创建项目

创建 Tool：

```bash
./scripts/shell/create_ai_extension.sh tool order-query-mcp \
  --output ../order-query-mcp
```

生成的目录是完整独立项目，包含测试、Dockerfile 和镜像发布配置，以及
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

## 本仓库模板回归

```bash
./scripts/shell/verify_ai_extension_templates.sh

# 同时构建并检查模板 OCI 镜像标签
AI_EXTENSION_VERIFY_DOCKER=true \
  ./scripts/shell/verify_ai_extension_templates.sh
```

模板内置的 CI 面向 GitHub Container Registry。使用其他 Registry 时只需替换
登录步骤和镜像名称，不改变 MCP 协议契约。
