package org.simplepoint.plugin.dna.runtime.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.plugin.dna.runtime.entity.SchemaMigrationStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Ordered durable migration step persistence. */
@Repository
public interface SchemaMigrationStepRepository
    extends JpaRepository<SchemaMigrationStepEntity, String> {

  /** Returns migration steps in deterministic execution order. */
  @Query("""
      select step from SchemaMigrationStepEntity step
      where step.tenantId = :tenantId
        and step.migrationId = :migrationId
        and step.deletedAt is null
      order by step.sequence
      """)
  List<SchemaMigrationStepEntity> findByMigration(
      @Param("tenantId") String tenantId,
      @Param("migrationId") String migrationId
  );

  /** Finds one step for guarded state changes. */
  @Query("""
      select step from SchemaMigrationStepEntity step
      where step.tenantId = :tenantId
        and step.id = :stepId
        and step.deletedAt is null
      """)
  Optional<SchemaMigrationStepEntity> findActiveById(
      @Param("tenantId") String tenantId,
      @Param("stepId") String stepId
  );
}
