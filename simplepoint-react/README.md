# SimplePoint Dashboard Frontend

This repository contains the React frontend workspace for the SimplePoint dashboard system. It works with the backend in `open-simplepoint-dashboard` and is organized as a small Nx + pnpm monorepo.

## Workspace layout

- `apps/simplepoint-host`: the shell application that loads remote modules and renders the dashboard routes.
- `apps/simplepoint-common`: the shared/remote frontend module exposed through module federation.
- `libs/`: shared UI and data-access utilities consumed by the apps.

## Prerequisites

- Node.js 20 or later
- Corepack-enabled `pnpm` 9

## Install dependencies

```bash
corepack enable
corepack prepare pnpm@9 --activate
pnpm install --frozen-lockfile
```

## Daily commands

```bash
pnpm typecheck
pnpm build
pnpm dev:common
pnpm dev:host
```

## Backend dependency

The frontend expects the SimplePoint backend APIs to be available, including the menu, schema, tenant, and user endpoints under the `/common/*` surface used by the host and common applications.
