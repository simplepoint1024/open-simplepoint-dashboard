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

/** Server-owned authorization projection from a page resource to a physical model. */
@Entity
@Getter
@Setter
@AttributeOverrides({
    @AttributeOverride(name = "id", column = @Column(name = "id", length = 36)),
    @AttributeOverride(name = "createdBy", column = @Column(name = "created_by", length = 64)),
    @AttributeOverride(name = "updatedBy", column = @Column(name = "updated_by", length = 64))
})
@Table(
    name = "simpoint_dna_model_resource_bindings",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_dna_model_resource_binding",
        columnNames = {"tenant_id", "physical_model_id", "resource_code"}
    ),
    indexes = @Index(
        name = "idx_dna_model_resource_lookup",
        columnList = "tenant_id,resource_code,physical_model_id"
    )
)
public class PhysicalModelResourceBindingEntity extends TenantBaseEntityImpl<String> {

  @Column(name = "physical_model_id", length = 36, nullable = false, updatable = false)
  private String physicalModelId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "physical_model_id",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_dna_model_resource_model")
  )
  private PhysicalModelEntity physicalModel;

  @Column(name = "resource_code", length = 160, nullable = false, updatable = false)
  private String resourceCode;
}
