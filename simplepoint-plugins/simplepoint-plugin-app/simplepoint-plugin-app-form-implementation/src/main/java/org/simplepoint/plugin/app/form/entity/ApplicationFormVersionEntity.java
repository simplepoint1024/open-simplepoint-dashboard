package org.simplepoint.plugin.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Immutable form document snapshot created from a validated draft. */
@Entity
@Table(
    name = "simpoint_app_form_versions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_simpoint_app_form_version",
        columnNames = {"tenant_id", "form_id", "version_number"}
    ),
    indexes = @Index(
        name = "idx_simpoint_app_form_version_lookup",
        columnList = "tenant_id,form_id,version_number"
    )
)
@Getter
@Setter
public class ApplicationFormVersionEntity {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "form_id", length = 36, nullable = false, updatable = false)
  private String formId;

  @Column(name = "version_number", nullable = false, updatable = false)
  private int versionNumber;

  @Column(
      name = "document_json",
      nullable = false,
      updatable = false,
      columnDefinition = "TEXT"
  )
  private String documentJson;

  @Column(length = 64, nullable = false, updatable = false)
  private String checksum;

  @Column(
      name = "storage_schema_json",
      nullable = false,
      updatable = false,
      columnDefinition = "TEXT"
  )
  private String storageSchemaJson;

  @Column(name = "storage_checksum", length = 64, nullable = false, updatable = false)
  private String storageChecksum;

  @Column(name = "physical_model_version")
  private Integer physicalModelVersion;

  @Column(name = "detail_bindings_json", columnDefinition = "TEXT")
  private String detailBindingsJson;

  @Column(name = "renderer_version", length = 16, nullable = false, updatable = false)
  private String rendererVersion;

  @Column(name = "created_by", length = 64, updatable = false)
  private String createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
