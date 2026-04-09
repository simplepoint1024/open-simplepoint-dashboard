# Copilot Instructions

## Build, test, and lint commands

### Backend (root Gradle workspace)

- Use the repo wrapper from the repository root: `./gradlew`
- CI uses JDK 17 (Temurin in `.github/workflows/ci.yml`); local work should target the same baseline.
- Show module paths before targeting a single module: `./gradlew projects`
- Full backend test suite (this matches CI): `./gradlew test`
- Full build: `./gradlew build`
- Full verification: `./gradlew check`
- Checkstyle across the Gradle subprojects: `./gradlew checkstyleMain checkstyleTest`
- Run one module's tests: `./gradlew :simplepoint-services:simplepoint-service-common:test`
- Run one test class: `./gradlew :simplepoint-services:simplepoint-service-common:test --tests 'fully.qualified.TestClass'`
- Run one test method: `./gradlew :simplepoint-services:simplepoint-service-common:test --tests 'fully.qualified.TestClass.testMethod'`

### Frontend (`open-simplepoint-dashboard-react/`, if present in the working tree)

- Install dependencies: `cd open-simplepoint-dashboard-react && corepack enable && corepack prepare pnpm@9 --activate && pnpm install --frozen-lockfile`
- Workspace type check: `cd open-simplepoint-dashboard-react && pnpm typecheck`
- Workspace build: `cd open-simplepoint-dashboard-react && pnpm build`
- Host app: `cd open-simplepoint-dashboard-react && pnpm dev:host` or `cd open-simplepoint-dashboard-react && pnpm build:host`
- Common remote: `cd open-simplepoint-dashboard-react && pnpm dev:common` or `cd open-simplepoint-dashboard-react && pnpm build:common`
- Audit remote: `cd open-simplepoint-dashboard-react && pnpm dev:audit` or `cd open-simplepoint-dashboard-react && pnpm build:audit`
- DNA remote: `cd open-simplepoint-dashboard-react && pnpm dev:dna` or `cd open-simplepoint-dashboard-react && pnpm build:dna`

## High-level architecture

- The root project is a dynamically assembled multi-module Gradle build. `settings.gradle.kts` includes every directory that contains a `build.gradle.kts`, so module paths come from the folder layout instead of a hand-maintained include list.
- The backend is layered rather than service-first:
  - `simplepoint-api`: shared contracts, DTOs, and base interfaces.
  - `simplepoint-core`: base entities/controllers/services, auth context, HTTP response wrapper, and common utilities.
  - `simplepoint-data` and `simplepoint-data-extend`: JPA/JDBC/R2DBC/MongoDB/AMQP/JSON-schema/data-source capabilities and repository infrastructure.
  - `simplepoint-plugin`: plugin runtime and Spring integration.
  - `simplepoint-plugins`: built-in business capabilities such as RBAC, OIDC, and i18n, usually split into `-api`, `-repository`, `-service`, and `-rest` modules.
  - `simplepoint-services`: runnable applications that compose the layers above.
- The main runnable services have distinct roles:
  - `simplepoint-service-host` is the WebFlux edge/gateway. It uses Spring Cloud Gateway, Redis WebSession, and AMQP remote clients.
  - `simplepoint-service-authorization` is the servlet OAuth2/OIDC authorization server. It composes JPA, plugin-webmvc, Redis, Consul, and Vault support.
  - `simplepoint-service-common` is the servlet business API aggregator. It wires RBAC, OIDC, i18n, tenant, JSON-schema, AMQP RPC, and initializer modules into one application.
- The plugin system is a runtime capability, not just a packaging convention. `PluginClassloader` loads plugin JARs, reads `META-INF/plugin-config.xml`, tracks inter-plugin dependencies, and the `/plugins` endpoint can auto-load everything from the `plugins/` directory at startup.
- If the nested `open-simplepoint-dashboard-react/` workspace is present, treat it as a separate frontend monorepo that integrates with the backend instead of the primary build. It is an Nx + pnpm workspace where `apps/simplepoint-host` is the shell, `apps/simplepoint-common` is a federated remote, and `libs/` contains shared code. The host registers remotes dynamically and the architecture docs expect built frontend assets to be published into service `src/main/resources/static/` directories.

## Key conventions

- New Spring Boot entrypoints should normally use `@Boot` plus `org.simplepoint.boot.starter.Application.run(...)`, not raw `@SpringBootApplication` plus `SpringApplication.run(...)`. `@Boot` defaults component scanning to `org.simplepoint.**`.
- JPA-backed service apps normally add `@EnableRepository`. That single annotation enables JPA repositories, entity scanning, JPA auditing, and Spring Data web support; by default it scans `org.simplepoint.**.entity` and `org.simplepoint.**.repository` and uses the custom `BaseRepositoryImpl`.
- Domain models usually extend `BaseEntityImpl<I>` (or tenant-aware variants). That gives them UUID string IDs, audit fields, and soft-delete behavior through `deleted_at`.
- REST endpoints usually extend `BaseController<S, T, I>` and return `org.simplepoint.core.http.Response`. The base controller already exposes `/schema`, and paginated endpoints typically accept `Pageable`.
- Service implementations commonly build on `BaseServiceImpl`, which generates JSON schema metadata for forms and extracts button/action metadata from `@ButtonDeclarations`. When changing entity shape or permission-gated UI actions, check both the entity annotations and the base-service schema generation path.
- Feature modules are split by responsibility. A capability is often spread across `-api`, `-repository`, `-service`, and `-rest` modules, and runnable services compose those modules in their `build.gradle.kts` files instead of re-implementing the logic locally.
- `simplepoint-service-host` is reactive/WebFlux, while `simplepoint-service-common` and `simplepoint-service-authorization` are servlet-based. Match the existing stack when adding filters, security config, request handling, or session code.
- RBAC feature modules do not own the core security domain model. Shared entities such as `Role`, `User`, `Menu`, and `Permissions` live under `simplepoint-security`, while RBAC plugin modules add repositories, services, and REST endpoints around those shared entities.
- Versions are centralized in `buildSrc/libs.versions.toml`. Prefer updating the version catalog instead of hardcoding dependency versions in individual modules.
- The root settings script discovers subprojects from `build.gradle.kts` files and honors `-PexcludeProjects=...` during project inclusion. Do not switch to a hardcoded include list unless you also change that discovery model.
- The root `build` task installs the repo pre-commit hook via `installGitHooks`; do not remove that dependency when adjusting the build.
- Configuration follows the existing SimplePoint naming conventions: prefer `SIMPLEPOINT_*` environment variables and `primitive.*` passthrough keys. Consul and Vault starters register their environment processors through `META-INF/spring.factories`.
- Multi-tenancy is mostly implicit in the backend stack: tenant-aware entities extend the tenant base entity types, repositories apply tenant filtering, `BaseServiceImpl` auto-fills tenant IDs on create, and request context is carried through `AuthorizationContext` rather than being handled ad hoc in each controller.
- Effective authorities in resource-server code come from the resolved `AuthorizationContext` / authorization services, not just raw JWT scope parsing. When changing permission checks, inspect the authorization-context resolution path as well as Spring Security config.
- If the nested frontend workspace is present, module federation exposure is convention-based: `apps/*/src/views/**/index.(tsx|ts|jsx|js)` becomes remote exposures via each app's `module.exposes.ts`, and the host resolves remote manifests from service-relative URLs such as `/<name>/mf/mf-manifest.json` unless the service supplies an explicit entry URL.
