# __EXTENSION_NAME__

这是一个完全声明式、可独立发布和维护的 Open SimplePoint Skill。Skill 不包含
脚本或任意代码，只固定已发布 MCP 能力快照，并通过平台持久化 Workflow 执行。

## 开发与校验

`skill.json` 中的 `serverId`、`snapshotId` 和能力名称必须来自 AI 工作台已经
发现的不可变 MCP 能力快照。

```bash
npm ci
npm test
```

本地校验覆盖官方 JSON Schema、禁止任意执行字段、能力别名、步骤 ID 和声明式
引用。平台导入时还会重新拉取 OCI Artifact、验证媒体类型与 Digest、对照实时
Registry 作用域并固定描述符 Hash。

## 打包和发布

安装 ORAS 后运行：

```bash
OCI_REFERENCE=ghcr.io/your-org/__EXTENSION_NAME__:1.0.0 \
OCI_SOURCE=https://github.com/your-org/__EXTENSION_NAME__ \
OCI_REVISION="$(git rev-parse HEAD)" \
OCI_VERSION=1.0.0 \
npm run package
```

Artifact 使用以下固定媒体契约：

- Artifact：`application/vnd.simplepoint.skill.v1+json`
- Config：`application/vnd.simplepoint.skill.config.v1+json`
- Manifest Layer：`application/vnd.simplepoint.skill.manifest.v1+json`

CI 模板会通过 GitHub OIDC 对不可变 Artifact Digest 做 keyless Cosign 签名。
发布后在 AI 工作台“技能”页面导入 Artifact Reference 和 Digest，平台验证通过
后创建并发布不可变版本，再绑定给 Agent 或 Workflow。
