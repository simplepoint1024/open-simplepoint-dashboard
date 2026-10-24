package org.simplepoint.plugin.rbac.tenant.api.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.PermissionFields;
import org.simplepoint.core.annotation.PermissionResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;

/**
 * Owner-facing point-in-time projection; time entitlement does not imply an ordinary role grant.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@PermissionResource(TenantResource.class)
public class TenantResourceView extends TenantResource {
  /** Effective tenant capability state, independent of an individual role grant. */
  public enum Availability {
    AVAILABLE,
    OFFLINE,
    DISABLED,
    SOURCE_UNAVAILABLE,
    NO_ENTITLEMENT,
    NOT_STARTED,
    EXPIRED,
    DEPENDENCY_UNAVAILABLE
  }

  /** How this resource obtains its own time entitlement; dependencies remain separate. */
  public enum EntitlementKind {
    BASELINE,
    TIMED,
    UNLIMITED,
    NOT_REQUIRED,
    NONE
  }

  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  @PermissionFields(
      resource = TenantResource.class,
      value = {
        "enabled",
        "manualDisabled",
        "formallyOffline",
        "sourceAvailable",
        "scopeTypes",
        "parentId",
        "targetResourceId",
        "managedHostResourceId",
        "platformResourceId",
        "baselineEntitled",
        "type",
        "grantable"
      })
  private Availability availability;

  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  @PermissionFields(
      resource = TenantResource.class,
      value = {"platformResourceId", "baselineEntitled", "type", "grantable"})
  private EntitlementKind entitlementKind;

  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  @PermissionFields(
      resource = TenantResource.class,
      value = {"platformResourceId", "baselineEntitled", "type", "grantable"})
  private Instant entitlementStartsAt;

  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  @PermissionFields(
      resource = TenantResource.class,
      value = {"platformResourceId", "baselineEntitled", "type", "grantable"})
  private Instant entitlementExpiresAt;
}
