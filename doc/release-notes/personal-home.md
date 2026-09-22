# Personalized home

The host dashboard is now a daily-use home page. `/dashboard` and the existing
`dashboard.view` resource code remain compatible with bookmarks and grants.
`menu.home` is a new translation key so deployments that preserve customized
dictionary entries do not keep the old dashboard title.

## Behavior

- Notifications show the latest five entries, full detail, read/read-all actions
  and a paginated inbox. Home and the navigation bell share scoped query keys and
  a single SSE connection. Plain-text content and validated links prevent
  executable notification markup/URLs.
- Users can create, rename, reorder and remove groups and select authorized apps.
  Preferences use the existing authenticated `/common/users/preferences` service,
  keyed by `home.v1.<tenantId>`; no new database table or browser-only persistence.
  A role change hides inaccessible entries without deleting their saved paths.
- Saves are explicit, duplicate submissions are blocked, failed saves retain the
  draft, and unsaved changes participate in the host's page-retention safeguards.
  Invalid/unavailable stored preferences block editing instead of being silently
  overwritten. Concurrent edits on different devices are last-save-wins.
- Date/time uses the device time zone unless overridden. Region is a browser
  locale hint or manually supplied label, **not GPS/IP geolocation**. Browser
  online/offline status is not a backend health check. No external location,
  weather or clock API is called.
- App modules are prefetched on hover/focus only; home does not eagerly load all
  favorite modules. Inactive home inbox queries and clock timers pause.
- Home owns a height-constrained vertical scrolling viewport inside its tab.
  The header, sidebar and tab bar stay fixed; wheel, touch and keyboard scrolling
  can reach all cards. Scroll position survives ordinary cached tab switches.
- A compact Monday-first calendar follows the home time zone, with month
  navigation, date selection and a today shortcut. Its fixed six-week layout
  avoids sidebar jumps; arrow keys, Home/End and PageUp/PageDown work from a
  single tab stop. It reuses the local-context clock without additional requests
  or dependencies. This is a date viewer, not an appointment/reminder service.

## Validation

Run `corepack pnpm check:frontend` in `simplepoint-react`. This includes TypeScript,
Node regression tests, translation checks, production builds, MF size budgets and
Chrome tests using production artifacts with isolated API fixtures. Home tests
cover grouped app selection, failed save/retry, reload persistence and shared
notification read state. These fixtures do not substitute for authenticated
business-account acceptance testing.

## PostgreSQL preference read fix

Existing preferences use Hibernate `@Lob`, including PostgreSQL large-object
references in the text column. `getPreference` must keep the repository query
and string materialization inside `@Transactional(readOnly = true)`. Without
that service boundary, saved settings can fail to reload with "Large Objects
may not be used in auto-commit mode". The fix preserves existing values and
storage format; it does not reset preferences or migrate/delete large objects.

`UserPreferenceServiceImplTest` exercises the actual Spring transaction proxy,
including read-only materialization, user isolation, missing keys, propagated
errors and writable saves. A read-only PostgreSQL check also reproduces the
unprotected failure and compares the transactional result against the existing
saved JSON, without printing user content or changing database records.
