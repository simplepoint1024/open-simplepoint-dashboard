# SimplePoint AI frontend

The AI frontend is a Module Federation remote served under `/ai/mf` by
`simplepoint-service-ai`.

The remote exposes one `workbench/*` resource tree: API keys, providers, models,
knowledge bases, the extension catalog, MCP, skills, agents, workflows, and billing. The model table opens a minimal
conversation-only debug dialog; there is no standalone playground route.

All pages use `/ai/workbench/**`. The backend derives `SYSTEM` or `TENANT` ownership
from the current authorization context, so the frontend never selects a scope or
sends an arbitrary tenant ID.

From the `simplepoint-react` workspace, run:

```bash
pnpm dev:ai
pnpm build:ai
pnpm typecheck:ai
```
