# AI Extension Templates

这些模板用于创建与平台代码完全隔离的 MCP Tool 和声明式 Skill 项目：

- `mcp-tool-typescript`：官方 MCP TypeScript SDK、stdio、OCI 镜像与供应链 CI。
- `skill`：`simplepoint.io/v1alpha1` 声明式 Manifest、OCI Artifact 与签名 CI。

不要直接修改模板占位符。使用仓库根目录脚本生成一个独立项目：

```bash
./scripts/shell/create_ai_extension.sh tool my-mcp-server \
  --output ../my-mcp-server

./scripts/shell/create_ai_extension.sh skill my-skill \
  --server-id <MCP_SERVER_ID> \
  --snapshot-id <CAPABILITY_SNAPSHOT_ID> \
  --tool-name echo \
  --output ../my-skill
```

生成目录可以单独初始化 Git 仓库、独立发布，并使用内置 GitHub Actions。平台升级
不会要求 Tool 或 Skill 与主仓库一起编译。
