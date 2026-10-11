package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.*;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.jackson.FieldPermissionPolicy;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.FieldScope;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Readable metadata for the verified caller; identifiers remain transport keys only. */
@Service
@Transactional(readOnly = true)
public class PermissionPresentationService {
  private final EntityManager em;
  private final FieldScopeCatalog fields;
  public PermissionPresentationService(EntityManager em, FieldScopeCatalog fields) { this.em = em; this.fields = fields; }

  public Map<String, Object> catalog() {
    var context = AuthorizationContextHolder.getContext();
    if (context == null || context.getUserId() == null || context.getUserId().isBlank()) throw new org.springframework.security.access.AccessDeniedException("授权上下文不可用");
    String tenant = context.getAttribute("X-Tenant-Id");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("pages", fields.pages());
    var users = em.createQuery("select u.nickname,u.email from User u where u.id=:id and u.deletedAt is null", Object[].class)
        .setParameter("id", context.getUserId()).setMaxResults(1).getResultList();
    var userValues = new LinkedHashMap<String, Object>(); userValues.put("nickname", users.isEmpty() ? null : users.getFirst()[0]);
    userValues.put("email", users.isEmpty() ? null : users.getFirst()[1]);
    var readableUser = FieldPermissionPolicy.project(User.class, userValues);
    if (!readableUser.isEmpty()) result.put("userName", readableUser.values().stream().filter(Objects::nonNull).findFirst().orElse(""));
    putName(result, "tenantName", Tenant.class, "name", first("select t.name from Tenant t where t.id=:id and t.deletedAt is null", tenant));
    String role = context.getAttribute("X-Role-Id");
    putName(result, "roleName", Role.class, "roleName", role == null || tenant == null ? "" : em.createQuery(
        "select r.roleName from Role r where r.id=:id and r.tenantId=:tenant and r.deletedAt is null", String.class)
        .setParameter("id", role).setParameter("tenant", tenant).setMaxResults(1).getResultStream().filter(Objects::nonNull).findFirst().orElse(""));
    Map<String, String> dataRules = new LinkedHashMap<>(), fieldRules = new LinkedHashMap<>(), organizations = new LinkedHashMap<>();
    Set<String> departmentIds = new HashSet<>();
    if (context.getDeptIds() != null) departmentIds.addAll(context.getDeptIds());
    if (context.getResourceDataScopes() != null) context.getResourceDataScopes().values().forEach(scope -> {
      if (scope.departmentIds() != null) departmentIds.addAll(scope.departmentIds());
    });
    var authorities = context.getResources() == null ? Set.<String>of() : context.getResources();
    boolean manages = Boolean.TRUE.equals(context.getIsAdministrator()) || authorities.contains("roles.view");
    if (tenant != null) {
      if (manages || authorities.contains("data-scope.view")) forRules(DataScope.class, tenant, departmentIds,
          row -> putName(dataRules, (String) row[0], DataScope.class, "name", row[1]));
      if (manages || authorities.contains("field-scope.view")) forRules(FieldScope.class, tenant, null,
          row -> putName(fieldRules, (String) row[0], FieldScope.class, "name", row[1]));
      List<String> ids = departmentIds.stream().filter(Objects::nonNull).toList();
      for (int i = 0; i < ids.size(); i += 100) em.createQuery(
          "select o.id,o.name from Organization o where o.tenantId=:tenant and o.deletedAt is null and o.id in :ids", Object[].class)
          .setParameter("tenant", tenant).setParameter("ids", ids.subList(i, Math.min(i + 100, ids.size())))
          .getResultList().forEach(row -> putName(organizations, (String) row[0], Organization.class, "name", row[1]));
    }
    result.put("dataRules", dataRules); result.put("fieldRules", fieldRules); result.put("organizations", organizations);
    return result;
  }

  private void forRules(Class<?> type, String tenant, Set<String> departments, java.util.function.Consumer<Object[]> consume) {
    String after = null;
    while (true) {
      var query = em.createQuery("select s.id,s.name from " + type.getSimpleName()
          + " s where s.tenantId=:tenant and s.deletedAt is null" + (after == null ? "" : " and s.id>:after") + " order by s.id", Object[].class)
          .setParameter("tenant", tenant);
      if (after != null) query.setParameter("after", after);
      var rows = query.setMaxResults(100).getResultList(); rows.forEach(consume);
      if (departments != null && !rows.isEmpty()) includeRuleDepartments(tenant, rows.stream().map(row -> (String) row[0]).toList(), departments);
      if (rows.size() < 100) return; after = (String) rows.getLast()[0];
    }
  }

  /** Element collections are a separate bounded join, not a scalar selection that drops zero-department rules. */
  private void includeRuleDepartments(String tenant, List<String> rules, Set<String> departments) {
    String after = null;
    while (true) {
      var query = em.createQuery("select distinct department from DataScope s join s.customDeptIds department"
          + " where s.tenantId=:tenant and s.deletedAt is null and s.id in :rules"
          + (after == null ? "" : " and department>:after") + " order by department", String.class)
          .setParameter("tenant", tenant).setParameter("rules", rules);
      if (after != null) query.setParameter("after", after);
      var rows = query.setMaxResults(100).getResultList(); departments.addAll(rows);
      if (rows.size() < 100) return; after = rows.getLast();
    }
  }

  private static void putName(Map<String, ? super String> target, String key, Class<?> type, String field, Object value) {
    var projected = FieldPermissionPolicy.project(type, Collections.singletonMap(field, value));
    if (projected.containsKey(field)) {
      target.put(key, (String) projected.get(field));
    }
  }

  private String first(String hql, String id) {
    return id == null ? "" : em.createQuery(hql, String.class).setParameter("id", id)
        .setMaxResults(1).getResultStream().filter(Objects::nonNull).findFirst().orElse("");
  }
}
