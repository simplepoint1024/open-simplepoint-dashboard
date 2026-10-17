package org.simplepoint.plugin.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStep;

/** Durable publication coordination state advanced by short control-plane transactions. */
@Entity
@Table(
    name = "simpoint_app_form_publications",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_simpoint_app_form_publication_key",
            columnNames = {"tenant_id", "idempotency_key"}
        ),
        @UniqueConstraint(
            name = "uk_simpoint_app_form_publication_version",
            columnNames = {"tenant_id", "form_id", "form_version"}
        )
    },
    indexes = @Index(
        name = "idx_simpoint_app_form_publication_state",
        columnList = "tenant_id,status,updated_at"
    )
)
@Getter
@Setter
public class ApplicationFormPublicationEntity {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "form_id", length = 36, nullable = false, updatable = false)
  private String formId;

  @Column(name = "form_version", nullable = false, updatable = false)
  private int formVersion;

  @Column(name = "idempotency_key", length = 80, nullable = false, updatable = false)
  private String idempotencyKey;

  @Enumerated(EnumType.STRING)
  @Column(length = 24, nullable = false)
  private ApplicationFormPublicationStatus status;

  @Enumerated(EnumType.STRING)
  @Column(length = 40, nullable = false)
  private ApplicationFormPublicationStep step;

  @Column(name = "migration_id", length = 64)
  private String migrationId;

  @Column(name = "error_code", length = 80)
  private String errorCode;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "execution_token", length = 36)
  private String executionToken;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
