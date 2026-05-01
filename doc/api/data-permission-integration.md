# Data Permission Integration Guide

This guide explains how to integrate the row-level and field-level data permission system into a new resource, controller, or service module.

---

## Architecture Overview

The system has two independent permission dimensions:

| Dimension | Entity | Controls |
|-----------|--------|----------|
| Row-level | `DataScope` | Which rows a user can see (filters injected via AOP + JPA Specification) |
| Field-level | `FieldScope` / `FieldScopeEntry` | How individual fields are presented (visible, masked, hidden, or editable) |

Both dimensions are resolved at login time and stored inside the user's `AuthorizationContext` (cached in Redis):

- `AuthorizationContext#dataScopeType` — `String` name of the winning `DataScopeType`
- `AuthorizationContext#deptIds` — set of department IDs for DEPT / DEPT_AND_BELOW / CUSTOM scopes
- `AuthorizationContext#fieldPermissions` — `Map<String, String>` keyed as `"resource#field"` → `FieldAccessType` name

### DataScopeType precedence (most-permissive wins across roles)

| Level | Type | Meaning |
|-------|------|---------|
| 4 | `ALL` | Access all data |
| 3 | `DEPT_AND_BELOW` | Own department + all sub-departments (BFS via org tree) |
| 2 | `DEPT` | Own department only |
| 1 | `CUSTOM` | Explicitly listed department IDs |
| 0 | `SELF` | Own data only (default when no scope is configured) |

### FieldAccessType precedence (most-permissive wins across roles)

| Level | Type | Meaning |
|-------|------|---------|
| 3 | `EDITABLE` | Full read + write |
| 2 | `VISIBLE` | Read-only, full value |
| 1 | `MASKED` | Read-only, partially obscured |
| 0 | `HIDDEN` | Not returned in responses |

---

## Row-Level Filtering

### Automatic filtering (recommended)

`BaseServiceImpl.limit()` is already annotated with `@DataScopeFilter(ownerField = "createdBy", deptField = "orgId")`. All services that extend `BaseServiceImpl` automatically get row-level filtering applied whenever the calling user has a `DataScope` configured on their role.

The `BaseRepositoryImpl` reads `DataScopeContext` inside `readSpecification()` and appends the appropriate JPA predicate automatically. No repository changes are needed.

If your entity uses standard field names (`createdBy` / `orgId`), you get filtering for free. If any field is missing on the entity, the predicate for that field is silently skipped — there is no error.

### Custom field names

If your service overrides `limit()` with different field names, annotate the override:

```java
import org.simplepoint.core.datascopeannotation.DataScopeFilter;

@Override
@DataScopeFilter(ownerField = "authorId", deptField = "departmentId")
public <S extends Article> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    return super.limit(attributes, pageable);
}
```

### Opting out

To disable row-level filtering for a specific service's list endpoint, override `limit()` without the annotation:

```java
@Override
public <S extends AuditLog> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    // audit logs are intentionally exempt from data scope filtering
    return super.limit(attributes, pageable);
}
```

### Reading the condition manually (advanced)

If you need to build a custom query outside of `BaseServiceImpl.limit()`, you can read the condition directly. Annotate your method with `@DataScopeFilter` first, then:

```java
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.core.datascopeannotation.DataScopeContext;

DataScopeCondition condition = DataScopeContext.get();
if (condition != null && !condition.isAllData()) {
    if (condition.isSelf()) {
        // filter by condition.getUserId() on ownerField
    } else if (condition.getDeptIds() != null) {
        // filter by condition.getDeptIds() on deptField
    }
}
```

`DataScopeCondition` provides:

| Method | Description |
|--------|-------------|
| `isAllData()` | `true` when scope type is `ALL` |
| `isSelf()` | `true` when scope type is `SELF` |
| `getDeptIds()` | Non-null set for DEPT / DEPT_AND_BELOW / CUSTOM; `null` otherwise |
| `getUserId()` | Current user's ID |
| `getDeptField()` | Value of `@DataScopeFilter#deptField` |
| `getOwnerField()` | Value of `@DataScopeFilter#ownerField` |

> **Spring proxy boundary warning:** `@DataScopeFilter` is intercepted by Spring AOP, which only fires when a method is called through the Spring proxy — i.e., from outside the bean, via an injected reference. Internal calls such as `this.limit(...)`, constructor calls, or calls from private/final methods bypass the proxy and will **not** trigger data scope filtering. If you need filtering in such cases, extract the call to a separate Spring bean or invoke the method through the self-injected proxy.

---

## Field-Level Filtering

Field permissions are enforced **automatically** via a Jackson `BeanSerializerModifier` registered in `FieldScopeJacksonModule`. No controller or service code is needed.

When an object is serialized to JSON:
- Fields with `HIDDEN` permission are omitted entirely from the response.
- Fields with `MASKED` permission are replaced with a partially obscured string (first 3 chars + `****` + last char).
- Fields with `VISIBLE` or `EDITABLE` permission are returned as-is.

The lookup key used is `"SimpleClassName#fieldName"` — e.g., `"User#phoneNumber"`. This must match the value of `FieldScopeEntry#field` and `FieldScopeEntry#resource` stored in the database.

### Write-side enforcement

`BaseServiceImpl` also enforces field permissions on writes:
- **`create()`**: Non-EDITABLE fields (HIDDEN/MASKED/VISIBLE) supplied by the client are cleared to `null` before the entity is persisted. Primitive fields are not affected.
- **`modifyById()`**: Non-EDITABLE fields are excluded from the set of modifiable fields, so the DB-persisted value is automatically preserved regardless of what the client sent.

> **Note:** If a non-EDITABLE field has a `NOT NULL` DB constraint, marking it as HIDDEN/MASKED in a FieldScope is a configuration error — creation of new records would fail at the DB level. Only mark fields HIDDEN/MASKED that are either auto-populated by auditing or have a DB default.

### Registering a field for masking

Create a `FieldScope` with one or more `FieldScopeEntry` records:

```
POST /field-scopes
{ "name": "Sensitive User Fields" }

PUT /field-scopes/{id}/entries
[
  { "resource": "User", "field": "phoneNumber", "access": "MASKED" },
  { "resource": "User", "field": "idCard",      "access": "HIDDEN" }
]
```

Then assign the `FieldScope` to a role (see [Configuration Management](#configuration-management)).

---

## Configuration Management

### DataScope (row-level)

REST endpoints: `GET /data-scopes`, `POST /data-scopes`, `PUT /data-scopes/{id}`, `DELETE /data-scopes`

### FieldScope (field-level)

REST endpoints: `GET /field-scopes`, `POST /field-scopes`, `PUT /field-scopes/{id}`, `DELETE /field-scopes`

Replace entries for a scope: `PUT /field-scopes/{id}/entries`

### Assign scopes to a role

Scopes are assigned per role via the role permissions UI, or directly through the API:

```
GET  /roles/scope-assignment?roleId={roleId}
PUT  /roles/scope-assignment
Body: { "roleId": "...", "dataScopeId": "...", "fieldScopeId": "..." }
```

The scope assignment is also included when authorizing role permissions:

```
PUT /roles/{roleId}/permissions
Body: { "permissionIds": [...], "dataScopeId": "...", "fieldScopeId": "..." }
```

---

## User Organization Membership

For `DEPT` and `DEPT_AND_BELOW` scope types to work, users must have an `orgId` field populated (references the `Organization` entity). This is set when creating or updating a user:

```json
POST /users
{
  "username": "alice",
  "orgId": "<organization-id>"
}
```

The authorization service performs a BFS traversal of the `Organization` tree (via `OrganizationRepository#findIdsByParentIds`) to compute the full set of department IDs for `DEPT_AND_BELOW`.

---

## Cache Invalidation

`AuthorizationContext` is cached in Redis with a 2-hour TTL. Mutations to `DataScope` or `FieldScope` automatically increment the tenant's permission version (same mechanism as role/permission mutations), which causes the next request to recompute the context.

No manual cache flushing is required.

---

## Entity Field Conventions

To make filtering work correctly, entities that need row-level filtering should follow these field naming conventions (or configure alternatives via `@DataScopeFilter`):

| Convention field | Default annotation value | Description |
|-----------------|--------------------------|-------------|
| `createdBy` | `ownerField = "createdBy"` | Populated automatically by JPA auditing |
| `orgId` | `deptField = "orgId"` | Department the record belongs to |

If your entity uses different field names, pass them explicitly:

```java
@DataScopeFilter(ownerField = "authorId", deptField = "departmentId")
```

