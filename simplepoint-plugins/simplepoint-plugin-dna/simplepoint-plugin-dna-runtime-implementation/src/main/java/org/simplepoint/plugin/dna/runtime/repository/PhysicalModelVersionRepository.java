package org.simplepoint.plugin.dna.runtime.repository;

import java.util.Optional;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for immutable physical model schema versions. */
@Repository
public interface PhysicalModelVersionRepository
    extends JpaRepository<PhysicalModelVersionEntity, String> {

  /** Finds one immutable target version within its tenant and model. */
  @Query("""
      select version from PhysicalModelVersionEntity version
      where version.tenantId = :tenantId
        and version.physicalModelId = :modelId
        and version.versionNumber = :versionNumber
        and version.deletedAt is null
      """)
  Optional<PhysicalModelVersionEntity> findActiveVersion(
      @Param("tenantId") String tenantId,
      @Param("modelId") String modelId,
      @Param("versionNumber") int versionNumber
  );
}
