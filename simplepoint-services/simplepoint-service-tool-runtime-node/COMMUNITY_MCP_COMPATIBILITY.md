# Community MCP compatibility baseline

Baseline date: 2026-08-06

`PROFILE_VALIDATED` means the Java Profile contract, original-image policy,
process/secret/storage/network/session invariants, and digest linkage are under
automated tests. `LIVE_E2E` is environment-specific and is only passed by the
matrix runner after the platform, OAuth credentials, Runtime Node, and
acceptance overlay are running. `OAUTH_REQUIRED` means all non-interactive gates
are present but a real subject-owned OAuth App authorization is still required.

| Server | Upstream selector | Locked multi-platform digest | Profile gate |
| --- | --- | --- | --- |
| GitHub MCP | `ghcr.io/github/github-mcp-server:0.31.0` | `sha256:7b1384cdd6d025c09256af2fb6cb79bc5e87aedc957c8826b5e50d8cb82f0be3` | `HISTORICAL_LIVE_E2E` |
| Filesystem MCP | `mcp/filesystem:1.0.2` | `sha256:7030b3d30ca1e662313e3e72cf60fbcd3fb16a337cfda12b59a167686eaa4dae` | `HISTORICAL_LIVE_E2E` |
| Git MCP | `mcp/git:latest` at commit `b4ee623` | `sha256:ad6af958e79466a14b895891e772fdda28fdc12c06fff4a70637b66773303a2c` | `HISTORICAL_LIVE_E2E` |
| PostgreSQL / DBHub | `bytebase/dbhub:0.24.0` | `sha256:098c16d168fc397c054396e604801e9dd9e138cd4e7aa8f865ac18dc53d3aa4e` | `HISTORICAL_LIVE_E2E` |
| Docker Hub MCP | `mcp/dockerhub:latest` at commit `ad806e2` | `sha256:76454af4edfd21571d9740113104d0d9f707220453d1c8f7c9971b21848d4248` | `HISTORICAL_LIVE_E2E` |
| Playwright MCP | `mcr.microsoft.com/playwright/mcp:latest`, resolved 2026-08-05 | `sha256:3d871c22ea2d4cca0966e2cfb1860e1cb03eb7353725a3d6cffd133296fb04eb` | `HISTORICAL_LIVE_E2E` |

The two upstream `latest` selectors are provenance inputs only. Deployment
always uses the digest above. If the remote provenance gate sees a moved tag,
it requires explicit upstream review and a new Profile revision; it never
silently updates an existing deployment.

PostgreSQL uses DBHub `execute_sql.readonly = true` plus a database login with
only `CONNECT`, schema `USAGE`, and table `SELECT`. Playwright can reach only
`site.mcp-e2e.local:80` on a dedicated internal network. Filesystem and Git
receive different managed volumes. Git uses its upstream image identity and a
shell-free first-create initializer; fixed-user workloads continue to run as
`65532:65532`.

## 当前验收范围

上述摘要是 2026-08-06 的历史环境验收，不代表精简后的版本已重新执行真实工具调用。
当前 runner 保留协议发现、直接工具调用、Runtime Revision 与能力快照台账关联、
PostgreSQL 只读拒绝、Playwright 会话连续性、OAuth 主体隔离及资源回收检查。
发布前应使用独立测试资源重新运行，凭证不得写入报告。
