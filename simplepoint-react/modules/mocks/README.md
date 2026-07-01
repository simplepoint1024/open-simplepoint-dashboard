# SimplePoint Mock Layer

This package contains development-only MSW handlers for the SimplePoint React workspace.

## Structure

Mock modules mirror backend REST module boundaries:

- `src/rbac/core/*` -> `simplepoint-plugin-rbac-core-rest`
- `src/rbac/tenant/*` -> `simplepoint-plugin-rbac-tenant-rest`
- `src/rbac/router/*` -> `simplepoint-plugin-rbac-router-rest`
- `src/i18n/*` -> `simplepoint-plugin-i18n-rest`
- `src/auditing/*` -> auditing plugin REST modules
- `src/storage/*` -> storage plugin endpoints
- `src/host/*` -> host bootstrap endpoints

Each resource directory exposes an `index.ts` with a backend-facing contract:

```ts
export default defineMockModule(contract, handlers);
```

The root `index.ts` starts the browser worker through `src/browser.ts`. Handlers are collected only from
`src/registry.ts`; do not add `require.context` or implicit directory scanning.

## Adding A Resource

1. Create a backend-aligned directory, for example `src/rbac/core/users`.
2. Add `handlers.ts` for MSW handlers and fixture state.
3. Add `index.ts` with `defineResource(...)` metadata matching the backend module and controller.
4. Register the module in `src/registry.ts`.
5. Run `pnpm typecheck:mocks`.
