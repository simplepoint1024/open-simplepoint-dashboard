package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.constants.TenantContextIds;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal, read-only, SQL-paged directories for context switching, not account/tenant
 * administration.
 */
@Service
@Transactional(readOnly = true)
public class CurrentDirectoryService {
  private static final String TENANTS =
      "t.deletedAt is null and (t.ownerId = :actor or exists"
          + " (select m.id from TenantUserRelevance m where m.tenantId = t.id and m.userId = :actor"
          + " and m.deletedAt is null and coalesce(m.enabled, true) = true))";
  private static final String ROLES =
      "r.deletedAt is null and r.tenantId = :tenant and exists (select a.id from UserRoleRelevance"
          + " a where a.roleId = r.id and a.userId = :actor and a.deletedAt is null and a.tenantId"
          + " = r.tenantId)";
  private static final String TENANT_ORDER =
      " order by case when t.tenantType ="
          + " org.simplepoint.plugin.rbac.tenant.api.entity.TenantType.PERSONAL then 1 else 0 end,"
          + " lower(t.name), t.id";
  private static final String DTO = CurrentDirectoryService.Option.class.getName();
  private final EntityManager em;

  /** Only display identities are projected; directory responses never serialize full entities. */
  public record Option(String id, String name, String type) {}

  /** Bounded workspace identity; never exposes owner, configuration or billing fields. */
  public record TenantOption(String id, String name, String type, String logo) {}

  /** Creates the query service. */
  public CurrentDirectoryService(EntityManager em) {
    this.em = em;
  }

  /**
   * Pages owned or active-member workspaces, with the virtual platform workspace first if eligible.
   */
  public Page<TenantOption> tenants(String keyword, Pageable pageable) {
    Authentication actor = actor();
    page(pageable);
    String search = keyword(keyword);
    boolean platform =
        platform(actor)
            && (search.isEmpty() || "平台工作台".contains(search) || "platform".contains(search));
    // The current user's personal workspace is displayed with a localized alias, not its stored
    // name.
    boolean personal = "我的空间".contains(search) || "my workspace".contains(search);
    String where =
        TENANTS
            + " and (lower(t.name) like :pattern escape '!'"
            + (personal
                ? " or (t.ownerId = :actor and t.tenantType ="
                    + " org.simplepoint.plugin.rbac.tenant.api.entity.TenantType.PERSONAL)"
                : "")
            + ")";
    long count =
        em.createQuery("select count(t) from Tenant t where " + where, Long.class)
            .setParameter("actor", actor.getName())
            .setParameter("pattern", pattern(search))
            .getSingleResult();
    List<TenantOption> rows = new ArrayList<>();
    if (platform && pageable.getOffset() == 0) {
      rows.add(platformOption());
    }
    int remaining = pageable.getPageSize() - rows.size();
    if (remaining > 0) {
      rows.addAll(
          em.createQuery(tenantProjection() + " where " + where + TENANT_ORDER, TenantOption.class)
              .setParameter("actor", actor.getName())
              .setParameter("pattern", pattern(search))
              .setFirstResult((int) Math.max(0, pageable.getOffset() - (platform ? 1 : 0)))
              .setMaxResults(remaining)
              .getResultList());
    }
    return new PageImpl<>(rows, pageable, count + (platform ? 1 : 0));
  }

  /** Exact hydration applies the same membership rules as browsing. */
  public List<TenantOption> selectedTenants(Collection<String> values) {
    Authentication actor = actor();
    Set<String> ids = ids(values);
    if (ids.isEmpty()) {
      return List.of();
    }
    List<TenantOption> rows = new ArrayList<>();
    if (ids.remove(TenantContextIds.PLATFORM) && platform(actor)) {
      rows.add(platformOption());
    }
    if (!ids.isEmpty()) {
      rows.addAll(
          em.createQuery(
                  tenantProjection() + " where " + TENANTS + " and t.id in :ids",
                  TenantOption.class)
              .setParameter("actor", actor.getName())
              .setParameter("ids", ids)
              .getResultList());
    }
    return rows;
  }

  /** Current roles return role IDs, never workflow candidate authorities. */
  public Page<Option> roles(String tenantId, String keyword, Pageable pageable) {
    Authentication actor = actor();
    String tenant = tenant(actor, tenantId);
    page(pageable);
    String where =
        ROLES
            + " and (lower(r.roleName) like :pattern escape '!' or lower(r.authority) like :pattern"
            + " escape '!')";
    String pattern = pattern(keyword(keyword));
    var rows =
        em.createQuery(
                roleProjection() + " where " + where + " order by lower(r.roleName), r.id",
                Option.class)
            .setParameter("actor", actor.getName())
            .setParameter("tenant", tenant)
            .setParameter("pattern", pattern)
            .setFirstResult((int) pageable.getOffset())
            .setMaxResults(pageable.getPageSize())
            .getResultList();
    long count =
        em.createQuery("select count(r) from Role r where " + where, Long.class)
            .setParameter("actor", actor.getName())
            .setParameter("tenant", tenant)
            .setParameter("pattern", pattern)
            .getSingleResult();
    return new PageImpl<>(rows, pageable, count);
  }

  /** Exact current-role hydration rejects a foreign workspace before querying roles. */
  public List<Option> selectedRoles(String tenantId, Collection<String> values) {
    Authentication actor = actor();
    String tenant = tenant(actor, tenantId);
    Set<String> ids = ids(values);
    if (ids.isEmpty()) {
      return List.of();
    }
    return em.createQuery(roleProjection() + " where " + ROLES + " and r.id in :ids", Option.class)
        .setParameter("actor", actor.getName())
        .setParameter("tenant", tenant)
        .setParameter("ids", ids)
        .getResultList();
  }

  private String tenant(Authentication actor, String id) {
    if (TenantContextIds.PLATFORM.equals(id)) {
      if (!platform(actor)) {
        throw new AccessDeniedException("Platform workspace unavailable");
      }
      return null;
    }
    if (id == null
        || id.isBlank()
        || em.createQuery(
                    "select count(t) from Tenant t where " + TENANTS + " and t.id = :id",
                    Long.class)
                .setParameter("actor", actor.getName())
                .setParameter("id", id)
                .getSingleResult()
            == 0) {
      throw new AccessDeniedException("Workspace unavailable");
    }
    return id;
  }

  private static Authentication actor() {
    Authentication actor = SecurityContextHolder.getContext().getAuthentication();
    if (actor == null
        || !actor.isAuthenticated()
        || actor instanceof AnonymousAuthenticationToken
        || actor.getName() == null
        || actor.getName().isBlank()) {
      throw new AccessDeniedException("Authentication required");
    }
    return actor;
  }

  private static boolean platform(Authentication actor) {
    return actor.getAuthorities().stream()
        .anyMatch(
            item -> Set.of("ROLE_Administrator", "platform.access").contains(item.getAuthority()));
  }

  private static TenantOption platformOption() {
    return new TenantOption(TenantContextIds.PLATFORM, "平台工作台", "PLATFORM", null);
  }

  private static String tenantProjection() {
    return "select new "
        + TenantOption.class.getName()
        + "(t.id, t.name, cast(t.tenantType as string), t.logo) from Tenant t";
  }

  private static String roleProjection() {
    return "select new " + DTO + "(r.id, r.roleName, 'ROLE') from Role r";
  }

  private static void page(Pageable pageable) {
    if (pageable == null
        || pageable.isUnpaged()
        || pageable.getPageSize() > 50
        || pageable.getOffset() > Integer.MAX_VALUE
        || pageable.getSort().isSorted()) {
      throw new IllegalArgumentException("Directory page must be bounded and use the fixed order");
    }
  }

  private static Set<String> ids(Collection<String> values) {
    if (values == null || values.size() > 50) {
      throw new IllegalArgumentException("At most 50 selected identities");
    }
    Set<String> result = new LinkedHashSet<>();
    for (String value : values) {
      if (value == null || value.isBlank() || value.length() > 100) {
        throw new IllegalArgumentException("Invalid selected identity");
      }
      result.add(value);
    }
    return result;
  }

  private static String keyword(String value) {
    String keyword = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (keyword.length() > 128) {
      throw new IllegalArgumentException("Keyword exceeds 128 characters");
    }
    return keyword;
  }

  private static String pattern(String keyword) {
    return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }
}
