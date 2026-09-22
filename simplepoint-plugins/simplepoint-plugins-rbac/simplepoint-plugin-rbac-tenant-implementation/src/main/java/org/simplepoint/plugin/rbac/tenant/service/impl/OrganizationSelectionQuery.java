package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.util.Map;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.data.jpa.ProjectionQuery;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.vo.OrganizationSelection;
import org.simplepoint.plugin.rbac.tenant.api.vo.OrganizationSelectionRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;

/** Draft membership only restricts rows; trusted tenant scope and soft deletion always apply. */
@Repository
public class OrganizationSelectionQuery {
  private final EntityManager em;

  /** Uses the transactional persistence context. */
  public OrganizationSelectionQuery(EntityManager em) {
    this.em = em;
  }

  /** Queries either side without hydrating the entire organization tree or selected records. */
  public Page<OrganizationSelectionRow> list(String tenantId, OrganizationSelection draft, Pageable page)
      throws IllegalArgumentException {
    if (tenantId == null || tenantId.isBlank() || !QueryCapabilities.readable(OrganizationSelectionRow.class, "id")) {
      throw new AccessDeniedException("组织选择需要当前租户和组织标识查看权限");
    }
    if (page.isPaged() && page.getSort().isUnsorted() && QueryCapabilities.readable(OrganizationSelectionRow.class, "name")) {
      page = PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by("name"));
    }
    var input = ProjectionQuery.prepare(OrganizationSelectionRow.class, draft.filters(), page);
    var cb = em.getCriteriaBuilder();
    var data = cb.createQuery(OrganizationSelectionRow.class);
    var result = scope(data, tenantId, draft, input);
    var fields = result.fields();
    data.select(cb.construct(OrganizationSelectionRow.class, fields.get("id"), fields.get("name"), fields.get("code"),
        fields.get("type"), fields.get("description"), fields.get("enabled")))
        .orderBy(input.orders(cb, fields, result.root().get("id")));
    var rows = em.createQuery(data).setFirstResult((int) page.getOffset()).setMaxResults(page.getPageSize()).getResultList();
    var count = cb.createQuery(Long.class);
    var counted = scope(count, tenantId, draft, input);
    count.select(cb.count(counted.root()));
    return new PageImpl<>(rows, page, em.createQuery(count).getSingleResult());
  }

  private record Candidate(Root<Organization> root, Map<String, Expression<?>> fields) {}

  private Candidate scope(CriteriaQuery<?> query, String tenantId, OrganizationSelection draft, ProjectionQuery input) {
    var cb = em.getCriteriaBuilder();
    var root = query.from(Organization.class);
    Map<String, Expression<?>> fields = Map.of("id", root.get("id").as(String.class), "name", root.get("name"),
        "code", root.get("code"), "type", root.get("type"), "description", root.get("description"), "enabled", root.get("enabled"));
    // Client draft IDs are not authorization data. This explicit, bounded predicate is ANDed with tenant scope.
    var membership = draft.ids().isEmpty() ? cb.disjunction() : root.get("id").in(draft.ids());
    query.where(cb.equal(root.get("tenantId"), tenantId), cb.isNull(root.get("deletedAt")),
        draft.selected() ? membership : cb.not(membership), input.predicate(cb, fields));
    return new Candidate(root, fields);
  }
}
