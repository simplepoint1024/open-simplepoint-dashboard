# __EXTENSION_NAME__

这是一个可独立开发、发布和维护的标准 MCP `stdio` Server。它不依赖
Open SimplePoint 源码；平台通过 OCI 镜像标签、Digest、签名和能力发现接入。

## 本地开发

```bash
npm ci
npm run check
npm start
```

`stdout` 是 MCP JSON-RPC 协议通道，业务日志只能写入 `stderr`。

## 构建与导入

```bash
docker build \
  --build-arg OCI_SOURCE=https://github.com/your-org/__EXTENSION_NAME__ \
  --build-arg OCI_REVISION="$(git rev-parse HEAD)" \
  --build-arg OCI_VERSION=1.0.0 \
  -t ghcr.io/your-org/__EXTENSION_NAME__:1.0.0 .
docker push ghcr.io/your-org/__EXTENSION_NAME__:1.0.0
```

CI 模板会测试 MCP `initialize -> tools/list -> tools/call`，构建多架构 OCI
镜像，生成 SPDX SBOM，使用 GitHub OIDC 对镜像签名并附加签名 SBOM。
发布后在 AI 工作台的“MCP Servers”中选择托管 OCI，填入镜像 Digest，再创建
Runtime Pool 并执行能力发现。

## 扩展原则

- 使用官方 MCP SDK 注册 Tool、Resource 或 Prompt。
- 保持输入/输出 Schema 明确、有限且向后兼容。
- 不在镜像内保存凭证；在工作台声明 Runtime Secret 引用。
- 网络默认关闭，确需外部访问时配置域名级 Egress Allowlist。
- 发布版本必须固定 Digest；不要覆盖已发布 Tag。
