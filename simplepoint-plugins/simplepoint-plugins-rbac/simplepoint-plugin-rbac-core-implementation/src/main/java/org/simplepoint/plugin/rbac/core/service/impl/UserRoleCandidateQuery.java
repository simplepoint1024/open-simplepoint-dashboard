package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.core.query.FilterCondition;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.data.jpa.ProjectionQuery;
import org.simplepoint.data.jpa.QueryPredicates;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.query.EscapeCharacter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;

/** SQL-paged assignment read model. The service first authorizes management of the tenant. */
@Repository
public class UserRoleCandidateQuery {
  private final EntityManager em;

  /** Uses the transaction's persistence context without loading the role catalog. */
  public UserRoleCandidateQuery(EntityManager em) {
    this.em = em;
  }

  /** Verifies one active member, including an owner without a legacy membership row. */
  public void requireMember(String tenantId, String userId) {
    if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
      throw new AccessDeniedException("角色分配需要明确的租户和成员");
    }
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(Integer.class);
    var user = query.from(User.class);
    var tenant = query.from(Tenant.class);
    var assigned = query.subquery(Integer.class);
    var member = assigned.from(TenantUserRelevance.class);
    assigned.select(cb.literal(1)).where(cb.equal(member.get("tenantId"), tenantId),
        cb.equal(member.get("userId"), userId), cb.isNull(member.get("deletedAt")));
    query.select(cb.literal(1)).where(cb.equal(user.get("id"), userId), cb.isNull(user.get("deletedAt")),
        cb.equal(tenant.get("id"), tenantId), cb.isNull(tenant.get("deletedAt")),
        cb.notEqual(tenant.get("tenantType"), org.simplepoint.plugin.rbac.tenant.api.entity.TenantType.PERSONAL),
        cb.or(cb.equal(tenant.get("ownerId"), userId), cb.exists(assigned)));
    if (em.createQuery(query).setMaxResults(1).getResultList().isEmpty()) {
      throw new AccessDeniedException("成员不存在或不属于当前租户");
    }
  }

  /** Applies user filters only within the selected side of the tenant's role catalog. */
  public Page<RoleRelevanceVo> list(String tenantId, String userId, Boolean selected,
                                   Map<String, String> attributes, Pageable page) throws IllegalArgumentException {
    if (!QueryCapabilities.readable(RoleRelevanceVo.class, "id")) {
      throw new AccessDeniedException("缺少角色标识查看权限，不能配置角色分配");
    }
    if (page.isPaged() && page.getSort().isUnsorted() && QueryCapabilities.readable(RoleRelevanceVo.class, "name")) {
      page = PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by("name"));
    }
    var filters = new LinkedHashMap<>(attributes);
    String keyword = filters.remove("keyword");
    var input = ProjectionQuery.prepare(RoleRelevanceVo.class, filters, page);
    var cb = em.getCriteriaBuilder();
    var data = cb.createQuery(RoleRelevanceVo.class);
    var result = scope(data, tenantId, userId, selected, keyword, input);
    var fields = result.fields();
    data.select(cb.construct(RoleRelevanceVo.class, fields.get("id"), fields.get("name"),
        fields.get("authority"), fields.get("description"))).orderBy(input.orders(cb, fields, result.role().get("id")));
    var rows = em.createQuery(data).setFirstResult((int) page.getOffset()).setMaxResults(page.getPageSize()).getResultList();
    var count = cb.createQuery(Long.class);
    var counted = scope(count, tenantId, userId, selected, keyword, input);
    count.select(cb.count(counted.role()));
    return new PageImpl<>(rows, page, em.createQuery(count).getSingleResult());
  }

  private record Candidate(Root<Role> role, Map<String, Expression<?>> fields) {}

  private Candidate scope(CriteriaQuery<?> query, String tenantId, String userId, Boolean selected,
                          String keyword, ProjectionQuery input) {
    var cb = em.getCriteriaBuilder();
    var role = query.from(Role.class);
    Map<String, Expression<?>> fields = Map.of("id", role.get("id").as(String.class), "name", role.get("roleName"),
        "authority", role.get("authority"), "description", role.get("description"));
    Predicate side = cb.conjunction();
    if (selected != null) {
      var membership = query.subquery(Integer.class);
      var relation = membership.from(UserRoleRelevance.class);
      membership.select(cb.literal(1)).where(cb.equal(relation.get("tenantId"), tenantId),
          cb.equal(relation.get("userId"), userId), cb.equal(relation.get("roleId"), role.get("id")),
          cb.isNull(relation.get("deletedAt")));
      side = selected ? cb.exists(membership) : cb.not(cb.exists(membership));
    }
    Predicate search = cb.conjunction();
    if (keyword != null && !keyword.isBlank()) {
      search = cb.or(fields.entrySet().stream().filter(entry -> QueryCapabilities.readable(RoleRelevanceVo.class, entry.getKey()))
          .map(entry -> QueryPredicates.build(cb, entry.getValue(), new FilterCondition("like", keyword),
              EscapeCharacter.DEFAULT, 100)).toArray(Predicate[]::new));
    }
    query.where(cb.equal(role.get("tenantId"), tenantId), cb.isNull(role.get("deletedAt")), side, search,
        input.predicate(cb, fields));
    return new Candidate(role, fields);
  }
}
