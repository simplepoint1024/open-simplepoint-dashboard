# SimplePoint Dashboard Frontend

This repository contains the React frontend workspace for the SimplePoint dashboard system. It works with the backend in `open-simplepoint-dashboard` and is organized as a small Nx + pnpm monorepo.

## Workspace layout

- `modules/simplepoint-host`: the shell application that loads remote modules and renders the dashboard routes.
- `modules/simplepoint-common`: the shared platform remote.
- `modules/simplepoint-audit`: the auditing and monitoring remote.
- `modules/simplepoint-dna`: the DNA remote.
- `modules/simplepoint-ai`: the AI workspace with separate platform-managed and tenant-owned provider/model views.
- `libs/`: shared UI and data-access utilities consumed by the apps.

## Prerequisites

- Node.js 22.22 or later
- Corepack-enabled `pnpm` 11.15.1

## Install dependencies

```bash
corepack enable
corepack prepare pnpm@11.15.1 --activate
pnpm install --frozen-lockfile
```

## Daily commands

### Workspace-scoped query caches

`useData` and `usePage` from `@simplepoint/shared/api/methods` automatically
partition query keys by the active tenant, role and authorization context.
Their fetch callbacks receive the TanStack Query context; pass `signal` to
`get(url, params, {signal})` so switching workspaces also aborts HTTP requests.

For direct `useQuery` / `useQueries` calls on workspace data, read
`useQueryScope()` and build options with `scopedQueryOptions(scope, key, fetchFn)`
from `@simplepoint/shared/api/queryScope`. Use `scopedQueryKey(scope, key)` for
`setQueryData` and `invalidateQueries` as well. Async save callbacks must capture
the originating scope, not look up the currently selected scope after awaiting.

The host cancels and removes previous-scope queries on scope changes, while
unscoped session queries remain intact. Route views are remounted on scope
changes to discard local state copied from old query results. This does not
clear browser storage or change backend authorization rules.

Run the regression suite with `pnpm test:query-scope`.

### Common commands

```bash
pnpm typecheck
pnpm build
pnpm dev:common
pnpm dev:audit
pnpm dev:dna
pnpm dev:ai
pnpm dev:host
```

## Backend dependency

The frontend expects the SimplePoint backend APIs to be available, including the menu, schema, tenant, and user endpoints under the `/common/*` surface used by the host and common applications.
