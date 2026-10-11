package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

/**
 * Personal/administrative instance read projection with live, authorized scalar identity labels.
 */
@Entity
@Immutable
@Subselect(WorkflowReadSql.MATTERS)
@Synchronize("simpoint_workflow_instances")
@Getter
public class WorkflowMatterReadModel {
  @Id private String id;

  @Column(name = "tenant_id")
  private String tenantId;

  @Column(name = "definition_id")
  private String definitionId;

  private String title;

  @Column(name = "definition_name")
  private String definitionName;

  @Column(name = "definition_version")
  private int definitionVersion;

  @Column(name = "business_key")
  private String businessKey;

  @Column(name = "starter_id")
  private String starterId;

  @Column(name = "starter_name")
  private String starterName;

  private String status;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  @Column(name = "previous_instance_id")
  private String previousInstanceId;

  @Column(name = "next_instance_id")
  private String nextInstanceId;

  @Column(name = "resubmission_version_available")
  private boolean resubmissionVersionAvailable;
}
