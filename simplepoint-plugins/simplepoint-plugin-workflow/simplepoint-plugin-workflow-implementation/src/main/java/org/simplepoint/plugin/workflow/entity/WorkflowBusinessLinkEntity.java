package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A durable external business submission may create exactly one process instance. */
@Entity
@Table(name = "simpoint_workflow_business_links", indexes =
    @Index(name = "idx_workflow_business_process", columnList = "tenant_id,process_instance_id"))
@Getter
@Setter
public class WorkflowBusinessLinkEntity {
  @Id @Column(length = 64)
  private String id;
  @Column(name = "tenant_id", length = 64, nullable = false, updatable = false)
  private String tenantId;
  @Column(name = "provider", length = 80, nullable = false, updatable = false)
  private String provider;
  @Column(name = "business_key", length = 160, nullable = false, updatable = false)
  private String businessKey;
  @Column(name = "process_instance_id", length = 64, nullable = false, updatable = false)
  private String processInstanceId;
}
