package org.simplepoint.plugin.rbac.tenant.api.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.security.entity.ResourceType;

/** Tenant-owned catalog entry. Platform entries retain the exact platform resource ID. */
@Data
@EqualsAndHashCode(of = {"tenantId", "id"})
@Entity
@Schema(description = "租户独立资源。平台来源项在租户内保留原平台 ID 与编码")
@IdClass(TenantResourceKey.class)
@Table(name = "simpoint_saas_tenant_resources", uniqueConstraints = {
    @UniqueConstraint(name = "uq_tenant_resource_code", columnNames = {"tenant_id", "code"}),
    @UniqueConstraint(name = "uq_tenant_resource_source", columnNames = {"tenant_id", "platform_resource_id"}),
    @UniqueConstraint(
        name = "uq_tenant_resource_managed_key",
        columnNames = {"tenant_id", "managed_owner", "managed_key"}
    )
})
public class TenantResource {
  @Id
  @Schema(description = "所属租户 ID", accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "tenant_id", length = 36, nullable = false)
  private String tenantId;

  @Id
  @Schema(description = "租户内资源 ID；平台来源时等于平台资源 ID", accessMode = Schema.AccessMode.READ_ONLY)
  @Column(length = 36, nullable = false)
  private String id;

  @Column(name = "platform_resource_id", length = 36)
  private String platformResourceId;

  /** Optional licensed platform page backing a tenant-created page alias. */
  @Schema(description = "租户自建页面所绑定的已授权平台页面 ID")
  @Column(name = "target_resource_id", length = 36)
  private String targetResourceId;

  /** Service identity that owns this generated resource. */
  @Schema(description = "服务托管资源的所有者", accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "managed_owner", length = 64)
  private String managedOwner;

  /** Stable aggregate-local identity supplied by the owning service. */
  @Schema(description = "服务托管资源的稳定业务键", accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "managed_key", length = 160)
  private String managedKey;

  /** Platform page whose trusted component renders a managed page. */
  @Schema(description = "服务托管页面的受信运行时宿主页", accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "managed_host_resource_id", length = 36)
  private String managedHostResourceId;

  /** Trusted permission catalog published atomically with a managed page generation. */
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  @Schema(hidden = true, accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "permission_metadata", columnDefinition = "TEXT")
  private String permissionMetadata;

  /** Monotonic publication fence; formal offline cannot be undone by an old activation. */
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "lifecycle_generation", nullable = false)
  private long lifecycleGeneration;

  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  @Column(name = "formally_offline", nullable = false)
  private boolean formallyOffline;

  @Column(name = "parent_id", length = 36)
  private String parentId;

  @Column(length = 120, nullable = false)
  @Schema(description = "资源编码；平台来源时保留平台编码", accessMode = Schema.AccessMode.READ_ONLY)
  private String code;

  @Column(length = 100, nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false)
  private ResourceType type;

  @Column(length = 200)
  private String path;

  @Column(length = 160)
  private String component;

  @Column(name = "route_kind", length = 32)
  private String routeKind;

  @Column(length = 100)
  private String icon;

  private Integer sort;

  @Column(length = 255)
  private String description;

  @Column(nullable = false)
  private boolean enabled = true;

  @Column(nullable = false)
  private boolean grantable;

  @Column(name = "local_modified", nullable = false)
  private boolean localModified;

  /** Local visibility override; separate from upstream entitlement revocation. */
  @Column(name = "manual_disabled", nullable = false)
  private boolean manualDisabled;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  public boolean isPlatformSource() {
    return platformResourceId != null;
  }

  public boolean isManaged() {
    return managedOwner != null;
  }

  @JsonProperty("origin")
  @Schema(description = "PLATFORM 平台来源、MANAGED 服务托管或 TENANT 租户自建", accessMode = Schema.AccessMode.READ_ONLY)
  public TenantResourceOrigin getOrigin() {
    if (isPlatformSource()) {
      return TenantResourceOrigin.PLATFORM;
    }
    return isManaged() ? TenantResourceOrigin.MANAGED : TenantResourceOrigin.TENANT;
  }
}
