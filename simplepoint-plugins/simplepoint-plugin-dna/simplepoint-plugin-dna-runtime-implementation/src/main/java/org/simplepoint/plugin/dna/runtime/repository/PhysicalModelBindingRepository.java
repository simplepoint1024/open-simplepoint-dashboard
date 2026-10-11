package org.simplepoint.plugin.dna.runtime.repository;

import java.util.Optional;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for immutable server-owned physical bindings. */
@Repository
public interface PhysicalModelBindingRepository
    extends JpaRepository<PhysicalModelBindingEntity, String> {

  /** Finds the live immutable binding for a tenant-scoped model. */
  @Query("""
      select binding from PhysicalModelBindingEntity binding
      where binding.tenantId = :tenantId
        and binding.physicalModelId = :modelId
        and binding.deletedAt is null
      """)
  Optional<PhysicalModelBindingEntity> findActiveByModel(
      @Param("tenantId") String tenantId,
      @Param("modelId") String modelId
  );

  /** Detects a collision with an already owned physical table. */
  @Query("""
      select case when count(binding) > 0 then true else false end
      from PhysicalModelBindingEntity binding
      where binding.dataSourceId = :dataSourceId
        and binding.catalogName = :catalogName
        and binding.schemaName = :schemaName
        and binding.tableName = :tableName
        and binding.deletedAt is null
      """)
  boolean existsActiveTarget(
      @Param("dataSourceId") String dataSourceId,
      @Param("catalogName") String catalogName,
      @Param("schemaName") String schemaName,
      @Param("tableName") String tableName
  );
}
