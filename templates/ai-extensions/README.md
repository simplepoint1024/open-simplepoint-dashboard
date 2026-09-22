# AI Extension Templates

这些模板用于创建与平台代码完全隔离的 MCP Tool 项目：

- `mcp-tool-typescript`：官方 MCP TypeScript SDK、stdio、OCI 镜像与供应链 CI。

不要直接修改模板占位符。使用仓库根目录脚本生成一个独立项目：

```bash
./scripts/shell/create_ai_extension.sh tool my-mcp-server \
  --output ../my-mcp-server

```

生成目录可以单独初始化 Git 仓库、独立发布，并使用内置 GitHub Actions。平台升级
不会要求 Tool 与主仓库一起编译。
