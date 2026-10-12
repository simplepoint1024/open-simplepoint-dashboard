package org.simplepoint.plugin.rbac.tenant.service.impl;

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
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.vo.UserRelevanceVo;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.identity.UserDisplayName;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.query.EscapeCharacter;
import org.springframework.stereotype.Repository;

/** Bounded SQL projection. The owning service must authorize the tenant before calling this query. */
@Repository
public class TenantUserCandidateQuery {
  private final EntityManager em;

  /** Creates the query adapter with the request's transactional persistence context. */
  public TenantUserCandidateQuery(EntityManager em) {
    this.em = em;
  }

  /** Queries either assignment side, or all candidates for the legacy selector. */
  public Page<UserRelevanceVo> list(Tenant tenant, Boolean selected, Map<String, String> attributes, Pageable page)
      throws IllegalArgumentException {
    if (!QueryCapabilities.readable(UserRelevanceVo.class, "id")) {
      throw new org.springframework.security.access.AccessDeniedException("缺少成员标识查看权限，不能配置成员分配");
    }
    if (page.isPaged() && page.getSort().isUnsorted() && QueryCapabilities.readable(UserRelevanceVo.class, "name")) {
      page = org.springframework.data.domain.PageRequest.of(page.getPageNumber(), page.getPageSize(),
          org.springframework.data.domain.Sort.by("name"));
    }
    var filters = new LinkedHashMap<>(attributes);
    String keyword = filters.remove("keyword");
    var input = ProjectionQuery.prepare(UserRelevanceVo.class, filters, page);
    var cb = em.getCriteriaBuilder();
    var data = cb.createQuery(UserRelevanceVo.class);
    var result = scope(data, tenant, selected, keyword, input);
    var fields = result.fields();
    data.select(cb.construct(UserRelevanceVo.class, fields.get("id"), fields.get("name"), fields.get("email"),
        fields.get("phoneNumber"))).orderBy(input.orders(cb, fields, result.user().get("id")));
    var rows = em.createQuery(data).setFirstResult((int) page.getOffset()).setMaxResults(page.getPageSize()).getResultList();
    var count = cb.createQuery(Long.class);
    var counted = scope(count, tenant, selected, keyword, input);
    count.select(cb.count(counted.user()));
    return new PageImpl<>(rows, page, em.createQuery(count).getSingleResult());
  }

  private record Candidate(Root<User> user, Map<String, Expression<?>> fields) {}

  private Candidate scope(CriteriaQuery<?> query, Tenant tenant, Boolean selected, String keyword, ProjectionQuery input) {
    var cb = em.getCriteriaBuilder();
    var user = query.from(User.class);
    Map<String, Expression<?>> fields = Map.of(
        "id", user.get("id").as(String.class),
        "name", UserDisplayName.expression(cb, user),
        "email", user.get("email"), "phoneNumber", user.get("phoneNumber"));
    Predicate side = cb.conjunction();
    if (selected != null) {
      var membership = query.subquery(Integer.class);
      var relation = membership.from(TenantUserRelevance.class);
      membership.select(cb.literal(1)).where(cb.equal(relation.get("tenantId"), tenant.getId()),
          cb.equal(relation.get("userId"), user.get("id")), cb.isNull(relation.get("deletedAt")));
      // Ownership remains assigned even if an older tenant has no explicit membership row.
      Predicate owner = tenant.getOwnerId() == null ? cb.disjunction() : cb.equal(user.get("id"), tenant.getOwnerId());
      Predicate assigned = cb.or(owner, cb.exists(membership));
      side = selected ? assigned : cb.not(assigned);
    }
    Predicate search = cb.conjunction();
    if (keyword != null && !keyword.isBlank()) {
      search = cb.or(fields.entrySet().stream().filter(entry -> QueryCapabilities.readable(UserRelevanceVo.class, entry.getKey()))
          .map(entry -> QueryPredicates.build(cb, entry.getValue(), new FilterCondition("like", keyword),
              EscapeCharacter.DEFAULT, 100)).toArray(Predicate[]::new));
    }
    query.where(cb.isNull(user.get("deletedAt")), side, search, input.predicate(cb, fields));
    return new Candidate(user, fields);
  }
}
