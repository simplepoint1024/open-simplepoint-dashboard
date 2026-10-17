# Repository Guidelines

## Project Structure & Module Organization
This repository is a full-stack SimplePoint dashboard project. Backend Java modules live at the root as `simplepoint-*` directories, including `simplepoint-api/src`, `simplepoint-core/src`, `simplepoint-boot`, and related support modules. The React frontend is isolated in `simplepoint-react`, a pnpm/Nx workspace with apps under `apps/*`, shared libraries under `libs/*`, and static/mock assets under `public/`. Documentation and operational notes are in `README.md`, `doc/`, `plugins/`, and module-level README files.

## Build, Test, and Development Commands
Use root Gradle commands for backend work:

```bash
./gradlew build
./gradlew test
```

Use frontend commands from `simplepoint-react`:

```bash
pnpm install
pnpm dev:host
pnpm build
pnpm typecheck
```

`pnpm dev:host` runs the host app locally. `pnpm build` builds the common package and host app through Nx. `pnpm typecheck` runs TypeScript checks for the common and host apps.

## Coding Style & Naming Conventions
Java code should follow conventional package naming under each module's `src/main/java`, with classes in `PascalCase`, methods and fields in `camelCase`, and constants in `UPPER_SNAKE_CASE`. Keep service, controller, mapper, and model names explicit and domain-oriented.

Frontend code uses TypeScript and React. Prefer `PascalCase` for components, `camelCase` for functions/hooks, and colocate feature-specific files inside the owning app or library. Use 2-space indentation for TypeScript/TSX and keep shared logic in `libs/*` instead of duplicating it across apps.

## Mandatory Platform UI Standards
All new features and changes to existing features must use the platform UI component and interaction contracts wherever they cover the requirement. Do not use native Ant Design table, button, form, picker, or pagination components in business pages to recreate behavior already provided by a platform component.

- Use `SimpleTable` for resource CRUD list pages with a server `/schema`, server pagination and query contract, and permission-filtered buttons. Keep the list, filters, sorting, selection, refresh, and standard create/edit actions in its controller. Use `DataTable` for feature-specific, composite, or in-memory tables that do not have a resource CRUD contract; do not use raw Ant Design `Table` for these pages.
- Define resource CRUD actions in the server schema. For composite pages without that contract, derive action descriptors from server permissions or capabilities. Render list toolbar, record, and detail actions through `SimpleTable` or the shared `TableActions` renderer. Do not hand-code native Ant Design `Button` controls for actions that the platform action renderer supports. Keep action permission checks on the server.
- Use shared platform form and selection controls, such as `SForm` and `EntitySelect`, when their behavior matches the field. A native Ant Design control is allowed only when no platform component covers the interaction, such as a specialized editor or a dialog's confirmation controls. Document the reason in code when the exception is not obvious.
- When adding or reviewing a feature, check the existing platform components in `simplepoint-react/libs/components` first. Do not add a parallel local table, action toolbar, or selection implementation for behavior the platform already owns.
- Published form business pages must open directly on the standard resource list. Render the designed form inside the platform create/edit drawer; expose detail and approval/history through permission-derived list actions. Do not introduce top-level Form/Data/Submission tabs or a second CRUD controller. Detail tables must retain usable column widths and scroll within their own bounded width/height in editors and read-only views.

- Retained-page overlays must follow the owning page selection. Reads must be cancelled or fenced on close, owner change and authorization scope change; writes must use the shared operation lock. Prefer `loadEditingRecord` to reimplementing the standard edit lifecycle.
- Table appearance preferences must use the shared account-synchronized preference store, retaining local edits on synchronization failure and fencing stale loads. Automatic identities must use the retained page location, not the global pathname/hash of another tab.
- Render dates and timestamps through the shared temporal formatter using the platform locale. Calendar dates and civil times must retain their values; only offset-bearing instants are converted to the viewer timezone. Never change storage/query values for presentation.
- Domain modules own complete status labels and state transitions; use platform semantic status tones. Unknown or terminal statuses must not default to a running appearance.
- Public domain error envelopes must include a stable, namespaced `messageKey` with supported translations while preserving diagnostic codes. Do not translate by matching English exception messages or expose submitted values as translation parameters.
- After moving an approved native control, update its exact UI exception entry and run `node scripts/ui-standards/audit.mjs --strict` from the frontend workspace. A stale entry is a failed quality gate.

## Mandatory Table and Form Extension Standards

- Ordinary data lists default to creation time descending before pagination, with a unique stable tie-breaker. Explicit user sorting wins. Models with ordinal/sequence fields preserve their declared sequence. Do not sort or reverse a single server page in the browser; SQL result snapshots retain query order. Locally created rows without timestamps must be inserted at the front by their owning controller.
- Record action columns use the shared `TableActions` row appearance: icon plus text, no ordinary filled/outlined button chrome. Toolbar and dialog actions keep their own hierarchy; action permissions remain server-derived.
- Form component property extensions register typed tabs with stable keys, labels, mandatory icons and independent content components. The generic designer owns tab layout; component-specific fields and index configuration belong to their own tabs.
- Detail rows edit through the owning form's field registry and validation paths. Preserve aggregate versioning, field write policies, bounded scrolling and server validation. Never create a second uncommitted form buffer inside an inline detail row.
- Business field validation must survive drafts, immutable publication snapshots, runtime projections and server aggregate writes. Text lengths count Unicode code points. User-configured patterns use bounded RE2 semantics in both browser and server; do not execute them through native backtracking regex engines.

## Mandatory Generated Business Resource Standards

- Generated business menus must share the same route identity function for rendering, selection and expansion, including directories without a URL. Verify navigation, tab switching and restoring a saved URL.
- Dynamic records must inherit the platform tenant, creator department, creator/updater and audit timestamp semantics. Populate these fields on the server; keep them separate from editable business values and reject attempts to write them through business payloads.
- Publish the generated page's data/field permission descriptor with its resource generation, including owned detail models. Permission discovery must respect the active tenant and actual page grants. Refreshing metadata must not enable offline resources or restore grants.
- Bind business field grants and data-scope expressions to stable field identities, not mutable storage column names. Enforce policy on the server for lists, counts, detail reads, filters, sorting, writes and historical snapshots. Hidden and masked fields must not be usable for query inference; UI visibility alone is not enforcement.

## Mandatory Platform Event Standards
New cross-service business events must use `simplepoint-events-api`, the transactional JDBC outbox/inbox and the RabbitMQ adapter. Business modules must not publish directly with RabbitTemplate, use local Spring events/Redis PubSub as durable cross-service delivery, or couple themselves to another module's persistence tables.

- Publish inside the same writable database transaction as the business fact. Handlers commit local effects, inbox completion and outgoing completion events atomically; remote effects belong to a separate event/operation.
- Use explicit versioned JSON contracts, stable event/subscription IDs, tenant/resource scope, operation IDs and generations. Never serialize Java type names, credentials or user authorization snapshots. Validate trusted producer and schema; fence stale generations.
- Authorization bootstrap queries must explicitly exclude revoked grants; do not depend on request-scoped soft-delete filters. Re-online must not restore revoked ordinary grants, including grants cached before the revocation.
- Keep durable event transport health observable separately from business readiness. Broker downtime must not remove otherwise ready outbox-backed services from RPC discovery; database unavailability must still prevent readiness.
- Delivery is at least once. Broker confirmation is not business completion. Required lifecycle participants must report durable completion before the owning operation is considered complete.
- Provision durable subscriptions before enabling producers; use separate service identities and broker ACLs, mandatory routing, correlated confirms, manual receipt ACK, bounded retries, audited dead-event replay and backlog monitoring. See `simplepoint-events/README.md`.
- Formal resource offline must revoke grants in the authorization-owning module, invalidate authorization versions and preserve data/history. Re-online must not revive previous ordinary grants. Client-side visibility is never the authorization boundary.

## Testing Guidelines
Run backend tests with `./gradlew test`. Place Java tests in the matching module test tree, mirroring production package names, and name test classes with a `*Test` suffix.

For frontend changes, run `pnpm typecheck` at minimum. If adding test tooling or tests, keep them near the feature they cover and use clear names such as `UserTable.test.tsx` or `permission-utils.test.ts`.

## Commit & Pull Request Guidelines
Recent history uses Conventional Commit-style prefixes such as `feat:`, `fix(i18n):`, and `chore:`. Keep commits focused and use a short imperative summary, optionally scoped: `feat(permission): add field scope editor`.

Pull requests should describe the change, list backend/frontend impact, link related issues, and include screenshots or screen recordings for UI changes. Mention any required config, migration, or data-permission behavior changes, and include the commands used for validation.

## Security & Configuration Tips
Do not commit secrets, generated credentials, local IDE files, or environment-specific configuration. Keep mock service worker assets in the frontend `public/` directory and document any new required environment variables in the relevant README.

## Mandatory Form Extension Standards

- Add declarative component capabilities through the versioned backend registry and frontend component/property/behavior registries. Keep component-specific configuration, previews and runtime adapters outside the designer controller. Reuse SForm widgets and platform tables/actions.
- Form list defaults bind stable field IDs and remain independent of scene layout and personal column preferences. Provide pointer and keyboard drag ordering; never require users to enter ordering numbers.
- Field create/edit visibility and writability policies intersect role field grants and lifecycle policies. Enforce blocked writes on the server, including owned detail fields and older page versions. Operation visibility must not be presented as a replacement for read authorization.
- Preserve absent optional metadata in immutable legacy snapshots. Cover canonical serialization, publication, runtime projection, direct API bypass and cross-scene field reuse when extending contracts. Component option domains and defaults require bounded server validation; Markdown must not execute raw HTML or unsafe URLs.
