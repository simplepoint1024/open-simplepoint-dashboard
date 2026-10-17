package org.simplepoint.plugin.dna.runtime.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.simplepoint.plugin.dna.runtime.entity.RecordCreateReceiptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Tenant and model scoped persistence for idempotent record-create receipts. */
@Repository
public interface RecordCreateReceiptRepository
    extends JpaRepository<RecordCreateReceiptEntity, String> {

  /** Finds one active receipt by its complete idempotency scope. */
  @Query("""
      select receipt from RecordCreateReceiptEntity receipt
      where receipt.tenantId = :tenantId
        and receipt.physicalModelId = :modelId
        and receipt.idempotencyKey = :idempotencyKey
        and receipt.deletedAt is null
      """)
  Optional<RecordCreateReceiptEntity> findByIdempotencyKey(
      @Param("tenantId") String tenantId,
      @Param("modelId") String modelId,
      @Param("idempotencyKey") String idempotencyKey
  );

  /** Locks one receipt while its result is finalized. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select receipt from RecordCreateReceiptEntity receipt
      where receipt.tenantId = :tenantId
        and receipt.physicalModelId = :modelId
        and receipt.idempotencyKey = :idempotencyKey
        and receipt.deletedAt is null
      """)
  Optional<RecordCreateReceiptEntity> findByIdempotencyKeyForUpdate(
      @Param("tenantId") String tenantId,
      @Param("modelId") String modelId,
      @Param("idempotencyKey") String idempotencyKey
  );
}
