package org.simplepoint.plugin.dna.runtime.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;

/** Control-plane receipt used to recover single-record creates across network failures. */
@Entity
@Getter
@Setter
@AttributeOverrides({
    @AttributeOverride(name = "id", column = @Column(name = "id", length = 36)),
    @AttributeOverride(name = "createdBy", column = @Column(name = "created_by", length = 64)),
    @AttributeOverride(name = "updatedBy", column = @Column(name = "updated_by", length = 64))
})
@Table(
    name = "simpoint_dna_record_create_receipts",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_dna_record_create_idempotency",
        columnNames = {"tenant_id", "physical_model_id", "idempotency_key"}
    ),
    indexes = {
        @Index(
            name = "idx_dna_record_create_receipt_model",
            columnList = "tenant_id,physical_model_id,created_at"
        ),
        @Index(
            name = "idx_dna_record_create_receipt_status",
            columnList = "status,created_at"
        )
    }
)
public class RecordCreateReceiptEntity extends TenantBaseEntityImpl<String> {

  @Column(name = "physical_model_id", length = 36, nullable = false, updatable = false)
  private String physicalModelId;

  @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
  private String idempotencyKey;

  @Column(name = "request_fingerprint", length = 64, nullable = false, updatable = false)
  private String requestFingerprint;

  @Column(name = "record_id", length = 36, nullable = false, updatable = false)
  private String recordId;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false)
  private RecordCreateReceiptStatus status;

  @Column(name = "result_updated_at")
  private Instant resultUpdatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;
}
