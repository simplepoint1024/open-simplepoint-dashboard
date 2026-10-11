# SimplePoint Dashboard Frontend

This repository contains the React frontend workspace for the SimplePoint dashboard system. It works with the backend in `open-simplepoint-dashboard` and is organized as a small Nx + pnpm monorepo.

## Workspace layout

- `modules/simplepoint-host`: the shell application that loads remote modules and renders the dashboard routes.
- `modules/simplepoint-common`: the shared platform remote.
- `modules/simplepoint-audit`: the auditing and monitoring remote.
- `modules/simplepoint-dna`: the DNA remote.
- `modules/simplepoint-ai`: the AI workspace with separate platform-managed and tenant-owned provider/model views.
- `modules/simplepoint-app`: the application workbench remote for application and form composition.
- `modules/simplepoint-workflow`: the process center remote for definitions, tasks, and matters.
- `libs/components/WorkflowDesigner` and `libs/components/ProcessStatusDiagram`: reusable BPMN authoring and read-only execution-state components.
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

## Frontend loading and bundle checks

Run `pnpm check:frontend` as the release quality gate: it checks all six TypeScript
projects, discovers frontend regression tests, validates i18n, builds every app,
checks MF bundle budgets, and exercises the production artifacts in Chrome.
Any failing step stops the gate. It requires Chrome (`CHROME_BIN` may override
the executable). API fixtures never substitute for an authenticated staging E2E test.

### Tab resource lifecycle

`TabPanels` restores tab labels without eagerly mounting their pages. Visited
pages consume the host-provided `@simplepoint/shared/page/context` singleton.
`useData`/`usePage` pause automatic queries for inactive pages or hidden browser
documents. Manual mutations remain independent; an already in-flight read may
finish and populate its scoped cache. Direct `useQuery` calls and custom effects
must explicitly use `usePageActive` when they need the same lifecycle policy.
`usePagePolling` serializes periodic reads across pause/resume cycles, preventing
slow connections from accumulating overlapping polls.

The mounted-page cache has an eight-page **soft** budget. Eviction is opt-in:
unknown routes, open editors and pending operations remain protected even if
the budget is exceeded. Only reviewed Package and LoginLog routes currently
opt in via `SimpleTable.allowTabEviction`. A custom page must account for ALL
its editors, uploads and operations before opting in. Evicted list pages may
reset local filters/pagination when reopened; retained pages preserve them.
Changing authorization scope discards the old page instances. Never replace
this policy with unconditional unmounting or a hard limit that discards drafts.

`usePageRetention(canEvict, blockClose)` also protects explicit tab close,
bulk close, route refresh, user-initiated tenant/role changes and browser unload.
Generic table editors, Package configuration, profile editing
are integrated. Custom editors must register their own dirty/busy state; forced
authorization changes are not blocked. Table drawers follow `usePageSelected`
without discarding draft/widget state while their route is inactive.

### Deployment continuity and diagnostics

Before replacing frontend containers, run `scripts/archive-assets.mjs` with an
explicit Compose project and an absolute `asset-archive` output directory.
It copies only hashed static assets from running containers, atomically records
the generation, and retains three generations. Only unreferenced assets older
than that window are pruned. Local edge configuration uses the archive only
after an upstream 404, never after authentication redirects or 401/403 errors.
This does not guarantee arbitrary Host/Remote API compatibility: deploy compatible
versions together, and retain their images for rollback as well.

The host checks its same-origin HTML entry at most once every five minutes while
visible. A changed hashed entry offers an update, with the edit guard applied
before refresh; offline/login/error responses never trigger a forced reload.

`window.spPerformance.snapshot()` exposes the latest 60 route-activation samples
in browser memory: time from committed activation to ready paint, cached-state
flag, outcome, request count and transferred bytes. It records no URLs, request
parameters, credentials or API bodies and sends nothing to an external collector.
These local diagnostics are not full real-user Web Vitals or backend tracing.

### Action feedback

`useActionFeedback` provides per-action synchronous duplicate-submit protection
and contextual error messages. It returns a success/failed/busy result rather
than leaking rejected UI-event promises. Keep action keys stable, include entity
IDs for independent row actions, and handle success only inside the completed
operation. Form validation stays inline; cancellation is not an error toast.
Query mutations do not automatically retry by default: only enable retries when
the backend provides an idempotency contract. SimpleTable additionally locks
submissions before loading-state rendering can catch up.

The host entry asynchronously imports `bootstrap.tsx` before consuming shared
React/UI dependencies. Keep shared dependencies asynchronous in
`libs/shared/types/module.shared.ts`. Internal workspace modules use
`requiredVersion: false` because `workspace:*` is not a valid browser runtime
semver range. The feedback bridge and icon provider remain singletons.

Remote builds use `libs/shared/types/module.remote-shared.ts`. Only framework
dependencies that the host really imports and process-wide internal state use
`import: false`, making the host their provider. UI components and leaf
libraries such as RJSF and `react-highlight-words` stay local to each remote;
they do not require singleton identity and must remain split at their actual
usage boundary. Do not add an entry to the shared list merely to deduplicate
bytes: if the host does not register a provider getter, `import: false` causes
Federation `RUNTIME-012` when the first matching page opens.

The complete Ant Design icon registry is bundled in one shared icon provider.
Menu icons are resolved synchronously and never issue icon-specific lazy chunk
requests. Both menus and the virtualized icon picker retain access to the
complete supported icon set. Do not reintroduce a dynamic icon context.
Import the registry using `@simplepoint/shared/types/icon`, including inside
`libs/shared` itself: a relative import bypasses MF's shared request matching
and silently bundles the entire registry into the remote.

Keep Module Federation asset analysis enabled in every remote manifest. The
host preloads only the selected expose's synchronous assets. Hover/focus waits
180 ms, can be cancelled before starting, and runs at most two speculative
loads. Data saver and slow links disable speculation; actual navigation never
waits for this queue or for optional preload links to complete. Pointer-down
signals immediate intent. Registration does not download unrelated services.
Keep module caches keyed by the remote registry signature so tenant or
deployment changes cannot reuse a module from an obsolete registry. Avoid
variable local imports such as `import(path)`: they generate a broad Rspack
context and many tiny chunks.

SimpleTable loads SForm when its drawer is rendered, and SForm loads optional
widgets when their schema actually uses them. Test opening edit/create drawers,
typing in fields, the icon picker and upload selectors after changing this code.

### Upload limits and feedback

Common object storage accepts files up to 20 MB with a 22 MB multipart request
limit. Deployments can override `SIMPLEPOINT_UPLOAD_MAX_FILE_SIZE` and
`SIMPLEPOINT_UPLOAD_MAX_REQUEST_SIZE`; keep the request limit above the file
limit and within the ingress limit (256 MB in the local deployment). Storage
tenant quotas and authorization still apply. Image widgets retain their 5 MB
limit and attachment widgets default to 10 MB.

Upload widgets report failures both inline and through the app message context.
Their `onChange` callback may return a promise: success is reported only after
that promise resolves, so avatar profile persistence failures remain visible.
The production-artifact smoke test covers multipart uploads, empty HTTP 413
responses, invalid success responses, avatar persistence failures and retries
using isolated API fixtures; it does not write to real user accounts.
Schema and namespace requests start in parallel. Raw schemas stay in scoped
query caches and are translated reactively; dictionary enrichment is a separate
cancellable query. A slow/failed dictionary does not hide the table, but the
drawer waits for complete options and provides a retry on error.

Route loading uses one persistent `LoadingBoundary`, not successive skeletons.
The shared `@simplepoint/shared/loading/context` identity lets the MF fallback
and remote schema placeholder register with that same boundary; it must remain
a host-provided singleton. The visual components themselves remain local.
Feedback appears after 200 ms, with a text hint after 1.2 s; completion is never
artificially delayed. Drawers have independent boundaries. The progress bar is
positioned out of document flow, and reduced-motion preferences are respected.
Tab switching no longer translates the page vertically.

Pagination/filtering uses `scopedPagePlaceholder` to retain previous rows only
within the same resource, tenant, role and authorization context. Background
fetches use delayed table-local feedback and disable stale-row operations;
they do not replace the table with a full-page loading placeholder.

Request signals must stay connected after `fetch()` resolves headers. Returning
the caller signal directly (or combining it with a timeout using
`AbortSignal.any`) lets abort close SSE bodies as well. Removing the caller's
abort listener at header completion leaks notification connections and can
exhaust HTTP/1.1 connection slots, blocking unrelated MF requests. The timeout
still covers receiving headers; it does not limit a long-lived response body.
Timed requests with a caller signal require a browser supporting
[`AbortSignal.any`](https://developer.mozilla.org/en-US/docs/Web/API/AbortSignal/any_static).

`collectPageExposes` shares the page-scanning policy across remotes. `config`,
`components` and `__tests__` directories are private; nested public routes remain exposed. Tests verify every route in
the backend resource declarations still resolves.

If an old tab requests a removed deployment chunk, the remote-load error offers
both an in-place retry and an explicit page refresh with an unsaved-data
confirmation. There is no automatic reload and no guarantee of retaining old
hashed assets; release tooling should deploy compatible host/remotes together.

After shared configuration changes, use a clean production build: an existing
Rspack persistent cache can retain old provider chunk placement. Remove only
`modules/simplepoint-*/node_modules/.cache/rspack` before rebuilding when needed.
Docker builds with fresh frontend dependency layers already start clean.

Run `pnpm typecheck`, `pnpm test:components`, build all modules, and then run
`pnpm check:bundles`. The latter rejects federation entries larger than 512 KiB,
disabled expose asset analysis, leaked `workspace:` version requirements and
dynamic icon contexts, duplicated remote icon registries, private exposes and
representative table pages exceeding 8 synchronous JS requests / 90 KiB gzip
(excluding host and remote entry). Also measure the total first-menu transfer in a fresh
browser: a small entry alone does not prove that its dependencies are loaded
efficiently.

Run `pnpm test:mf` for preload scheduling and route-exposure regressions.
`pnpm test:mf:browser` uses headless Chrome (`CHROME_BIN` overrides its path),
production builds and isolated API fixtures. It covers cold menu navigation,
schema/i18n overlap, slow dictionaries, the Package drawer and resolution of
every exposed module. Set `MF_SMOKE_EDGE=http://127.0.0.1:8000` to use deployed
remote assets. Also set `MF_SMOKE_HOST_DIST` to the `static/` directory
extracted from the running host JAR (host HTTP assets require login).
For a self-signed HTTPS edge, set `MF_SMOKE_CA_FILE` to the trusted CA certificate;
TLS validation remains enabled.
This is not an authenticated backend or external-network test.
Set `MF_SMOKE_DICTIONARY_FAILURE=1` to additionally inject a dictionary failure
and verify the table remains readable while its drawer offers a working retry.
