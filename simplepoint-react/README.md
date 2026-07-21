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
