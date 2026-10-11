package org.simplepoint.plugin.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;

/** Candidate roles display directory names, never raw authority strings as user-facing names. */
@Entity
@Immutable
@Subselect(
    "select id, tenant_id, authority, role_name from simpoint_ac_roles where deleted_at is null")
@Getter
public class WorkflowRoleReadModel {
  @Id private String id;

  @Column(name = "tenant_id")
  private String tenantId;

  private String authority;

  @Column(name = "role_name")
  private String roleName;
}
