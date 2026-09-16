# Community MCP compatibility baseline

Baseline date: 2026-08-06

`PROFILE_VALIDATED` means the Java Profile contract, original-image policy,
process/secret/storage/network/session invariants, and digest linkage are under
automated tests. `LIVE_E2E` is environment-specific and is only passed by the
matrix runner after the platform, OAuth/model credentials, Runtime Node, and
acceptance overlay are running. `OAUTH_REQUIRED` means all non-interactive gates
are present but a real subject-owned OAuth App authorization is still required.

| Server | Upstream selector | Locked multi-platform digest | Profile gate |
| --- | --- | --- | --- |
| GitHub MCP | `ghcr.io/github/github-mcp-server:0.31.0` | `sha256:7b1384cdd6d025c09256af2fb6cb79bc5e87aedc957c8826b5e50d8cb82f0be3` | `LIVE_E2E` |
| Filesystem MCP | `mcp/filesystem:1.0.2` | `sha256:7030b3d30ca1e662313e3e72cf60fbcd3fb16a337cfda12b59a167686eaa4dae` | `LIVE_E2E` |
| Git MCP | `mcp/git:latest` at commit `b4ee623` | `sha256:ad6af958e79466a14b895891e772fdda28fdc12c06fff4a70637b66773303a2c` | `LIVE_E2E` |
| PostgreSQL / DBHub | `bytebase/dbhub:0.24.0` | `sha256:098c16d168fc397c054396e604801e9dd9e138cd4e7aa8f865ac18dc53d3aa4e` | `LIVE_E2E` |
| Docker Hub MCP | `mcp/dockerhub:latest` at commit `ad806e2` | `sha256:76454af4edfd21571d9740113104d0d9f707220453d1c8f7c9971b21848d4248` | `LIVE_E2E` |
| Playwright MCP | `mcr.microsoft.com/playwright/mcp:latest`, resolved 2026-08-05 | `sha256:3d871c22ea2d4cca0966e2cfb1860e1cb03eb7353725a3d6cffd133296fb04eb` | `LIVE_E2E` |

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

## Live evidence

The following local release-candidate runs passed on 2026-08-05. Each run
covered direct discovery/call, immutable Skill execution, Workflow execution,
Agent execution through the isolated deterministic model stub, invocation
ledger linkage, pool shutdown, and workload cleanup. The model stub proves
platform orchestration and tool routing, not external model quality.

| Server | Server / revision evidence | Skill / Workflow / Agent evidence | Calls |
| --- | --- | --- | --- |
| GitHub | `0c6aac07-fb9a-43b6-a251-f1b359f9f70c` / `d0de6ec5-1926-4e49-9a8c-1e1a645f32bf` | `956cabf0-b0bb-4d03-abdd-5ceead014848` / `da01edf6-2c10-4e9a-876c-a1526e41dfaf` / `f6ce092b-6718-489d-9753-8d3316f5ca02` | 5 |
| Filesystem | `4911fa95-ad94-44fb-9905-70be3bb10ac1` / `cde387ac-ac4c-456c-b7a6-6c69329f456f` | `159ef271-5bb0-43ae-98f3-417fe53a6799` / `1e4a944d-0c66-4e74-a216-33344aef0890` / `e6d8fe34-2009-4e75-9503-9cc64a7e6c1f` | 4 |
| Git | `65a34945-c618-4c7d-ad88-bb422f0c5c5d` / `f39d5eca-9572-485c-b454-91b8042cd188` | `f9b1725c-57bb-4f31-ba0a-c01559dcc8e2` / `2b8fda65-17ff-4f38-8631-2bc48d55f460` / `e2247116-bc3a-4559-856f-77e9bde051ac` | 4 |
| PostgreSQL | `d8100547-bc89-4f17-a63f-8367c1c4d311` / `72c0f4bc-fe5e-4e41-a4f1-438832546e34` | `5c943a09-6461-4ce6-814f-4257a724049a` / `571cf993-bc90-4235-a129-ba0786cc1431` / `21793304-337b-4adc-8180-36a35b3dec37` | 5 |
| Docker Hub | `6fb420f6-7413-49ff-acf2-767671eeafd2` / `08b68dd6-aa12-4206-a6f7-1dedc122d627` | `246822e0-cbac-4b2f-b963-ef48ca961cdc` / `067190cf-d83a-4d6f-8e22-5fb9e4d05003` / `e0e22d4c-65c2-43f9-a4a3-cf1fc3df0c23` | 4 |
| Playwright | `29edfc67-f5dc-422f-ba40-6d03b223e7d3` / `19c33b91-def6-40f2-a28c-9c06c0a83662` | `c978a08e-10a5-4ab3-9465-7f075cbe2951` / `347325b2-5622-4641-80f1-a7941c58698a` / `874d1cdd-67f8-4127-b19a-f45ad03c58f1` | 6 |

PostgreSQL also rejected a write through both DBHub read-only policy and its
database role. Playwright retained one browser session across navigation,
click, and DOM evaluation. Docker Hub ran through the signed egress allowlist
and an optional enterprise upstream proxy. Immutable evidence remains in the
control plane; transient pools, workloads, secret materialization, and managed
volumes are reclaimed.

GitHub additionally passed the real platform Authorization Code + PKCE flow,
subject-owned encrypted token dispatch, protocol `2025-11-25` discovery (23
tools, 0 resources, 2 prompts), direct `get_me`, immutable Skill execution,
Workflow execution, and Agent execution through the configured development
model. The earlier deliberately non-authorizing token remains only a useful
non-interactive image and protocol preflight.

After the M3-M6 completion audit and Runtime rebuild, Filesystem passed a fresh
direct/Skill/Workflow/Agent regression with revision
`5ee1115b-5578-4fa3-a862-6fa8999860be` and execution evidence
`9c684666-c1a9-42c1-b14e-f3255cf17476` /
`0a4cdbbb-e165-41fa-b0af-68cf798cb8e6` /
`7c9ee03c-8812-4e38-a2ec-6c9c90b65d42`. Cleanup left zero managed workload
containers and zero keys under the managed MCP Redis session prefix.

## Baseline verification

The following implementation gates passed on 2026-08-05:

- remote tag-to-digest provenance for all six images;
- Java 21 Gradle checks for MCP, Runtime, Skill, Agent, and Workflow modules
  plus the repository-wide test/build graph (`469 actionable tasks`);
- Runtime Node `go test ./...` plus static node/launcher builds;
- negative gates for strict JSON-RPC framing and size bounds, ambiguous HTTP
  paths, host-path/symlink storage escape, exact network allowlists, cross-tenant
  workspace identity, cross-user OAuth access, token refresh/disconnect rollout,
  secret-value non-materialization in launcher config, and fenced Redis session
  reclamation;
- merged base + acceptance Compose configuration validation;
- React workspace TypeScript checks and Node/shell verifier syntax checks.

These checks establish the static baseline. The evidence above promotes all six
rows to `LIVE_E2E`. GitHub used an OAuth App client ID/secret, the matching
authenticated browser callback, and one-time platform-user authorization; a PAT
was not accepted as a shortcut.
