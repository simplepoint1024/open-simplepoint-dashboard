package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import org.simplepoint.core.permission.InvalidPermissionPolicyException;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.FieldScope;
import org.springframework.stereotype.Component;

/** Protects rule lifecycle so deleting a referenced policy cannot invalidate a tenant's login context. */
@Component
public class PermissionPolicyGuard {
  private final EntityManager em;
  public PermissionPolicyGuard(EntityManager em) { this.em = em; }

  public static String name(String name) {
    if (name == null || name.isBlank() || name.trim().length() > 100) {
      throw new InvalidPermissionPolicyException("规则名称不能为空且不能超过 100 个字符");
    }
    return name.trim();
  }

  public void requireUnused(String tenant, Collection<String> ids, boolean data) {
    if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new InvalidPermissionPolicyException("每次请选择 1 至 100 条有效规则");
    }
    Class<?> type = data ? DataScope.class : FieldScope.class;
    for (String id : ids.stream().distinct().sorted().toList()) {
      Object row = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
      String owner = row instanceof DataScope value ? value.getTenantId()
          : row instanceof FieldScope value ? value.getTenantId() : null;
      if (!tenant.equals(owner)) throw new InvalidPermissionPolicyException("规则不存在或不属于当前工作空间");
    }
    String field = data ? "dataScopeId" : "fieldScopeId";
    for (String entity : new String[]{"RoleResourceGrant", "RoleScopeBinding"}) {
      Long references = em.createQuery("select count(r) from " + entity + " r where r.tenantId = :tenant"
          + " and r.deletedAt is null and r." + field + " in :ids", Long.class)
          .setParameter("tenant", tenant).setParameter("ids", ids).getSingleResult();
      if (references > 0) throw new InvalidPermissionPolicyException("规则正在被角色使用，请先解除绑定再删除");
    }
  }
}
