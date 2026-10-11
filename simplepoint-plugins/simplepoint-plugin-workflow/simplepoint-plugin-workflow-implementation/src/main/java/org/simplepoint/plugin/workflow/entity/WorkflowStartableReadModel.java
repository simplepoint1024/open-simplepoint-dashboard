package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

/** Only immutable published metadata; editing the next draft cannot change a starter's catalog. */
@Entity
@Immutable
@Subselect(
    """
select d.id, d.tenant_id, coalesce(v.definition_name, p.NAME_, d.definition_key) as name,
  v.definition_category as category, v.definition_description as description,
  v.version_no as latest_version
from simpoint_workflow_definitions d
join simpoint_workflow_definition_versions v
  on v.definition_id = d.id and v.tenant_id = d.tenant_id and v.version_no = d.latest_version
join ACT_RE_PROCDEF p on p.ID_ = v.flowable_process_definition_id and p.TENANT_ID_ = d.tenant_id
where d.status = 'PUBLISHED' and d.latest_version > 0 and p.SUSPENSION_STATE_ = 1
    """)
@Synchronize({"simpoint_workflow_definitions", "simpoint_workflow_definition_versions"})
@Getter
public class WorkflowStartableReadModel {
  @Id private String id;

  @Column(name = "tenant_id")
  private String tenantId;

  private String name;
  private String category;
  private String description;

  @Column(name = "latest_version")
  private int latestVersion;
}
