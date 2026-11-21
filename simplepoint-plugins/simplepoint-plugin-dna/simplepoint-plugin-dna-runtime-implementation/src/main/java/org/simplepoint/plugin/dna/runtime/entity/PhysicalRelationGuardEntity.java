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
 * Durable migration participant. Failure never releases it; verified activation commits release.
 */
@Entity
@Getter
@Setter
@AttributeOverrides({
  @AttributeOverride(name = "id", column = @Column(name = "id", length = 36)),
  @AttributeOverride(name = "createdBy", column = @Column(name = "created_by", length = 64)),
  @AttributeOverride(name = "updatedBy", column = @Column(name = "updated_by", length = 64))
})
@Table(name = "simpoint_dna_relation_guards")
public class PhysicalRelationGuardEntity extends TenantBaseEntityImpl<String> {
  @Column(name = "migration_id", length = 36, nullable = false, updatable = false)
  private String migrationId;

  @Column(name = "model_id", length = 36, nullable = false, updatable = false)
  private String modelId;

  @Column(nullable = false)
  private boolean released;
}
