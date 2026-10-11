package org.simplepoint.plugin.ai.core.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Map;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.data.jpa.DataScopePredicates;
import org.simplepoint.data.jpa.ProjectionQuery;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiEmbeddingModelOption;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

/** SQL count and scalar pages use identical availability, ownership and active data predicates. */
public final class AiEmbeddingModelOptionsQuery {
  private AiEmbeddingModelOptionsQuery() {}

  public static Page<AiEmbeddingModelOption> page(EntityManager manager, ScopeAssignment scope,
      Map<String, String> filters, Pageable pageable) {
    if (!QueryCapabilities.readable(AiEmbeddingModelOption.class, "id")) {
      throw new AccessDeniedException("模型标识不可读取");
    }
    var query = ProjectionQuery.prepare(AiEmbeddingModelOption.class, filters, pageable);
    var cb = manager.getCriteriaBuilder();
    var rows = cb.createQuery(AiEmbeddingModelOption.class);
    var model = rows.from(AiModelDefinition.class);
    var fields = fields(cb, model);
    rows.select(cb.construct(AiEmbeddingModelOption.class, fields.get("id"), fields.get("name")))
        .where(predicate(manager, cb, model, scope), query.predicate(cb, fields))
        .orderBy(query.orders(cb, fields, model.get("id")));
    var count = cb.createQuery(Long.class);
    var counted = count.from(AiModelDefinition.class);
    count.select(cb.count(counted)).where(predicate(manager, cb, counted, scope),
        query.predicate(cb, fields(cb, counted)));
    long total = manager.createQuery(count).getSingleResult();
    var content = manager.createQuery(rows).setFirstResult((int) pageable.getOffset())
        .setMaxResults(pageable.getPageSize()).getResultList();
    return new PageImpl<>(content, pageable, total);
  }

  private static Map<String, Expression<?>> fields(CriteriaBuilder cb, Root<AiModelDefinition> model) {
    // The derived label inherits both source policies; hidden values never enter the SQL projection.
    Expression<String> name = QueryCapabilities.readable(AiEmbeddingModelOption.class, "name")
        ? cb.coalesce(cb.nullif(cb.trim(model.get("displayName")), ""), model.get("modelId"))
        : cb.literal("***");
    // Preserve the established provider discriminator without loading/decorating provider entities.
    // Provider labels are optional: unreadable provider fields never enter output or search expressions.
    if (QueryCapabilities.readable(AiEmbeddingModelOption.class, "name")
        && QueryCapabilities.readable(AiProviderDefinition.class, "name")) {
      var provider = model.join(AiProviderDefinition.class, JoinType.LEFT);
      provider.on(cb.equal(provider.get("id"), model.get("providerId")), cb.isNull(provider.get("deletedAt")));
      Expression<String> providerName = cb.nullif(cb.trim(provider.get("name")), "");
      name = cb.<String>selectCase().when(cb.isNotNull(providerName), cb.concat(cb.concat(name, " · "), providerName)).otherwise(name);
    }
    return Map.of("id", model.get("id"), "name", name);
  }

  private static Predicate predicate(EntityManager manager, CriteriaBuilder cb,
      Root<AiModelDefinition> model, ScopeAssignment scope) {
    Predicate shared = cb.or(cb.isNull(model.get("scopeType")), cb.equal(model.get("scopeType"), AiResourceScope.SYSTEM));
    Predicate ownership = scope.scopeType() == AiResourceScope.SYSTEM ? shared : cb.or(shared,
        cb.and(cb.equal(model.get("scopeType"), AiResourceScope.TENANT), cb.equal(model.get("tenantId"), scope.tenantId())));
    var context = AuthorizationContextHolder.getContext();
    var active = context == null ? null : context.activeDataScope();
    Predicate data = cb.conjunction();
    if (active != null) {
      var compiled = DataScopePredicates.compile(active.condition("createOrgDeptId", "createdBy", context),
          model, cb, manager.getMetamodel());
      if (compiled != null) data = compiled;
    }
    return cb.and(ownership, data, cb.isNull(model.get("deletedAt")), cb.isTrue(model.get("enabled")),
        cb.isTrue(model.get("available")), cb.equal(model.get("modelType"), AiModelType.EMBEDDING));
  }
}
