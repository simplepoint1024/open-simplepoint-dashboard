# Data Permission Integration Guide

## Security contract

The effective authorization context is resolved after JWT authentication, using the verified JWT subject. Client `X-Context-Id` is a hint, not a credential. Caller-supplied user, organization, scope and administrator attributes are not forwarded to policy calculation.

A cache entry is keyed by subject + tenant + selected role + current tenant authorization version. Every request checks the current committed version before reuse. Unversioned scopes are recalculated rather than cached. Deploy the authorization provider and resource-server consumers together; old providers do not expose the new `currentVersion` remote method.

| Dimension | Storage | Enforcement |
|-----------|---------|-------------|
| Tenant | Tenant-aware entity + active tenant context | Query filter and explicit by-ID ownership check |
| Rows | `DataScope` | Base service checks and JPA predicates |
| Fields | `FieldScope` / `FieldScopeEntry` | Jackson output, base writes and JSON Schema |
| Role assignment | `RoleScopeBinding` | One independent, revisioned binding per tenant + role |

An explicit `ALL` row scope never disables tenant isolation. A platform administrator without a selected tenant retains the explicit platform-level exception. Security/configuration services can bypass row scope, but still require their own tenant and function authorization.

## Row-level filtering

Base service defaults use `createdBy` and `createOrgDeptId`. The user's current `orgId` is an input to authorization resolution, not the default department field of a business row.

| Scope | Effective rule within the current tenant |
|-------|-----------------------------------------|
| ALL | All rows |
| SELF | Creator equals the authenticated user |
| DEPT | Current valid organization |
| DEPT_AND_BELOW | Current organization and tenant-qualified descendants |
| CUSTOM | Selected organization IDs |
| Missing/unknown execution context | Deny; never silently use ALL |

A normally authenticated user with no configured data scope defaults to SELF. Multiple configured roles retain the existing union semantics: department sets are combined, SELF can be OR-ed with departments, and ALL wins. A missing department retains the existing SELF fallback. New CUSTOM policies must contain valid, active organizations from the current tenant.

`BaseServiceImpl` protects page contents/totals, counts, existence checks, by-ID reads, creates, updates and deletes. Bulk deletion requires explicit ALL and is disabled for scope-policy/organization management.

Custom field names must be configured consistently:

```java
@Override
@DataScopeFilter(ownerField = "authorId", deptField = "departmentId")
public <S extends Article> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    return super.limit(attributes, pageable);
}

@Override
protected String dataScopeOwnerField() { return "authorId"; }

@Override
protected String dataScopeDeptField() { return "departmentId"; }
```

Omitting an annotation does not disable base-service checks. Only reviewed security/configuration services should override `isDataScopeApplicable()` to return false. This is not an exemption from tenant or endpoint authorization.

Custom SQL, repositories called directly, exports, background jobs and external query engines do not acquire automatic protection merely by sharing an entity. They must explicitly apply tenant and row predicates. Missing conditions must deny access. Base methods install a fallback condition even on self-invocation; AOP-only custom methods still require a Spring proxy.

## Field-level enforcement

New field entries use a fully qualified entity name and Java property name, for example `org.simplepoint.security.entity.User#phoneNumber`. Unambiguous legacy simple names remain accepted and are normalized on save. Existing legacy policies remain readable. Renaming/moving a resource class requires a policy migration.

| Access | Response | Base write / schema |
|--------|----------|---------------------|
| EDITABLE | Original value | Writable |
| VISIBLE | Original value | Read-only |
| MASKED | Constant `***` (null stays null) | Read-only; sensitive defaults/examples removed |
| HIDDEN | Omitted | Read-only; removed from schema properties and required list |

For the same stored key, multiple roles retain most-permissive merge semantics. Unspecified fields remain unrestricted. During mixed canonical/legacy configuration, the stricter of the two aliases is used to avoid unintentionally weakening an old policy.

Serialization does not modify managed entities. On creation, protected reference fields are cleared; protected primitives reject creation because an absent value cannot be distinguished from a supplied primitive default. Use nullable domain fields or a reviewed explicit server-side creation policy where needed. A database default does not necessarily apply when Hibernate sends an explicit null: required values need server-side handling. Updates preserve protected database values.

Application bean serialization is automatic when the ObjectMapper registers `FieldScopeJacksonModule`. A DTO with matching property names can share its entity policy:

```java
@PermissionResource(User.class)
public class UserView {
    public String phoneNumber;
}
```

Maps have no reliable entity identity. A projection adapter must supply it explicitly:

```java
return FieldPermissionPolicy.project(User.class, projection);
```

This helper is a shallow, named-field projection, not a generic recursive scrubber. DTOs with renamed fields, nested projections, custom serializers and raw SQL/Map exports require explicit adapters and endpoint tests. The current schema filtering covers the generated top-level entity properties, not arbitrary nested schema composition.

## Configuration API

All paths below are backend paths; the frontend may add its configured gateway prefix.

- `GET/POST/PUT/DELETE /data-scopes`; PUT carries the ID in its body.
- `GET/POST/PUT/DELETE /field-scopes`; POST/PUT only edit policy metadata.
- `GET /field-scopes/catalog` returns this service's managed entity/field catalog.
- `PUT /field-scopes/entries?fieldScopeId={id}` replaces validated entries (maximum 500).
- `GET /roles/scope-assignment?roleId={id}` reads the independent binding or legacy state.
- `PUT /roles/scope-assignment` saves both scope selections together.
- `GET /data-scopes/effective` shows only the caller's verified effective scope, departments, field policies and version.

Example assignment:

```json
{
  "roleId": "role-id",
  "dataScopeId": "data-policy-id",
  "fieldScopeId": "field-policy-id",
  "revision": 2,
  "confirmLegacyReplacement": false
}
```

For an unbound role, revision is null. After every successful write, read the new revision before another edit. A stale revision rejects the write. A role with zero resource grants can still save a scope binding. Null data scope explicitly restores SELF; null field scope removes field restrictions.

Existing grants are a compatibility fallback only when no independent binding exists. All legacy combinations are preserved on read; conflicting combinations require explicit confirmation before replacement. An independently saved empty binding suppresses legacy fallback. Resource-only saves can use `updateScope: false` on the access-center API; full access-center saves must include the scope revision.

Role binding writes require the current tenant owner or administrator. Supplied policy IDs must belong to the tenant and not be deleted. Unknown/duplicate fields are rejected before mutating entries; existing rows are reused to avoid unique-key collisions.

The field catalog currently covers the common service's persistence unit, not a distributed AI/DNA catalog. Existing remote-module policies can still be resolved, but registering/editing new remote fields needs a catalog contribution from that module. The effective preview is not another-user impersonation or an unsaved-policy simulator, and does not prove that every business endpoint has integrated enforcement.

## Version invalidation and operations

Policy/role edits and organization changes increment the tenant authorization version in the same database transaction. User modification refreshes every tenant returned by the user's membership lookup, since the current User model stores organization globally. Failed transactions must not publish a new version. A new request checks the committed version; requests already in flight are not forcibly terminated. The two-hour cache TTL is a retention limit, not the revocation delay.

Before deleting a policy, detach it from all effective roles. A dangling/deleted policy is fail-closed and can deny requests until repaired. This release does not add policy-reference deletion prevention.

Apply the additive [PostgreSQL migration](../deployment/sql/20260908-role-scope-binding.sql) before deploying the updated provider/consumers. No bulk legacy data rewrite is required. Do not drop legacy scope columns. The [rollout notes](../deployment/data-permission-hardening.md) describe validation and rollback constraints.
