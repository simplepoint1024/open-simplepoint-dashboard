package org.simplepoint.plugin.dna.runtime.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Tenant-scoped schema migration persistence and publication locking. */
@Repository
public interface SchemaMigrationRepository extends JpaRepository<SchemaMigrationEntity, String> {

  /** Finds a migration by tenant and id. */
  @Query("""
      select migration from SchemaMigrationEntity migration
      where migration.tenantId = :tenantId
        and migration.id = :migrationId
        and migration.deletedAt is null
      """)
  Optional<SchemaMigrationEntity> findActiveById(
      @Param("tenantId") String tenantId,
      @Param("migrationId") String migrationId
  );

  /** Locks a migration while its durable state is advanced. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select migration from SchemaMigrationEntity migration
      where migration.tenantId = :tenantId
        and migration.id = :migrationId
        and migration.deletedAt is null
      """)
  Optional<SchemaMigrationEntity> findActiveByIdForUpdate(
      @Param("tenantId") String tenantId,
      @Param("migrationId") String migrationId
  );

  /** Finds the idempotent publication record for one model version. */
  @Query("""
      select migration from SchemaMigrationEntity migration
      where migration.tenantId = :tenantId
        and migration.physicalModelId = :modelId
        and migration.targetVersion = :targetVersion
        and migration.deletedAt is null
      """)
  Optional<SchemaMigrationEntity> findByTargetVersion(
      @Param("tenantId") String tenantId,
      @Param("modelId") String modelId,
      @Param("targetVersion") int targetVersion
  );

  /** Finds another nonterminal publication for the same model. */
  @Query("""
      select migration from SchemaMigrationEntity migration
      where migration.tenantId = :tenantId
        and migration.physicalModelId = :modelId
        and migration.status not in (
          org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus.ACTIVE,
          org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus.FAILED
        )
        and migration.deletedAt is null
      """)
  Optional<SchemaMigrationEntity> findNonterminalByModel(
      @Param("tenantId") String tenantId,
      @Param("modelId") String modelId
  );
}
