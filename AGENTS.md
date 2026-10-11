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

## Testing Guidelines
Run backend tests with `./gradlew test`. Place Java tests in the matching module test tree, mirroring production package names, and name test classes with a `*Test` suffix.

For frontend changes, run `pnpm typecheck` at minimum. If adding test tooling or tests, keep them near the feature they cover and use clear names such as `UserTable.test.tsx` or `permission-utils.test.ts`.

## Commit & Pull Request Guidelines
Recent history uses Conventional Commit-style prefixes such as `feat:`, `fix(i18n):`, and `chore:`. Keep commits focused and use a short imperative summary, optionally scoped: `feat(permission): add field scope editor`.

Pull requests should describe the change, list backend/frontend impact, link related issues, and include screenshots or screen recordings for UI changes. Mention any required config, migration, or data-permission behavior changes, and include the commands used for validation.

## Security & Configuration Tips
Do not commit secrets, generated credentials, local IDE files, or environment-specific configuration. Keep mock service worker assets in the frontend `public/` directory and document any new required environment variables in the relevant README.
