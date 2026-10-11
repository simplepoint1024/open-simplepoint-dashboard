package org.simplepoint.plugin.dna.runtime.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;

/** Persistent identity and lifecycle state of a form-owned physical model. */
@Entity
@Getter
@Setter
@AttributeOverrides({
    @AttributeOverride(name = "id", column = @Column(name = "id", length = 36)),
    @AttributeOverride(
        name = "createdBy",
        column = @Column(name = "created_by", length = 64)
    ),
    @AttributeOverride(
        name = "updatedBy",
        column = @Column(name = "updated_by", length = 64)
    )
})
@Table(
    name = "simpoint_dna_physical_models",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_dna_physical_model_form",
        columnNames = {"tenant_id", "app_id", "form_id"}
    ),
    indexes = {
      @Index(name = "idx_dna_physical_model_status", columnList = "tenant_id,status"),
      @Index(name = "idx_dna_physical_model_updated", columnList = "updated_at")
    }
)
@org.simplepoint.core.query.TableQuery(value = {
    @org.simplepoint.core.query.QueryField("displayName"),
    @org.simplepoint.core.query.QueryField("appId"),
    @org.simplepoint.core.query.QueryField("formId"),
    @org.simplepoint.core.query.QueryField("status"),
    @org.simplepoint.core.query.QueryField("latestVersion"),
    @org.simplepoint.core.query.QueryField("activeVersion"),
    @org.simplepoint.core.query.QueryField("updatedAt")
}, maxPageSize = 100)
public class PhysicalModelEntity extends TenantBaseEntityImpl<String> {

  @Column(name = "app_id", length = 64, nullable = false, updatable = false)
  private String appId;

  @Column(name = "form_id", length = 64, nullable = false, updatable = false)
  private String formId;

  @Column(name = "display_name", length = 128, nullable = false)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(length = 24, nullable = false)
  private PhysicalModelStatus status;

  @Column(name = "latest_version", nullable = false)
  private int latestVersion;

  @Column(name = "active_version")
  private Integer activeVersion;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;
}
