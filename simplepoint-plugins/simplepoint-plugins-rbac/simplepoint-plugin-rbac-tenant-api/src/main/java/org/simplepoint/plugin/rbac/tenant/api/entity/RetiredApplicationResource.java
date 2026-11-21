package org.simplepoint.plugin.rbac.tenant.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Identity-only reservation that fences late application resource registration. */
@Entity
@Table(name = "simpoint_managed_app_retirements")
@Getter
@Setter
public class RetiredApplicationResource {
  @Id @Column(length = 36)
  private String id;
  @Column(name = "operation_id", length = 36, nullable = false)
  private String operationId;
}
