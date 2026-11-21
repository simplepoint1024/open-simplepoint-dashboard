package org.simplepoint.plugin.dna.runtime.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;

/**
 * Immutable per-version storage dependency; the source model's active version selects the live
 * snapshot.
 */
@Entity
@Getter
@Setter
@AttributeOverrides({
  @AttributeOverride(name = "id", column = @Column(name = "id", length = 36)),
  @AttributeOverride(name = "createdBy", column = @Column(name = "created_by", length = 64)),
  @AttributeOverride(name = "updatedBy", column = @Column(name = "updated_by", length = 64))
})
@Table(name = "simpoint_dna_relation_definitions")
public class PhysicalRelationDefinitionEntity extends TenantBaseEntityImpl<String> {
  @Column(name = "source_model_id", length = 36, nullable = false, updatable = false)
  private String sourceModelId;

  @Column(name = "source_root_model_id", length = 36, nullable = false, updatable = false)
  private String sourceRootModelId;

  @Column(name = "source_version", nullable = false, updatable = false)
  private int sourceVersion;

  @Column(name = "relation_id", length = 64, nullable = false, updatable = false)
  private String relationId;

  @Column(name = "field_id", length = 64, nullable = false, updatable = false)
  private String fieldId;

  @Column(name = "column_name", length = 63, nullable = false, updatable = false)
  private String columnName;

  @Column(name = "target_model_id", length = 36, nullable = false, updatable = false)
  private String targetModelId;

  @Column(name = "unique_target", nullable = false, updatable = false)
  private boolean uniqueTarget;

  @Column(name = "data_source_id", length = 64, nullable = false, updatable = false)
  private String dataSourceId;

  @Column(name = "catalog_name", length = 128, nullable = false, updatable = false)
  private String catalogName;

  @Column(name = "schema_name", length = 128, nullable = false, updatable = false)
  private String schemaName;

  @Column(name = "source_table", length = 128, nullable = false, updatable = false)
  private String sourceTable;

  @Column(name = "target_table", length = 128, nullable = false, updatable = false)
  private String targetTable;
}
