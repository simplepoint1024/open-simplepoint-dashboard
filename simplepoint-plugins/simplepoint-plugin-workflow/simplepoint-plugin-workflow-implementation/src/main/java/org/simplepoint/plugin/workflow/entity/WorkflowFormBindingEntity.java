package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Queryable published start-form index; the immutable process contract remains authoritative. */
@Entity
@Table(name = "simpoint_workflow_form_bindings", indexes =
    @Index(name = "idx_workflow_bound_form", columnList = "tenant_id,provider,form_id,form_version"))
@Getter
@Setter
public class WorkflowFormBindingEntity {
  @Id @Column(length = 36)
  private String id;
  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;
  @Column(name = "definition_id", length = 36, nullable = false, updatable = false)
  private String definitionId;
  @Column(name = "definition_version", nullable = false, updatable = false)
  private int definitionVersion;
  @Column(name = "provider", length = 80, nullable = false, updatable = false)
  private String provider;
  @Column(name = "form_id", length = 160, nullable = false, updatable = false)
  private String formId;
  @Column(name = "form_version", nullable = false, updatable = false)
  private int formVersion;
}
