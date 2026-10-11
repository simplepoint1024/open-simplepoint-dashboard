package org.simplepoint.plugin.dna.runtime.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelResourceBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Tenant-scoped model to page-resource authorization projection. */
@Repository
public interface PhysicalModelResourceBindingRepository
    extends JpaRepository<PhysicalModelResourceBindingEntity, String> {

  /** Finds one active binding for idempotent management. */
  @Query("""
      select binding from PhysicalModelResourceBindingEntity binding
      where binding.tenantId = :tenantId
        and binding.physicalModelId = :modelId
        and binding.resourceCode = :resourceCode
        and binding.deletedAt is null
      """)
  Optional<PhysicalModelResourceBindingEntity> findActive(
      @Param("tenantId") String tenantId,
      @Param("modelId") String modelId,
      @Param("resourceCode") String resourceCode
  );

  /** Lists active resource bindings for one model. */
  @Query("""
      select binding from PhysicalModelResourceBindingEntity binding
      where binding.tenantId = :tenantId
        and binding.physicalModelId = :modelId
        and binding.deletedAt is null
      order by binding.resourceCode, binding.id
      """)
  List<PhysicalModelResourceBindingEntity> findActiveByModel(
      @Param("tenantId") String tenantId,
      @Param("modelId") String modelId
  );
}
