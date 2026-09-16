package org.simplepoint.security.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;

/** Role-level policy independent of resource grants. Null policies are an explicit reset. */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "simpoint_ac_role_scope_binding", uniqueConstraints =
    @UniqueConstraint(name = "uq_role_scope_binding", columnNames = {"tenant_id", "role_id"}))
public class RoleScopeBinding extends TenantBaseEntityImpl<String> {
  @Column(name = "role_id", nullable = false)
  private String roleId;
  private String dataScopeId;
  private String fieldScopeId;
  @Version
  private Long revision;
}
