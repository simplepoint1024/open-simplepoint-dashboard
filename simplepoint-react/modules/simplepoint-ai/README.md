# SimplePoint AI frontend

The AI frontend is a Module Federation remote served under `/ai/mf` by
`simplepoint-service-ai`.

The remote exposes an AI workspace, model provider management, and a synchronized
model catalog. Provider actions can test credentials, preview remote models, and
persist the currently available model list.

Platform views use `/ai/platform/ai/**` without tenant headers and maintain shared
`SYSTEM` resources. Tenant views use `/ai/tenant/ai/**`, carry the active tenant
context, and maintain only that tenant's `TENANT` resources when BYOK is enabled.

From the `simplepoint-react` workspace, run:

```bash
pnpm dev:ai
pnpm build:ai
pnpm typecheck:ai
```
