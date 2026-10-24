package org.simplepoint.plugin.rbac.tenant.api.model;

import java.time.Instant;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TablePagination;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;

/** Package summary; duration is a credit per resource, never a shared package expiration. */
@TableQuery(
    value = {
      @QueryField("packageCode"),
      @QueryField("packageName"),
      @QueryField("durationDays"),
      @QueryField("state"),
      @QueryField("assignedAt"),
      @QueryField("activatedAt"),
      @QueryField("revokedAt"),
      @QueryField("resourceCount"),
      @QueryField("acquisition"),
      @QueryField("adoptedAt")
    },
    maxPageSize = TablePagination.MAX_PAGE_SIZE)
public record TenantPackageInstanceView(
    String id,
    String packageCode,
    String packageName,
    Integer durationDays,
    TenantPackageInstance.State state,
    Instant assignedAt,
    Instant activatedAt,
    Instant revokedAt,
    int resourceCount,
    TenantPackageInstance.Acquisition acquisition,
    Instant adoptedAt,
    java.util.List<String> allowedActions) {
  /** Capability lists are immutable projections, not caller-supplied permissions. */
  public TenantPackageInstanceView {
    allowedActions = java.util.List.copyOf(allowedActions);
  }

  /** Owner projection used by the scalar persistence query and ordinary activation receipts. */
  public TenantPackageInstanceView(
      String id,
      String packageCode,
      String packageName,
      Integer durationDays,
      TenantPackageInstance.State state,
      Instant assignedAt,
      Instant activatedAt,
      Instant revokedAt,
      int resourceCount,
      TenantPackageInstance.Acquisition acquisition,
      Instant adoptedAt) {
    this(
        id,
        packageCode,
        packageName,
        durationDays,
        state,
        assignedAt,
        activatedAt,
        revokedAt,
        resourceCount,
        acquisition,
        adoptedAt,
        acquisition == TenantPackageInstance.Acquisition.ASSIGNED
                && state == TenantPackageInstance.State.PENDING
                && activatedAt == null
                && revokedAt == null
                && resourceCount > 0
            ? java.util.List.of("resources", "activate")
            : java.util.List.of("resources"));
  }

  /** Retains the established JSON property and Java accessor for current-owner clients. */
  public java.util.List<String> getAllowedActions() {
    return allowedActions;
  }

  /** Only the platform administration boundary may choose this action projection. */
  public TenantPackageInstanceView forAdministration() {
    return new TenantPackageInstanceView(
        id,
        packageCode,
        packageName,
        durationDays,
        state,
        assignedAt,
        activatedAt,
        revokedAt,
        resourceCount,
        acquisition,
        adoptedAt,
        revokedAt == null
                && (state == TenantPackageInstance.State.PENDING
                    || state == TenantPackageInstance.State.ACTIVE)
            ? java.util.List.of("resources", "revoke")
            : java.util.List.of("resources"));
  }

  /** Copies scalars without exposing persistence collections. */
  public static TenantPackageInstanceView of(TenantPackageInstance instance) {
    boolean namedPackage =
        instance.getAcquisition() == TenantPackageInstance.Acquisition.ASSIGNED
            || instance.getAcquisition() == TenantPackageInstance.Acquisition.LEGACY_PACKAGE;
    return new TenantPackageInstanceView(
        instance.getId(),
        namedPackage ? instance.getPackageCode() : null,
        namedPackage ? instance.getPackageName() : null,
        instance.getDurationDays(),
        instance.getState(),
        instance.getAssignedAt(),
        instance.getAcquisition() == TenantPackageInstance.Acquisition.ASSIGNED
            ? instance.getActivatedAt()
            : null,
        instance.getRevokedAt(),
        instance.getResourceIds().size(),
        instance.getAcquisition(),
        instance.getAdoptedAt());
  }
}
