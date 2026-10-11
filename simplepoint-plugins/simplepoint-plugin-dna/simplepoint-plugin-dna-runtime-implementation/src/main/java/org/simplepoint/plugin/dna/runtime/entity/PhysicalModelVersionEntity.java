package org.simplepoint.plugin.dna.runtime.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;

/** Immutable serialized target schema for one physical model version. */
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
    name = "simpoint_dna_physical_model_versions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_dna_physical_model_version",
        columnNames = {"tenant_id", "physical_model_id", "version_number"}
    ),
    indexes = @Index(
        name = "idx_dna_physical_model_version_checksum",
        columnList = "physical_model_id,schema_checksum"
    )
)
@org.simplepoint.core.query.TableQuery(value = {
    @org.simplepoint.core.query.QueryField("physicalModelId"),
    @org.simplepoint.core.query.QueryField("versionNumber"),
    @org.simplepoint.core.query.QueryField("contractVersion"),
    @org.simplepoint.core.query.QueryField("schemaChecksum"),
    @org.simplepoint.core.query.QueryField("createdAt")
}, maxPageSize = 100)
public class PhysicalModelVersionEntity extends TenantBaseEntityImpl<String> {

  @Column(name = "physical_model_id", length = 36, nullable = false, updatable = false)
  private String physicalModelId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "physical_model_id",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_dna_physical_version_model")
  )
  private PhysicalModelEntity physicalModel;

  @Column(name = "version_number", nullable = false, updatable = false)
  private int versionNumber;

  @Column(name = "contract_version", length = 16, nullable = false, updatable = false)
  private String contractVersion;

  @Column(name = "schema_json", columnDefinition = "TEXT", nullable = false, updatable = false)
  private String schemaJson;

  @Column(
      name = "schema_checksum",
      length = 64,
      nullable = false,
      updatable = false
  )
  private String schemaChecksum;
}
