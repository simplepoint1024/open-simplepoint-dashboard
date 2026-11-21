package org.simplepoint.plugin.dna.runtime.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Exact data-side work item; the physical plan is erased with completion. */
@Entity
@Table(name = "simpoint_dna_form_storage_removals")
@Getter
@Setter
public class FormStorageRemovalEntity {
  @Id @Column(length = 36)
  private String id;
  @Column(name = "tenant_id", length = 64, nullable = false)
  private String tenantId;
  @Column(name = "form_id", length = 36, nullable = false)
  private String formId;
  @Column(name = "form_version", nullable = false)
  private int formVersion;
  @Column(name = "plan_json", columnDefinition = "TEXT")
  private String planJson;
  @Column(name = "execution_token", length = 36)
  private String executionToken;
  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;
  @Column(name = "lease_until")
  private Instant leaseUntil;
  @Column(name = "completed_at")
  private Instant completedAt;
  @Column(nullable = false)
  private int attempts;
}
