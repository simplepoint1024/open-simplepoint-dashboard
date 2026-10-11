package org.simplepoint.plugin.dna.runtime.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalIsolationMode;

/** Server-owned binding from a model identity to one immutable physical table. */
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
    name = "simpoint_dna_physical_bindings",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_dna_physical_binding_model",
            columnNames = "physical_model_id"
        ),
        @UniqueConstraint(
            name = "uk_dna_physical_binding_target",
            columnNames = {
              "data_source_id", "catalog_name", "schema_name", "table_name"
            }
        )
    },
    indexes = @Index(
        name = "idx_dna_physical_binding_tenant",
        columnList = "tenant_id,physical_model_id"
    )
)
public class PhysicalModelBindingEntity extends TenantBaseEntityImpl<String> {

  @Column(name = "physical_model_id", length = 36, nullable = false, updatable = false)
  private String physicalModelId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "physical_model_id",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_dna_physical_binding_model")
  )
  private PhysicalModelEntity physicalModel;

  @Column(name = "data_source_id", length = 64, nullable = false)
  private String dataSourceId;

  @Column(name = "catalog_name", length = 128, nullable = false)
  private String catalogName;

  @Column(name = "schema_name", length = 128, nullable = false)
  private String schemaName;

  @Column(name = "table_name", length = 128, nullable = false)
  private String tableName;

  @Enumerated(EnumType.STRING)
  @Column(name = "isolation_mode", length = 32, nullable = false)
  private PhysicalIsolationMode isolationMode;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;
}
