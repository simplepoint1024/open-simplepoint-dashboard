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
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus;

/** Durable publication state for one immutable target model version. */
@Entity
@Getter
@Setter
@AttributeOverrides({
    @AttributeOverride(name = "id", column = @Column(name = "id", length = 36)),
    @AttributeOverride(name = "createdBy", column = @Column(name = "created_by", length = 64)),
    @AttributeOverride(name = "updatedBy", column = @Column(name = "updated_by", length = 64))
})
@Table(
    name = "simpoint_dna_schema_migrations",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_dna_schema_migration_target",
        columnNames = {"tenant_id", "physical_model_id", "target_version"}
    ),
    indexes = {
        @Index(
          name = "idx_dna_schema_migration_model",
          columnList = "tenant_id,physical_model_id,status"
        ),
        @Index(name = "idx_dna_schema_migration_started", columnList = "started_at")
    }
)
@org.simplepoint.core.query.TableQuery(value = {
    @org.simplepoint.core.query.QueryField("physicalModelId"),
    @org.simplepoint.core.query.QueryField("fromVersion"),
    @org.simplepoint.core.query.QueryField("targetVersion"),
    @org.simplepoint.core.query.QueryField("status"),
    @org.simplepoint.core.query.QueryField("risk"),
    @org.simplepoint.core.query.QueryField("dialectId"),
    @org.simplepoint.core.query.QueryField("errorCode"),
    @org.simplepoint.core.query.QueryField("startedAt"),
    @org.simplepoint.core.query.QueryField("finishedAt")
}, maxPageSize = 100)
public class SchemaMigrationEntity extends TenantBaseEntityImpl<String> {

  @Column(name = "physical_model_id", length = 36, nullable = false, updatable = false)
  private String physicalModelId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "physical_model_id",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_dna_schema_migration_model")
  )
  private PhysicalModelEntity physicalModel;

  @Column(name = "from_version")
  private Integer fromVersion;

  @Column(name = "target_version", nullable = false, updatable = false)
  private int targetVersion;

  @Column(name = "target_schema_checksum", length = 64, nullable = false, updatable = false)
  private String targetSchemaChecksum;

  @Column(name = "target_physical_checksum", length = 64)
  private String targetPhysicalChecksum;

  @Column(name = "actual_schema_checksum", length = 64)
  private String actualSchemaChecksum;

  @Column(name = "verified_schema_checksum", length = 64)
  private String verifiedSchemaChecksum;

  @Enumerated(EnumType.STRING)
  @Column(length = 24, nullable = false)
  private SchemaMigrationStatus status;

  @Enumerated(EnumType.STRING)
  @Column(length = 24)
  private SchemaMigrationRisk risk;

  @Column(name = "dialect_id", length = 64)
  private String dialectId;

  @Column(name = "database_version", length = 128)
  private String databaseVersion;

  @Column(name = "error_code", length = 64)
  private String errorCode;

  @Column(name = "execution_token", length = 36, nullable = false)
  private String executionToken;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;
}
