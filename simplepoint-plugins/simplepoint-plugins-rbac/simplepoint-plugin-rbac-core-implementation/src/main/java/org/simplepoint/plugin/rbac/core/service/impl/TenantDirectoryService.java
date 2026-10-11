package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantDirectoryItem;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded current-tenant selection directory, independent of account-management projections. */
@Service
@Transactional(readOnly = true)
public class TenantDirectoryService {

  private static final Set<String> USER_ACCESS = Set.of(
      "users.view", "users.config.role", "organizations.view",
      "workflow.definitions.view", "workflow.definitions.edit", "workflow.instances.start", "workflow.tasks.complete"
  );
  private static final Set<String> ROLE_ACCESS = Set.of(
      "roles.view", "roles.config.resource", "users.config.role", "workflow.definitions.view", "workflow.definitions.edit",
      "workflow.instances.start", "workflow.tasks.complete"
  );
  private static final String USER_NAME = "coalesce(nullif(u.nickname, ''), nullif(u.name, ''), u.email)";
  private static final String USER_WHERE = "u.deletedAt is null and coalesce(u.enabled, true) = true"
      + " and (u.id = :owner or exists (select m.id from TenantUserRelevance m"
      + " where m.tenantId = :tenant and m.userId = u.id and m.deletedAt is null"
      + " and coalesce(m.enabled, true) = true))";
  private static final String ROLE_WHERE = "r.tenantId = :tenant and r.deletedAt is null"
      + " and r.authority is not null and r.authority <> ''";

  private final EntityManager entityManager;

  /** Creates the tenant directory query service. */
  public TenantDirectoryService(final EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** Searches active members with bounded server paging, including the tenant owner. */
  public Page<TenantDirectoryItem> users(final String keyword, final Pageable pageable) {
    Tenant tenant = requireTenant(USER_ACCESS);
    requirePage(pageable);
    String pattern = pattern(keyword);
    String where = USER_WHERE + " and (lower(" + USER_NAME + ") like :pattern escape '!'"
        + " or lower(u.email) like :pattern escape '!')";
    var data = entityManager.createQuery(
        "select new org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantDirectoryItem(u.id, "
            + USER_NAME + ", u.id) from User u where " + where
            + " order by lower(" + USER_NAME + "), u.id", TenantDirectoryItem.class
    );
    data.setParameter("tenant", tenant.getId()).setParameter("owner", tenant.getOwnerId())
        .setParameter("pattern", pattern);
    var count = entityManager.createQuery("select count(u) from User u where " + where, Long.class);
    count.setParameter("tenant", tenant.getId()).setParameter("owner", tenant.getOwnerId())
        .setParameter("pattern", pattern);
    return new PageImpl<>(data.setFirstResult((int) pageable.getOffset())
        .setMaxResults(pageable.getPageSize()).getResultList(), pageable, count.getSingleResult());
  }

  /** Resolves only requested active user IDs, using the same tenant boundary as search. */
  public List<TenantDirectoryItem> selectedUsers(final Collection<String> values) {
    Tenant tenant = requireTenant(USER_ACCESS);
    Set<String> requested = requested(values);
    if (requested.isEmpty()) {
      return List.of();
    }
    return entityManager.createQuery(
        "select new org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantDirectoryItem(u.id, "
            + USER_NAME + ", u.id) from User u where " + USER_WHERE + " and u.id in :values",
        TenantDirectoryItem.class
    ).setParameter("tenant", tenant.getId()).setParameter("owner", tenant.getOwnerId())
        .setParameter("values", requested).getResultList();
  }

  /** Searches tenant-owned roles; the selection value is the authority used by task candidates. */
  public Page<TenantDirectoryItem> roles(final String keyword, final Pageable pageable) {
    Tenant tenant = requireTenant(ROLE_ACCESS);
    requirePage(pageable);
    String where = ROLE_WHERE + " and (lower(r.roleName) like :pattern escape '!'"
        + " or lower(r.authority) like :pattern escape '!')";
    var data = entityManager.createQuery(
        "select new org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantDirectoryItem"
            + "(r.id, r.roleName, r.authority) from Role r where " + where
            + " order by lower(r.roleName), r.id", TenantDirectoryItem.class
    ).setParameter("tenant", tenant.getId()).setParameter("pattern", pattern(keyword));
    var count = entityManager.createQuery("select count(r) from Role r where " + where, Long.class)
        .setParameter("tenant", tenant.getId()).setParameter("pattern", pattern(keyword));
    return new PageImpl<>(data.setFirstResult((int) pageable.getOffset())
        .setMaxResults(pageable.getPageSize()).getResultList(), pageable, count.getSingleResult());
  }

  /** Resolves requested authorities only from the active tenant. */
  public List<TenantDirectoryItem> selectedRoles(final Collection<String> values) {
    Tenant tenant = requireTenant(ROLE_ACCESS);
    Set<String> requested = requested(values);
    if (requested.isEmpty()) {
      return List.of();
    }
    return entityManager.createQuery(
        "select new org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantDirectoryItem"
            + "(r.id, r.roleName, r.authority) from Role r where " + ROLE_WHERE
            + " and r.authority in :values", TenantDirectoryItem.class
    ).setParameter("tenant", tenant.getId()).setParameter("values", requested).getResultList();
  }

  private Tenant requireTenant(final Set<String> access) {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    String id = context == null ? null : context.getAttribute("X-Tenant-Id");
    if (context == null || context.getScopeType() != AuthorizationScopeType.TENANT
        || id == null || id.isBlank() || context.getUserId() == null) {
      throw new AccessDeniedException("请选择组织工作空间");
    }
    if (!Boolean.TRUE.equals(context.getIsAdministrator())
        && (context.getResources() == null || access.stream().noneMatch(context.getResources()::contains))) {
      throw new AccessDeniedException("缺少人员或角色选择权限");
    }
    Tenant tenant = entityManager.find(Tenant.class, id);
    if (tenant == null || tenant.getDeletedAt() != null || tenant.getTenantType() != TenantType.ORGANIZATION) {
      throw new AccessDeniedException("组织工作空间不存在");
    }
    if (!Boolean.TRUE.equals(context.getIsAdministrator())
        && !Objects.equals(context.getUserId(), tenant.getOwnerId())) {
      long membership = entityManager.createQuery(
          "select count(m) from TenantUserRelevance m where m.tenantId = :tenant"
              + " and m.userId = :user and m.deletedAt is null and coalesce(m.enabled, true) = true", Long.class
      ).setParameter("tenant", id).setParameter("user", context.getUserId()).getSingleResult();
      if (membership == 0) {
        throw new AccessDeniedException("当前用户不是有效组织成员");
      }
    }
    return tenant;
  }

  private static void requirePage(final Pageable pageable) {
    if (pageable == null || pageable.isUnpaged() || pageable.getPageSize() > 50
        || pageable.getOffset() > Integer.MAX_VALUE || pageable.getSort().isSorted()) {
      throw new IllegalArgumentException("每页最多 50 条，使用固定目录顺序");
    }
  }

  private static Set<String> requested(final Collection<String> values) {
    if (values != null && values.size() > 50) {
      throw new IllegalArgumentException("每次最多解析 50 个已选项");
    }
    Set<String> result = new LinkedHashSet<>();
    if (values != null) {
      values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty())
          .forEach(result::add);
    }
    if (result.stream().anyMatch(value -> value.length() > 100)) {
      throw new IllegalArgumentException("已选项标识过长");
    }
    return result;
  }

  private static String pattern(final String keyword) {
    String search = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    if (search.length() > 128) {
      throw new IllegalArgumentException("搜索词不能超过 128 个字符");
    }
    return "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }
}
