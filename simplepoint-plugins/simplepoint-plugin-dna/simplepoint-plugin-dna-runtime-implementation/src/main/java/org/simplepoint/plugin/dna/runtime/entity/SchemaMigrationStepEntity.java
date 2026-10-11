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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationOperation;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStepStatus;

/** One ordered, recoverable, and checksummed native DDL step. */
@Entity
@Getter
@Setter
@AttributeOverrides({
    @AttributeOverride(name = "id", column = @Column(name = "id", length = 36)),
    @AttributeOverride(name = "createdBy", column = @Column(name = "created_by", length = 64)),
    @AttributeOverride(name = "updatedBy", column = @Column(name = "updated_by", length = 64))
})
@Table(
    name = "simpoint_dna_schema_migration_steps",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_dna_schema_migration_step",
        columnNames = {"migration_id", "step_sequence"}
    ),
    indexes = @Index(
        name = "idx_dna_schema_migration_step_status",
        columnList = "tenant_id,migration_id,status"
    )
)
public class SchemaMigrationStepEntity extends TenantBaseEntityImpl<String> {

  @Column(name = "migration_id", length = 36, nullable = false, updatable = false)
  private String migrationId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "migration_id",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_dna_schema_migration_step")
  )
  private SchemaMigrationEntity migration;

  @Column(name = "step_sequence", nullable = false, updatable = false)
  private int sequence;

  @Enumerated(EnumType.STRING)
  @Column(length = 32, nullable = false, updatable = false)
  private SchemaMigrationOperation operation;

  @Column(name = "logical_object", length = 128, nullable = false, updatable = false)
  private String logicalObject;

  @Enumerated(EnumType.STRING)
  @Column(length = 24, nullable = false, updatable = false)
  private SchemaMigrationRisk risk;

  @Column(name = "payload_json", columnDefinition = "TEXT", nullable = false, updatable = false)
  private String payloadJson;

  @Column(name = "statement_sql", columnDefinition = "TEXT", nullable = false, updatable = false)
  private String statementSql;

  @Column(name = "statement_checksum", length = 64, nullable = false, updatable = false)
  private String statementChecksum;

  @Enumerated(EnumType.STRING)
  @Column(length = 24, nullable = false)
  private SchemaMigrationStepStatus status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "error_code", length = 64)
  private String errorCode;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;
}
