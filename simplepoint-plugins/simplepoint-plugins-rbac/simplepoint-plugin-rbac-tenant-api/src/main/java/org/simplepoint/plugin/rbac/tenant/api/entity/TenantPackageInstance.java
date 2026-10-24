package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKey;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/** One assigned package purchase; its resource set and duration are frozen before activation. */
@Data
@Entity
@Table(name = "simpoint_saas_tenant_package_instances")
public class TenantPackageInstance {
  /** Activation is irreversible; revocation retains the original activation evidence. */
  public enum State {
    PENDING,
    ACTIVE,
    REVOKED
  }

  /**
   * Distinguishes ordinary assignments from facts explicitly adopted from the legacy authorization
   * model.
   */
  public enum Acquisition {
    ASSIGNED,
    LEGACY_PACKAGE,
    LEGACY_PUBLIC,
    LEGACY_ROLE,
    LEGACY_HOST
  }

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false, updatable = false)
  private Acquisition acquisition = Acquisition.ASSIGNED;

  /** Adoption time is not the unknown historical purchase or activation time. */
  @Column(name = "adopted_at", updatable = false)
  private Instant adoptedAt;

  @Id
  @Column(length = 36, nullable = false, updatable = false)
  private String id;

  @Column(name = "tenant_id", length = 36, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "package_code", length = 128, nullable = false, updatable = false)
  private String packageCode;

  @Column(name = "package_name", length = 128, nullable = false, updatable = false)
  private String packageName;

  @Column(name = "duration_days", updatable = false)
  private Integer durationDays;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private State state = State.PENDING;

  @OneToMany(cascade = CascadeType.PERSIST)
  @JoinColumn(
      name = "instance_id",
      referencedColumnName = "id",
      insertable = false,
      updatable = false)
  @MapKey(name = "resourceId")
  private Map<String, TenantPackageResourceDefinition> resources = new LinkedHashMap<>();

  /** Binds purchase content to this instance before the aggregate is persisted. */
  public void setResources(Map<String, TenantPackageResourceDefinition> definitions) {
    if (id == null || id.isBlank() || !resources.isEmpty()) {
      throw new IllegalStateException(
          "Package content must be assigned once after its instance identity");
    }
    var bound = new LinkedHashMap<String, TenantPackageResourceDefinition>();
    definitions.forEach(
        (resourceId, definition) -> {
          var copy =
              new TenantPackageResourceDefinition(
                  definition.getResourceCode(), definition.getResourceName());
          copy.setInstanceId(id);
          copy.setResourceId(resourceId);
          bound.put(resourceId, copy);
        });
    this.resources = bound;
  }

  /**
   * Exposes purchase content without permitting collection replacement through the read accessor.
   */
  public Map<String, TenantPackageResourceDefinition> getResources() {
    return java.util.Collections.unmodifiableMap(resources);
  }

  /** Returns stable purchased identities without exposing a mutable map-key view. */
  public Set<String> getResourceIds() {
    return java.util.Collections.unmodifiableSet(resources.keySet());
  }

  @Column(name = "assigned_at", nullable = false, updatable = false)
  private Instant assignedAt;

  @Column(name = "assigned_by", length = 36, nullable = false, updatable = false)
  private String assignedBy;

  @Column(name = "activated_at")
  private Instant activatedAt;

  @Column(name = "activated_by", length = 36)
  private String activatedBy;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoked_by", length = 36)
  private String revokedBy;
}
