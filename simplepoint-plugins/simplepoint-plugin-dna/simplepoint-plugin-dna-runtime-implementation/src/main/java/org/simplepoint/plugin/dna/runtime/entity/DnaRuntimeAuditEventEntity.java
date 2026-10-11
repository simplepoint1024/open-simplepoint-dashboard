package org.simplepoint.plugin.dna.runtime.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditOutcome;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;

/** Sanitized, append-only Runtime control-plane audit row. */
@Entity
@Getter
@Setter
@AttributeOverrides({
    @AttributeOverride(name = "id", column = @Column(name = "id", length = 36)),
    @AttributeOverride(
        name = "createdBy",
        column = @Column(name = "created_by", length = 64)
    ),
    @AttributeOverride(
        name = "updatedBy",
        column = @Column(name = "updated_by", length = 64)
    )
})
@Table(
    name = "simpoint_dna_runtime_audit_events",
    indexes = {
        @Index(
            name = "idx_dna_runtime_audit_model",
            columnList = "tenant_id,physical_model_id,occurred_at"
        ),
        @Index(
            name = "idx_dna_runtime_audit_type",
            columnList = "event_type,occurred_at"
        )
    }
)
@org.simplepoint.core.query.TableQuery(value = {
    @org.simplepoint.core.query.QueryField("eventType"),
    @org.simplepoint.core.query.QueryField("occurredAt"),
    @org.simplepoint.core.query.QueryField("actorId"),
    @org.simplepoint.core.query.QueryField("physicalModelId"),
    @org.simplepoint.core.query.QueryField("appId"),
    @org.simplepoint.core.query.QueryField("formId"),
    @org.simplepoint.core.query.QueryField("dataSourceId"),
    @org.simplepoint.core.query.QueryField("modelVersion"),
    @org.simplepoint.core.query.QueryField("affectedRows"),
    @org.simplepoint.core.query.QueryField("durationMillis"),
    @org.simplepoint.core.query.QueryField("outcome"),
    @org.simplepoint.core.query.QueryField("errorCode")
}, maxPageSize = 100)
public class DnaRuntimeAuditEventEntity extends TenantBaseEntityImpl<String> {

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", length = 48, nullable = false, updatable = false)
  private DnaRuntimeAuditEventType eventType;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "actor_id", length = 64, nullable = false, updatable = false)
  private String actorId;

  @Column(name = "physical_model_id", length = 36, nullable = false, updatable = false)
  private String physicalModelId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "physical_model_id",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_dna_runtime_audit_model")
  )
  private PhysicalModelEntity physicalModel;

  @Column(name = "app_id", length = 64, updatable = false)
  private String appId;

  @Column(name = "form_id", length = 64, updatable = false)
  private String formId;

  @Column(name = "data_source_id", length = 64, nullable = false, updatable = false)
  private String dataSourceId;

  @Column(name = "model_version", updatable = false)
  private Integer modelVersion;

  @Column(name = "affected_rows", updatable = false)
  private Long affectedRows;

  @Column(name = "duration_ms", nullable = false, updatable = false)
  private long durationMillis;

  @Enumerated(EnumType.STRING)
  @Column(length = 16, nullable = false, updatable = false)
  private DnaRuntimeAuditOutcome outcome;

  @Enumerated(EnumType.STRING)
  @Column(name = "error_code", length = 64, updatable = false)
  private DnaRuntimeErrorCode errorCode;
}
