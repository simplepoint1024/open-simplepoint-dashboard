package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.schema.ProjectionTableSchema;
import org.simplepoint.data.jpa.ProjectionQuery;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageInstanceView;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageResourceView;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageAdministrationQueryService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded, tenant-scoped package read model using the standard platform query contract. */
@Service
@Transactional(readOnly = true)
public class TenantPackageQueryServiceImpl
    implements TenantPackageQueryService, TenantPackageAdministrationQueryService {
  private final EntityManager em;
  private final Clock clock;

  /** Uses identity's selected persistence binding. */
  @Autowired
  public TenantPackageQueryServiceImpl(EntityManager em) {
    this(em, Clock.systemUTC());
  }

  TenantPackageQueryServiceImpl(EntityManager em, Clock clock) {
    this.em = em;
    this.clock = clock;
  }

  @Override
  public Map<String, Object> schema() {
    new TenantOwnerBoundary(em, () -> new TenantPackageException(Code.OWNER_REQUIRED))
        .currentTenant(false);
    return packageSchema(false);
  }

  @Override
  public Map<String, Object> schema(String tenantId) {
    requireAdministration(tenantId);
    return packageSchema(true);
  }

  private Map<String, Object> packageSchema(boolean administration) {
    var schema =
        new java.util.LinkedHashMap<>(
            ProjectionTableSchema.schema(
                TenantPackageInstanceView.class,
                "tenant.packages",
                Map.of(
                    "state",
                    Arrays.stream(TenantPackageInstance.State.values())
                        .map(
                            state ->
                                new ProjectionTableSchema.Choice(
                                    state.name(), "i18n:tenant.packages.state." + state.name()))
                        .toList(),
                    "acquisition",
                    Arrays.stream(TenantPackageInstance.Acquisition.values())
                        .map(
                            value ->
                                new ProjectionTableSchema.Choice(
                                    value.name(),
                                    "i18n:tenant.packages.acquisition." + value.name()))
                        .toList())));
    schema.put(
        "buttons",
        java.util.List.of(
            Map.of(
                "key",
                "resources",
                "title",
                "i18n:tenant.packages.actions.resources",
                "icon",
                "ProfileOutlined",
                "argumentMinSize",
                1,
                "argumentMaxSize",
                1,
                "sort",
                0),
            Map.of(
                "key",
                "activate",
                "title",
                "i18n:tenant.packages.actions.activate",
                "icon",
                "CheckCircleOutlined",
                "argumentMinSize",
                1,
                "argumentMaxSize",
                1,
                "sort",
                1)));
    if (administration) {
      schema.put(
          "buttons",
          java.util.List.of(
              Map.of(
                  "key",
                  "allocate",
                  "title",
                  "i18n:tenant.packages.actions.allocate",
                  "icon",
                  "PlusOutlined",
                  "argumentMinSize",
                  0,
                  "argumentMaxSize",
                  0,
                  "sort",
                  0),
              Map.of(
                  "key",
                  "resources",
                  "title",
                  "i18n:tenant.packages.actions.resources",
                  "icon",
                  "ProfileOutlined",
                  "argumentMinSize",
                  1,
                  "argumentMaxSize",
                  1,
                  "sort",
                  1),
              Map.of(
                  "key",
                  "revoke",
                  "title",
                  "i18n:tenant.packages.actions.revoke",
                  "icon",
                  "StopOutlined",
                  "argumentMinSize",
                  1,
                  "argumentMaxSize",
                  1,
                  "sort",
                  2,
                  "danger",
                  true)));
    }
    return schema;
  }

  @Override
  public Page<TenantPackageInstanceView> query(Map<String, String> filters, Pageable pageable) {
    String tenantId =
        new TenantOwnerBoundary(em, () -> new TenantPackageException(Code.OWNER_REQUIRED))
            .currentTenant(false);
    return queryTenant(tenantId, filters, pageable);
  }

  @Override
  public Page<TenantPackageInstanceView> query(
      String tenantId, Map<String, String> filters, Pageable pageable) {
    requireAdministration(tenantId);
    return queryTenant(tenantId, filters, pageable)
        .map(TenantPackageInstanceView::forAdministration);
  }

  private Page<TenantPackageInstanceView> queryTenant(
      String tenantId, Map<String, String> filters, Pageable pageable) {
    if (pageable == null || pageable.isUnpaged()) {
      throw new TenantPackageException(Code.INVALID_REQUEST);
    }
    if (pageable.getSort().isUnsorted()) {
      pageable =
          PageRequest.of(
              pageable.getPageNumber(), pageable.getPageSize(), Sort.by("assignedAt").descending());
    }
    var input =
        ProjectionQuery.prepareIgnoringCase(
            TenantPackageInstanceView.class,
            filters == null ? Map.of() : filters,
            pageable,
            Set.of("packageCode", "packageName"));
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(TenantPackageInstanceView.class);
    var root = query.from(TenantPackageInstance.class);
    var fields = fields(cb, root);
    query
        .select(
            cb.construct(
                TenantPackageInstanceView.class,
                root.get("id"),
                fields.get("packageCode"),
                fields.get("packageName"),
                root.get("durationDays"),
                root.get("state"),
                root.get("assignedAt"),
                fields.get("activatedAt"),
                root.get("revokedAt"),
                fields.get("resourceCount"),
                root.get("acquisition"),
                root.get("adoptedAt")))
        .where(cb.and(cb.equal(root.get("tenantId"), tenantId), input.predicate(cb, fields)))
        .orderBy(input.orders(cb, fields, root.get("id")));
    var content =
        em.createQuery(query)
            .setFirstResult((int) pageable.getOffset())
            .setMaxResults(pageable.getPageSize())
            .getResultList();
    var count = cb.createQuery(Long.class);
    var counted = count.from(TenantPackageInstance.class);
    count
        .select(cb.count(counted))
        .where(
            cb.and(
                cb.equal(counted.get("tenantId"), tenantId),
                input.predicate(cb, fields(cb, counted))));
    return new PageImpl<>(content, pageable, em.createQuery(count).getSingleResult());
  }

  @Override
  public TenantPackageInstanceView get(String instanceId) {
    String tenantId =
        new TenantOwnerBoundary(em, () -> new TenantPackageException(Code.OWNER_REQUIRED))
            .currentTenant(false);
    return getInstance(tenantId, instanceId);
  }

  @Override
  public TenantPackageInstanceView get(String tenantId, String instanceId) {
    requireAdministration(tenantId);
    return getInstance(tenantId, instanceId).forAdministration();
  }

  private TenantPackageInstanceView getInstance(String tenantId, String instanceId) {
    if (instanceId == null || instanceId.isBlank() || instanceId.length() > 36) {
      throw new TenantPackageException(Code.INSTANCE_NOT_FOUND);
    }
    var rows =
        em.createQuery(
                "select i from TenantPackageInstance i where i.tenantId = :tenant and i.id = :id",
                TenantPackageInstance.class)
            .setParameter("tenant", tenantId)
            .setParameter("id", instanceId)
            .setMaxResults(1)
            .getResultList();
    if (rows.isEmpty()) {
      throw new TenantPackageException(Code.INSTANCE_NOT_FOUND);
    }
    return TenantPackageInstanceView.of(rows.getFirst());
  }

  private Map<String, Expression<?>> fields(CriteriaBuilder cb, Root<TenantPackageInstance> root) {
    var namedPackage =
        root.get("acquisition")
            .in(
                TenantPackageInstance.Acquisition.ASSIGNED,
                TenantPackageInstance.Acquisition.LEGACY_PACKAGE);
    return Map.of(
        "packageCode",
        cb.<String>selectCase()
            .when(namedPackage, root.get("packageCode"))
            .otherwise(cb.nullLiteral(String.class)),
        "packageName",
        cb.<String>selectCase()
            .when(namedPackage, root.get("packageName"))
            .otherwise(cb.nullLiteral(String.class)),
        "durationDays",
        root.get("durationDays"),
        "state",
        root.get("state"),
        "assignedAt",
        root.get("assignedAt"),
        "activatedAt",
        cb.<java.time.Instant>selectCase()
            .when(
                cb.equal(root.get("acquisition"), TenantPackageInstance.Acquisition.ASSIGNED),
                root.get("activatedAt"))
            .otherwise(cb.nullLiteral(java.time.Instant.class)),
        "revokedAt",
        root.get("revokedAt"),
        "resourceCount",
        cb.size(root.get("resources")),
        "acquisition",
        root.get("acquisition"),
        "adoptedAt",
        root.get("adoptedAt"));
  }

  @Override
  public Page<TenantPackageResourceView> resources(String instanceId, Pageable pageable) {
    String tenantId =
        new TenantOwnerBoundary(em, () -> new TenantPackageException(Code.OWNER_REQUIRED))
            .currentTenant(false);
    return new TenantPackageResourceQuery(em)
        .query(tenantId, instanceId, pageable, clock.instant());
  }

  @Override
  public Page<TenantPackageResourceView> resources(
      String tenantId, String instanceId, Pageable pageable) {
    requireAdministration(tenantId);
    return new TenantPackageResourceQuery(em)
        .query(tenantId, instanceId, pageable, clock.instant());
  }

  private void requireAdministration(String tenantId) {
    TenantPackagePlatformBoundary.administrator();
    TenantPackagePlatformBoundary.requireTenant(em, tenantId);
  }
}
