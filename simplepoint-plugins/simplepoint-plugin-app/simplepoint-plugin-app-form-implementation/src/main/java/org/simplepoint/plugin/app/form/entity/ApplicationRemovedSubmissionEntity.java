package org.simplepoint.plugin.app.form.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Retains only the identity needed to fence replay of an erased submission. */
@Entity
@Table(name = "simpoint_app_removed_submissions")
@Getter
@Setter
public class ApplicationRemovedSubmissionEntity {
  @Id
  @Column(length = 64)
  private String id;
  @Column(name = "tenant_id", length = 64, nullable = false)
  private String tenantId;
  @Column(name = "process_instance_id", length = 64, nullable = false)
  private String processInstanceId;
  @Column(name = "operation_id", length = 36, nullable = false)
  private String operationId;
}
