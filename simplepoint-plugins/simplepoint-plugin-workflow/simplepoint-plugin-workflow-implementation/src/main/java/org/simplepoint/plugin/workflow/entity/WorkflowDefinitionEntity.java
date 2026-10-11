package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Stable tenant-owned process identity and mutable draft. */
@Entity
@Table(name = "simpoint_workflow_definitions",
    uniqueConstraints = @UniqueConstraint(name = "uk_workflow_definition_tenant_key",
        columnNames = {"tenant_id", "definition_key"}),
    indexes = @Index(name = "idx_workflow_definition_tenant_updated",
        columnList = "tenant_id,updated_at"))
@Getter
@Setter
public class WorkflowDefinitionEntity {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "definition_key", length = 80, nullable = false, updatable = false)
  private String key;

  @Column(length = 160, nullable = false)
  private String name;

  @Column(length = 80)
  private String category;

  @Column(length = 1000)
  private String description;

  @Column(length = 20, nullable = false)
  private String status;

  @Column(name = "latest_version", nullable = false)
  private int latestVersion;

  @Column(name = "draft_revision", nullable = false)
  private long draftRevision;

  @Column(name = "draft_xml", nullable = false, columnDefinition = "TEXT")
  private String draftXml;

  @Column(name = "draft_form_contract", columnDefinition = "TEXT")
  private String draftFormContract;

  @Column(name = "published_checksum", length = 64)
  private String publishedChecksum;

  @Column(name = "created_by", length = 64, nullable = false, updatable = false)
  private String createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;
}
