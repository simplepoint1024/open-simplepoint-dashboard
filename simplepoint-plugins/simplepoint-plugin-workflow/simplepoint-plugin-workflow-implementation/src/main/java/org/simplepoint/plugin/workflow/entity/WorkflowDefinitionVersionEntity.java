package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Immutable published BPMN snapshot and Flowable deployment binding. */
@Entity
@Table(name = "simpoint_workflow_definition_versions",
    uniqueConstraints = @UniqueConstraint(name = "uk_workflow_definition_version",
        columnNames = {"definition_id", "version_no"}),
    indexes = {
        @Index(name = "idx_workflow_version_tenant_definition",
            columnList = "tenant_id,definition_id,version_no"),
        @Index(name = "uk_workflow_version_process_definition",
            columnList = "flowable_process_definition_id", unique = true)
    })
@Getter
@Setter
public class WorkflowDefinitionVersionEntity {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "definition_id", length = 36, nullable = false, updatable = false)
  private String definitionId;

  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;

  @Column(name = "version_no", nullable = false, updatable = false)
  private int version;

  @Column(name = "bpmn_xml", nullable = false, updatable = false, columnDefinition = "TEXT")
  private String bpmnXml;

  @Column(name = "form_contract", updatable = false, columnDefinition = "TEXT")
  private String formContract;

  @Column(name = "definition_name", length = 160, updatable = false)
  private String definitionName;

  @Column(name = "definition_category", length = 80, updatable = false)
  private String definitionCategory;

  @Column(name = "definition_description", length = 1000, updatable = false)
  private String definitionDescription;

  @Column(name = "checksum_version", updatable = false)
  private Integer checksumVersion;

  @Column(length = 64, nullable = false, updatable = false)
  private String checksum;

  @Column(length = 500, updatable = false)
  private String comment;

  @Column(name = "flowable_deployment_id", length = 64, nullable = false, updatable = false)
  private String flowableDeploymentId;

  @Column(name = "flowable_process_definition_id", length = 128, nullable = false, updatable = false)
  private String flowableProcessDefinitionId;

  @Column(name = "published_by", length = 64, nullable = false, updatable = false)
  private String publishedBy;

  @Column(name = "published_at", nullable = false, updatable = false)
  private Instant publishedAt;
}
