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

### 1. Annotate the query/service method

```java
import org.simplepoint.core.datascopeannotation.DataScopeFilter;

@DataScopeFilter(ownerField = "createdBy", deptField = "orgId")
public Page<MyEntity> list(Pageable pageable) { ... }
```

- `ownerField` — the JPA entity field name that stores the record owner's user ID
- `deptField` — the JPA entity field name that stores the record's department ID (optional)

### 2. Read the resolved condition and apply it

The AOP aspect (`DataScopeAspect`) intercepts the annotated method and populates `DataScopeContext` on the current thread. Retrieve it and build a JPA `Specification`:

```java
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.core.datascopeannotation.DataScopeContext;

DataScopeCondition condition = DataScopeContext.get();

Specification<MyEntity> spec = (root, query, cb) -> {
    if (condition == null || condition.isAllData()) {
        return cb.conjunction(); // no filter
    }
    if (condition.isSelf()) {
        return cb.equal(root.get("createdBy"), condition.getCurrentUserId());
    }
    if (condition.getDeptIds() != null && !condition.getDeptIds().isEmpty()) {
        return root.get(condition.getDeptField()).in(condition.getDeptIds());
    }
    return cb.conjunction();
};

return repository.findAll(spec, pageable);
```

`DataScopeCondition` provides:

| Method | Description |
|--------|-------------|
| `isAllData()` | `true` when scope type is `ALL` |
| `isSelf()` | `true` when scope type is `SELF` |
| `getDeptIds()` | Non-null set for DEPT / DEPT_AND_BELOW / CUSTOM; `null` otherwise |
| `getCurrentUserId()` | Current user's ID |
| `getDeptField()` | Value of `@DataScopeFilter#deptField` |
| `getOwnerField()` | Value of `@DataScopeFilter#ownerField` |

---

## Field-Level Filtering

Field permissions are read directly from `AuthorizationContext` at the controller or service layer. No AOP is involved.

```java
import org.simplepoint.core.AuthorizationContext;

// Inject via SecurityContextHolder or pass through from the controller
AuthorizationContext ctx = ...; // e.g. from SecurityContextHolder or parameter

Map<String, String> fieldPerms = ctx.getFieldPermissions(); // may be null
String key = "MyEntity#salary"; // "resource#field"

String accessType = fieldPerms != null ? fieldPerms.get(key) : null;
if ("HIDDEN".equals(accessType)) {
    entity.setSalary(null);
} else if ("MASKED".equals(accessType)) {
    entity.setSalary(mask(entity.getSalary()));
}
```

> **Tip:** Define constants for your resource identifier (e.g. `"MyEntity"`) to avoid magic strings.

---

## Configuration Management

### DataScope (row-level)

REST endpoint: `POST /data-scopes`, `PUT /data-scopes/{id}`, `DELETE /data-scopes`, `GET /data-scopes`

Assign a `DataScope` to a role:

```json
PUT /roles/{roleId}/permissions
{
  "permissionIds": ["..."],
  "dataScopeId": "<data-scope-id>",
  "fieldScopeId": "<field-scope-id>"
}
```

### FieldScope (field-level)

REST endpoint: `POST /field-scopes`, `PUT /field-scopes/{id}`, `DELETE /field-scopes`, `GET /field-scopes`

Replace entries for a scope:

```
PUT /field-scopes/{id}/entries
Body: List<FieldScopeEntryDto>
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
