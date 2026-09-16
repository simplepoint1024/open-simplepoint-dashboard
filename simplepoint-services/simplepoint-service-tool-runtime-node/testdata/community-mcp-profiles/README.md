# Community MCP Runtime Profile fixtures

These fixtures are platform policy overlays for six unmodified community MCP
images. Each artifact reference is pinned to the multi-platform OCI manifest
recorded in `provenance.json`; no SimplePoint wrapper image or private image
label is required.

| Fixture | Runtime policy | Default acceptance operation |
| --- | --- | --- |
| `github` | dedicated, user OAuth, read-only, GitHub API egress | `get_me` / repository read |
| `filesystem` | dedicated writable managed workspace, no network | list/read; approved write |
| `git` | dedicated writable managed workspace, no network | status/log/diff |
| `postgresql` | affine, read-only DBHub config file, exact PostgreSQL route | schema search / SELECT |
| `dockerhub` | shared stateless, bounded log tmpfs, Node proxy support, public Docker Hub HTTP egress | public image search |
| `playwright` | dedicated browser, upstream `node` identity, bounded config/cache/shm/output, internal test site only | navigate/snapshot/click |

`MCP_E2E_POSTGRES_SECRET_ID` is an explicit template token. The community E2E
runner creates a scope-owned Runtime Secret containing a read-only `dbhub.toml`
and substitutes only its opaque ID before import. GitHub needs no static secret:
`provider://github/access-token` is resolved from the current user's Provider
Connection at dispatch time.

The Docker Hub fixture intentionally tests unauthenticated public discovery.
For private namespaces, add a scope-owned PAT as `ENV_AT_EXEC` targeting
`HUB_PAT_TOKEN` and publish a new revision with the non-sensitive
`--username=<name>` argument. Never place the PAT in Profile JSON.
