package org.simplepoint.plugin.dna.runtime.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Tenant-scoped persistence for physical model identities. */
@Repository
public interface PhysicalModelRepository extends JpaRepository<PhysicalModelEntity, String> {

  /** Finds a live model inside the supplied trusted tenant. */
  @Query("""
      select model from PhysicalModelEntity model
      where model.tenantId = :tenantId
        and model.id = :modelId
        and model.deletedAt is null
      """)
  Optional<PhysicalModelEntity> findActiveById(
      @Param("tenantId") String tenantId,
      @Param("modelId") String modelId
  );

  /** Serializes model version creation and publication claims across service instances. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select model from PhysicalModelEntity model
      where model.tenantId = :tenantId
        and model.id = :modelId
        and model.deletedAt is null
      """)
  Optional<PhysicalModelEntity> findActiveByIdForUpdate(
      @Param("tenantId") String tenantId,
      @Param("modelId") String modelId
  );

  /** Returns whether the form already owns a live model in the tenant. */
  @Query("""
      select case when count(model) > 0 then true else false end
      from PhysicalModelEntity model
      where model.tenantId = :tenantId
        and model.appId = :appId
        and model.formId = :formId
        and model.deletedAt is null
      """)
  boolean existsActiveByForm(
      @Param("tenantId") String tenantId,
      @Param("appId") String appId,
      @Param("formId") String formId
  );
}
