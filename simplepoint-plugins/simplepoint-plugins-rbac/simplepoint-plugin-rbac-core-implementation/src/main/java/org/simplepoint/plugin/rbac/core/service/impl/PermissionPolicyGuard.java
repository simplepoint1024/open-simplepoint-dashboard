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

  /** Bound rules remain editable; expressions must still be valid for every binding. */
  public void validateDataUpdate(String tenant, DataScope proposed) {
    DataScope current = em.find(DataScope.class, proposed.getId(), LockModeType.PESSIMISTIC_WRITE);
    if (current == null || current.getDeletedAt() != null || !tenant.equals(current.getTenantId())) {
      throw new InvalidPermissionPolicyException("规则不存在或不属于当前工作空间");
    }
    if (proposed.getType() != org.simplepoint.security.entity.DataScopeType.EXPRESSION) return;
    Long defaults = em.createQuery("select count(b) from RoleScopeBinding b where b.tenantId = :tenant"
        + " and b.deletedAt is null and b.dataScopeId = :id", Long.class)
        .setParameter("tenant", tenant).setParameter("id", current.getId()).getSingleResult();
    Long incompatible = em.createQuery("select count(g) from RoleResourceGrant g where g.tenantId = :tenant"
        + " and g.deletedAt is null and g.dataScopeId = :id and (g.pagePolicy is null or g.pagePolicy = false"
        + " or g.resourceCode is null or g.resourceCode <> :page)", Long.class)
        .setParameter("tenant", tenant).setParameter("id", current.getId())
        .setParameter("page", proposed.getResourceCode()).getSingleResult();
    if (defaults > 0 || incompatible > 0) {
      throw new InvalidPermissionPolicyException("该规则仍用于其他页面或角色默认范围，无法改为当前页面专属表达式；请调整关联或另建规则");
    }
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
