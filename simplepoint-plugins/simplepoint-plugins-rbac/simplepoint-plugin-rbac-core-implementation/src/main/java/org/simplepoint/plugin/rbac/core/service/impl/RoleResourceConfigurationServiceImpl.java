package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.permission.PermissionPageDescriptor;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.core.query.TablePagination;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RoleResourceGrantDto;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.service.RoleResourceConfigurationService;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.FieldScope;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** SQL-paged editing with explicit changes and resource-level optimistic conflict detection. */
@Service
@Transactional(readOnly = true)
public class RoleResourceConfigurationServiceImpl implements RoleResourceConfigurationService {
  private static final int MAX_CHANGES = 5000;
  private static final int QUERY_BATCH = 200;
  private static final int MAX_SUBTREE = 5000;
  private final EntityManager em;
  private final RoleService roles;
  private final RoleRepository repository;
  private final ResourceAuthorizationVersionService authorizationVersions;
  private TenantResourceCatalog tenantResourceCatalog;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setTenantResourceCatalog(TenantResourceCatalog tenantResourceCatalog) {
    this.tenantResourceCatalog = tenantResourceCatalog;
  }

  /** Creates the bounded resource editor. */
  public RoleResourceConfigurationServiceImpl(
      EntityManager em,
      RoleService roles,
      RoleRepository repository,
      ResourceAuthorizationVersionService authorizationVersions) {
    this.em = em;
    this.roles = roles;
    this.repository = repository;
    this.authorizationVersions = authorizationVersions;
  }

  @Override
  public Page<Row> list(
      String roleId,
      String keyword,
      String state,
      String type,
      String parentId,
      boolean rootsOnly,
      boolean navigationOnly,
      Pageable page) {
    final String tenant = requireRole(roleId);
    if (page == null
        || page.isUnpaged()
        || page.getPageSize() > TablePagination.MAX_PAGE_SIZE
        || page.getOffset() > Integer.MAX_VALUE
        || page.getSort().isSorted()) {
      throw badRequest("每页最多 100 项，使用固定资源编码排序");
    }
    String search = keyword == null ? "" : keyword.trim();
    if (search.length() > 128 || !Set.of("all", "granted", "ungranted").contains(state)) {
      throw badRequest("不支持的资源筛选条件");
    }
    ResourceType resourceType = null;
    if (type != null && !type.isBlank()) {
      requireReadable(TenantResource.class, "type");
      try {
        resourceType = ResourceType.valueOf(type);
      } catch (IllegalArgumentException ex) {
        throw badRequest("不支持的资源类型");
      }
    }
    if (parentId != null || rootsOnly) {
      requireReadable(TenantResource.class, "parentId");
    }
    if (parentId != null && rootsOnly) {
      throw badRequest("不能同时筛选根资源和子资源");
    }
    return listTenant(
        tenant, roleId, search, state, resourceType, parentId, rootsOnly, navigationOnly, page);
  }

  @Override
  public Page<PolicyOption> policies(
      String roleId,
      String resourceCode,
      String kind,
      String keyword,
      Pageable page,
      String selectedId) {
    String tenant = requireRole(roleId);
    boolean data = "data".equals(kind);
    if (resourceCode == null || resourceCode.isBlank() || resourceCode.length() > 120) {
      throw badRequest("页面资源编码无效");
    }
    if ((!data && !"field".equals(kind))
        || page == null
        || page.isUnpaged()
        || page.getPageSize() < 1
        || page.getPageSize() > 50
        || page.getSort().isSorted()
        || page.getOffset() > Integer.MAX_VALUE) {
      throw badRequest("不支持的规则类型或分页参数");
    }
    var descriptor = permissionModels(tenant, List.of(resourceCode)).get(resourceCode);
    if (descriptor == null || !(data ? descriptor.data() : descriptor.fields())) {
      throw badRequest("当前页面尚未接入此类权限规则");
    }
    String search = keyword == null ? "" : keyword.trim();
    if (search.length() > 100) {
      throw badRequest("规则搜索词过长");
    }
    if (selectedId != null
        && (selectedId.isBlank()
            || selectedId.length() > 64
            || !selectedId.equals(selectedId.trim()))) {
      throw badRequest("规则标识无效");
    }
    String pattern =
        "%"
            + search
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
                .toLowerCase(java.util.Locale.ROOT)
            + "%";
    if (data) {
      var cb = em.getCriteriaBuilder();
      var query = cb.createQuery(DataScope.class);
      var root = query.from(DataScope.class);
      query
          .select(root)
          .where(
              cb.equal(root.get("tenantId"), tenant),
              cb.isNull(root.get("deletedAt")),
              cb.like(cb.lower(root.get("name")), pattern, '!'),
              selectedId == null ? cb.conjunction() : cb.equal(root.get("id"), selectedId),
              cb.or(
                  cb.notEqual(
                      root.get("type"), org.simplepoint.security.entity.DataScopeType.EXPRESSION),
                  cb.equal(root.get("resourceCode"), resourceCode)))
          .orderBy(cb.asc(root.get("name")), cb.asc(root.get("id")));
      var count = cb.createQuery(Long.class);
      var counted = count.from(DataScope.class);
      count
          .select(cb.count(counted))
          .where(
              cb.equal(counted.get("tenantId"), tenant),
              cb.isNull(counted.get("deletedAt")),
              cb.like(cb.lower(counted.get("name")), pattern, '!'),
              selectedId == null ? cb.conjunction() : cb.equal(counted.get("id"), selectedId),
              cb.or(
                  cb.notEqual(
                      counted.get("type"),
                      org.simplepoint.security.entity.DataScopeType.EXPRESSION),
                  cb.equal(counted.get("resourceCode"), resourceCode)));
      List<PolicyOption> values =
          em
              .createQuery(query)
              .setFirstResult((int) page.getOffset())
              .setMaxResults(page.getPageSize())
              .getResultList()
              .stream()
              .map(
                  scope -> new PolicyOption(scope.getId(), scope.getName(), scope.getType().name()))
              .toList();
      return new PageImpl<>(values, page, em.createQuery(count).getSingleResult());
    }
    Set<String> model = descriptor.fieldModels();
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(FieldScope.class);
    var root = query.from(FieldScope.class);
    query
        .select(root)
        .where(
            cb.equal(root.get("tenantId"), tenant),
            cb.isNull(root.get("deletedAt")),
            cb.like(cb.lower(root.get("name")), pattern, '!'),
            fieldEntryExists(cb, query, root, tenant, model),
            selectedId == null ? cb.conjunction() : cb.equal(root.get("id"), selectedId))
        .orderBy(cb.asc(root.get("name")), cb.asc(root.get("id")));
    var count = cb.createQuery(Long.class);
    var counted = count.from(FieldScope.class);
    count
        .select(cb.count(counted))
        .where(
            cb.equal(counted.get("tenantId"), tenant),
            cb.isNull(counted.get("deletedAt")),
            cb.like(cb.lower(counted.get("name")), pattern, '!'),
            fieldEntryExists(cb, count, counted, tenant, model),
            selectedId == null ? cb.conjunction() : cb.equal(counted.get("id"), selectedId));
    List<PolicyOption> values =
        em
            .createQuery(query)
            .setFirstResult((int) page.getOffset())
            .setMaxResults(page.getPageSize())
            .getResultList()
            .stream()
            .map(scope -> new PolicyOption(scope.getId(), scope.getName(), null))
            .toList();
    return new PageImpl<>(values, page, em.createQuery(count).getSingleResult());
  }

  private Predicate fieldEntryExists(
      CriteriaBuilder cb,
      CommonAbstractCriteria query,
      Root<FieldScope> scope,
      String tenant,
      Set<String> model) {
    var sub = query.subquery(Integer.class);
    var entry = sub.from(org.simplepoint.security.entity.FieldScopeEntry.class);
    sub.select(cb.literal(1))
        .where(
            cb.equal(entry.get("fieldScopeId"), scope.get("id")),
            cb.equal(entry.get("tenantId"), tenant),
            entry.get("resource").in(model),
            cb.isNull(entry.get("deletedAt")));
    return cb.exists(sub);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void save(String roleId, Patch patch) {
    String tenant = requireRole(roleId);
    tenantResourceCatalog.lockForGrantModification(tenant);
    saveTenant(tenant, roleId, patch);
  }

  @Override
  public BulkPreview previewSubtree(String roleId, String resourceId, boolean granted) {
    String tenant = requireRole(roleId);
    if (resourceId == null || resourceId.isBlank()) {
      throw badRequest("请选择资源目录");
    }
    return previewTenant(resourceId, granted, subtreeTenant(tenant, roleId, resourceId));
  }

  @Override
  public List<Row> subtreeRows(String roleId, String resourceId) {
    String tenant = requireRole(roleId);
    if (resourceId == null || resourceId.isBlank()) {
      throw badRequest("请选择资源目录");
    }
    var items = subtreeTenant(tenant, roleId, resourceId).resources();
    List<Row> result = new ArrayList<>(items.size());
    Set<String> eligible = tenantResourceCatalog.roleResourceIds(tenant);
    for (int start = 0; start < items.size(); start += QUERY_BATCH) {
      result.addAll(
          rowsTenant(
              items.subList(start, Math.min(start + QUERY_BATCH, items.size())),
              tenant,
              roleId,
              eligible));
    }
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void saveSubtree(String roleId, BulkCommand command) {
    String tenant = requireRole(roleId);
    tenantResourceCatalog.lockForGrantModification(tenant);
    if (command == null
        || command.resourceId() == null
        || command.resourceId().isBlank()
        || command.granted() == null
        || command.revision() == null) {
      throw badRequest("批量资源变更缺少目录、授权状态或版本");
    }
    saveSubtreeTenant(tenant, roleId, command);
  }

  private Page<Row> listTenant(
      String tenant,
      String roleId,
      String search,
      String state,
      ResourceType type,
      String parentId,
      boolean rootsOnly,
      boolean navigationOnly,
      Pageable page) {
    Set<String> eligible = tenantResourceCatalog.roleResourceIds(tenant);
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(TenantResource.class);
    var root = query.from(TenantResource.class);
    query
        .select(root)
        .where(
            tenantFilters(
                cb,
                query,
                root,
                tenant,
                roleId,
                search,
                state,
                type,
                parentId,
                rootsOnly,
                navigationOnly,
                eligible));
    query.orderBy(
        cb.asc(root.get("sort")),
        cb.asc(root.get("name")),
        cb.asc(root.get("code")),
        cb.asc(root.get("id")));
    List<TenantResource> resources =
        em.createQuery(query)
            .setFirstResult((int) page.getOffset())
            .setMaxResults(page.getPageSize())
            .getResultList();
    var count = cb.createQuery(Long.class);
    var counted = count.from(TenantResource.class);
    count
        .select(cb.count(counted))
        .where(
            tenantFilters(
                cb,
                count,
                counted,
                tenant,
                roleId,
                search,
                state,
                type,
                parentId,
                rootsOnly,
                navigationOnly,
                eligible));
    return new PageImpl<>(
        rowsTenant(resources, tenant, roleId, eligible),
        page,
        em.createQuery(count).getSingleResult());
  }

  private Predicate tenantFilters(
      CriteriaBuilder cb,
      CommonAbstractCriteria query,
      Root<TenantResource> resource,
      String tenant,
      String roleId,
      String search,
      String state,
      ResourceType type,
      String parentId,
      boolean rootsOnly,
      boolean navigationOnly,
      Set<String> eligible) {
    List<Predicate> predicates = new ArrayList<>();
    predicates.add(cb.equal(resource.get("tenantId"), tenant));
    predicates.add(inIds(cb, resource.get("id"), eligible));
    if (!search.isEmpty()) {
      String pattern =
          "%"
              + search
                  .replace("!", "!!")
                  .replace("%", "!%")
                  .replace("_", "!_")
                  .toLowerCase(java.util.Locale.ROOT)
              + "%";
      List<Predicate> matches = new ArrayList<>();
      for (String field : List.of("code", "alias", "label", "name", "title", "path")) {
        if (QueryCapabilities.readable(TenantResource.class, field)) {
          matches.add(cb.like(cb.lower(resource.get(field)), pattern, '!'));
        }
      }
      predicates.add(cb.or(matches.toArray(Predicate[]::new)));
    }
    if (type != null) {
      predicates.add(cb.equal(resource.get("type"), type));
    }
    if (navigationOnly) {
      predicates.add(navigationType(cb, resource));
    }
    if (parentId != null) {
      predicates.add(cb.equal(resource.get("parentId"), parentId));
    } else if (rootsOnly) {
      predicates.add(cb.isNull(resource.get("parentId")));
    }
    if (!"all".equals(state)) {
      var sub = query.subquery(Integer.class);
      var grant = sub.from(RoleResourceGrant.class);
      sub.select(cb.literal(1))
          .where(
              cb.equal(grant.get("tenantId"), tenant),
              cb.equal(grant.get("roleId"), roleId),
              cb.equal(grant.get("resourceCode"), resource.get("code")),
              cb.isNull(grant.get("deletedAt")));
      predicates.add("granted".equals(state) ? cb.exists(sub) : cb.not(cb.exists(sub)));
    }
    return cb.and(predicates.toArray(Predicate[]::new));
  }

  private static Predicate inIds(
      CriteriaBuilder cb, jakarta.persistence.criteria.Path<String> path, Set<String> ids) {
    var values = List.copyOf(ids);
    List<Predicate> batches = new ArrayList<>();
    for (int start = 0; start < values.size(); start += QUERY_BATCH) {
      batches.add(path.in(values.subList(start, Math.min(start + QUERY_BATCH, values.size()))));
    }
    return cb.or(batches.toArray(Predicate[]::new));
  }

  private static String resourceLabel(TenantResource resource) {
    return java.util.stream.Stream.of(
            resource.getAlias(),
            resource.getName(),
            resource.getLabel(),
            resource.getTitle(),
            resource.getCode())
        .filter(value -> value != null && !value.isBlank())
        .findFirst()
        .orElse(resource.getId());
  }

  private static Predicate navigationType(CriteriaBuilder cb, Root<TenantResource> resource) {
    return cb.not(resource.get("type").in(ResourceType.ACTION, ResourceType.API));
  }

  private List<Row> rowsTenant(
      List<TenantResource> resources, String tenant, String roleId, Set<String> eligible) {
    resources = resources.stream().filter(resource -> eligible.contains(resource.getId())).toList();
    if (resources.isEmpty()) {
      return List.of();
    }
    Map<String, PermissionPageDescriptor> models = new java.util.HashMap<>();
    for (TenantResource resource : resources) {
      if (resource.isGrantable()
          && resource.getType() == ResourceType.PAGE
          && resource.getTargetResourceId() == null) {
        var descriptor = PermissionPageDescriptor.fromJson(resource.getPermissionMetadata());
        if (descriptor != null) {
          models.put(resource.getCode(), descriptor);
        }
      }
    }
    Map<String, List<RoleResourceGrant>> grants =
        em
            .createQuery(
                "select g from RoleResourceGrant g where g.tenantId = :tenant and g.roleId = :role"
                    + " and g.deletedAt is null and g.resourceCode in :codes",
                RoleResourceGrant.class)
            .setParameter("tenant", tenant)
            .setParameter("role", roleId)
            .setParameter("codes", resources.stream().map(TenantResource::getCode).toList())
            .getResultList()
            .stream()
            .collect(Collectors.groupingBy(RoleResourceGrant::getResourceCode));
    Set<String> parentIds = new java.util.HashSet<>();
    Set<String> navigationParents = new java.util.HashSet<>();
    if (QueryCapabilities.readable(TenantResource.class, "parentId")) {
      var cb = em.getCriteriaBuilder();
      var query = cb.createTupleQuery();
      var child = query.from(TenantResource.class);
      query
          .multiselect(child.get("parentId"), child.get("type"))
          .distinct(true)
          .where(
              cb.equal(child.get("tenantId"), tenant),
              inIds(cb, child.get("id"), eligible),
              child.get("parentId").in(resources.stream().map(TenantResource::getId).toList()));
      for (Tuple item : em.createQuery(query).getResultList()) {
        parentIds.add(item.get(0, String.class));
        if (item.get(1, ResourceType.class) != ResourceType.ACTION
            && item.get(1, ResourceType.class) != ResourceType.API) {
          navigationParents.add(item.get(0, String.class));
        }
      }
    }
    return resources.stream()
        .map(
            resource -> {
              List<RoleResourceGrant> assigned = grants.getOrDefault(resource.getCode(), List.of());
              return new Row(
                  resource.getId(),
                  resource.getCode(),
                  resourceLabel(resource),
                  resource.getType().name(),
                  resource.getPath(),
                  resource.getParentId(),
                  resource.getIcon(),
                  parentIds.contains(resource.getId()),
                  navigationParents.contains(resource.getId()),
                  resource.getDescription(),
                  resource.isGrantable(),
                  !assigned.isEmpty(),
                  digest(
                      resource.getId()
                          + "|"
                          + resource.getCode()
                          + "|"
                          + resource.getUpdatedAt()
                          + "|"
                          + assigned.stream()
                              .map(g -> g.getId() + ":" + g.getUpdatedAt())
                              .sorted()
                              .collect(Collectors.joining("|"))),
                  policySlot(models.get(resource.getCode()), assigned, true),
                  policySlot(models.get(resource.getCode()), assigned, false));
            })
        .toList();
  }

  private void saveTenant(String tenant, String roleId, Patch patch) {
    if (patch == null || patch.changes() == null || patch.changes().size() > MAX_CHANGES) {
      throw badRequest("每次最多保存 5000 项明确变更");
    }
    if (repository.lockForAuthorization(tenant, roleId).isEmpty()) {
      throw new AccessDeniedException("角色不存在或不属于当前租户");
    }
    Set<String> ids = new LinkedHashSet<>();
    Set<String> codes = new LinkedHashSet<>();
    for (Change change : patch.changes()) {
      if (change == null
          || change.resourceId() == null
          || change.resourceCode() == null
          || change.revision() == null
          || change.granted() == null
          || !ids.add(change.resourceId())
          || !codes.add(change.resourceCode())) {
        throw badRequest("资源变更缺少标识、版本或存在重复项");
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    Set<String> eligible = tenantResourceCatalog.roleResourceIds(tenant);
    Map<String, Row> current = new java.util.HashMap<>();
    List<String> requested = List.copyOf(ids);
    for (int start = 0; start < requested.size(); start += QUERY_BATCH) {
      List<TenantResource> found =
          em.createQuery(
                  "select r from TenantResource r"
                      + " where r.tenantId = :tenant and r.enabled = true and r.id in :ids",
                  TenantResource.class)
              .setParameter("tenant", tenant)
              .setParameter(
                  "ids", requested.subList(start, Math.min(start + QUERY_BATCH, requested.size())))
              .getResultList();
      rowsTenant(
              found.stream().filter(row -> eligible.contains(row.getId())).toList(),
              tenant,
              roleId,
              eligible)
          .forEach(row -> current.put(row.id(), row));
    }
    Set<String> added = new LinkedHashSet<>();
    Set<String> removed = new LinkedHashSet<>();
    for (Change change : patch.changes()) {
      Row row = current.get(change.resourceId());
      if (row == null
          || !row.grantable()
          || !Objects.equals(row.code(), change.resourceCode())
          || !Objects.equals(row.revision(), change.revision())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "资源或授权已发生变化，请重新载入后确认");
      }
      if (row.granted() != change.granted()) {
        (change.granted() ? added : removed).add(row.code());
      }
    }
    applyChanges(tenant, roleId, added, removed);
    applyPolicyChanges(tenant, roleId, patch.changes(), current);
  }

  private record TenantSubtree(
      List<TenantResource> resources,
      Set<String> grantedCodes,
      Set<String> grantableCodes,
      String revision) {}

  private TenantSubtree subtreeTenant(String tenant, String roleId, String rootId) {
    requireReadable(TenantResource.class, "parentId");
    Set<String> eligible = tenantResourceCatalog.roleResourceIds(tenant);
    if (!eligible.contains(rootId)) {
      throw new AccessDeniedException("资源目录不存在或无权访问");
    }
    TenantResource root = em.find(TenantResource.class, new TenantResourceKey(tenant, rootId));
    if (root == null || !root.isEnabled()) {
      throw new AccessDeniedException("资源目录不存在或无权访问");
    }
    List<TenantResource> resources = new ArrayList<>();
    resources.add(root);
    Set<String> seen = new java.util.HashSet<>();
    seen.add(rootId);
    List<String> parents = List.of(rootId);
    while (!parents.isEmpty()) {
      List<String> next = new ArrayList<>();
      for (int start = 0; start < parents.size(); start += QUERY_BATCH) {
        var cb = em.getCriteriaBuilder();
        var query = cb.createQuery(TenantResource.class);
        var child = query.from(TenantResource.class);
        query
            .select(child)
            .where(
                cb.equal(child.get("tenantId"), tenant),
                inIds(cb, child.get("id"), eligible),
                child
                    .get("parentId")
                    .in(parents.subList(start, Math.min(start + QUERY_BATCH, parents.size()))));
        List<TenantResource> children =
            em.createQuery(query).setMaxResults(MAX_SUBTREE + 1).getResultList();
        for (TenantResource item : children) {
          if (!seen.add(item.getId())) {
            continue;
          }
          resources.add(item);
          next.add(item.getId());
          if (resources.size() > MAX_SUBTREE) {
            throw badRequest("目录内可见资源超过 5000 项");
          }
        }
      }
      parents = next;
    }
    resources.sort(java.util.Comparator.comparing(TenantResource::getId));
    Set<String> granted = new LinkedHashSet<>();
    StringBuilder revision = new StringBuilder();
    for (TenantResource item : resources) {
      revision
          .append(item.getId())
          .append('|')
          .append(item.getCode())
          .append('|')
          .append(item.getParentId())
          .append('|')
          .append(item.getUpdatedAt())
          .append(';');
    }
    for (int start = 0; start < resources.size(); start += QUERY_BATCH) {
      List<String> codes =
          resources.subList(start, Math.min(start + QUERY_BATCH, resources.size())).stream()
              .map(TenantResource::getCode)
              .toList();
      for (RoleResourceGrant grant :
          em.createQuery(
                  "select g from RoleResourceGrant g"
                      + " where g.tenantId = :tenant and g.roleId = :role and g.deletedAt is null"
                      + " and g.resourceCode in :codes order by g.resourceCode, g.id",
                  RoleResourceGrant.class)
              .setParameter("tenant", tenant)
              .setParameter("role", roleId)
              .setParameter("codes", codes)
              .getResultList()) {
        granted.add(grant.getResourceCode());
        revision
            .append(grant.getResourceCode())
            .append(':')
            .append(grant.getId())
            .append(':')
            .append(grant.getUpdatedAt())
            .append(';');
      }
    }
    Set<String> grantableCodes =
        tenantResourceCatalog.grantableCodes(
            tenant, resources.stream().map(TenantResource::getCode).toList());
    return new TenantSubtree(resources, granted, grantableCodes, digest(revision.toString()));
  }

  private BulkPreview previewTenant(String rootId, boolean granted, TenantSubtree subtree) {
    int total = 0;
    int changed = 0;
    for (TenantResource entry : subtree.resources()) {
      if (!subtree.grantableCodes().contains(entry.getCode())) {
        continue;
      }
      total++;
      if (subtree.grantedCodes().contains(entry.getCode()) != granted) {
        changed++;
      }
    }
    return new BulkPreview(rootId, granted, total, changed, subtree.revision());
  }

  private void saveSubtreeTenant(String tenant, String roleId, BulkCommand command) {
    if (repository.lockForAuthorization(tenant, roleId).isEmpty()) {
      throw new AccessDeniedException("角色不存在或不属于当前租户");
    }
    TenantSubtree subtree = subtreeTenant(tenant, roleId, command.resourceId());
    if (!Objects.equals(subtree.revision(), command.revision())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "目录资源或授权已发生变化，请重新预览");
    }
    Set<String> added = new LinkedHashSet<>();
    Set<String> removed = new LinkedHashSet<>();
    for (TenantResource entry : subtree.resources()) {
      if (!subtree.grantableCodes().contains(entry.getCode())) {
        continue;
      }
      if (command.granted() && !subtree.grantedCodes().contains(entry.getCode())) {
        added.add(entry.getCode());
      }
      if (!command.granted() && subtree.grantedCodes().contains(entry.getCode())) {
        removed.add(entry.getCode());
      }
    }
    applyChanges(tenant, roleId, added, removed);
  }

  private void applyChanges(String tenant, String roleId, Set<String> added, Set<String> removed) {
    if (!removed.isEmpty() && !hasRolePolicy(tenant, roleId)) {
      List<String> codes = List.copyOf(removed);
      for (int start = 0; start < codes.size(); start += QUERY_BATCH) {
        Long legacy =
            em.createQuery(
                    "select count(g) from RoleResourceGrant g where g.tenantId = :tenant and"
                        + " g.roleId = :role and g.deletedAt is null and g.resourceCode in :codes"
                        + " and (g.pagePolicy is null or g.pagePolicy = false) and (g.dataScopeId"
                        + " is not null or g.fieldScopeId is not null)",
                    Long.class)
                .setParameter("tenant", tenant)
                .setParameter("role", roleId)
                .setParameter(
                    "codes", codes.subList(start, Math.min(start + QUERY_BATCH, codes.size())))
                .getSingleResult();
        if (legacy > 0) {
          throw new ResponseStatusException(
              HttpStatus.CONFLICT, "待撤销的旧授权仍承载数据或字段策略，需先迁移旧策略；本次未保存任何变更");
        }
      }
    }
    List<String> additions = List.copyOf(added);
    for (int start = 0; start < additions.size(); start += QUERY_BATCH) {
      RoleResourceGrantDto dto = new RoleResourceGrantDto();
      dto.setRoleId(roleId);
      dto.setResourceCodes(
          new LinkedHashSet<>(
              additions.subList(start, Math.min(start + QUERY_BATCH, additions.size()))));
      roles.authorize(dto);
    }
    List<String> removals = List.copyOf(removed);
    for (int start = 0; start < removals.size(); start += QUERY_BATCH) {
      roles.unauthorized(
          roleId,
          new LinkedHashSet<>(
              removals.subList(start, Math.min(start + QUERY_BATCH, removals.size()))));
    }
  }

  private boolean hasRolePolicy(String tenant, String roleId) {
    return em.createQuery(
                "select count(b) from RoleScopeBinding b where b.tenantId = :tenant"
                    + " and b.roleId = :role and b.deletedAt is null",
                Long.class)
            .setParameter("tenant", tenant)
            .setParameter("role", roleId)
            .getSingleResult()
        > 0;
  }

  @Override
  public List<Row> contextRows(String roleId, List<String> resourceIds) {
    String tenant = requireRole(roleId);
    requireReadable(TenantResource.class, "parentId");
    if (resourceIds == null
        || resourceIds.size() > QUERY_BATCH
        || resourceIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 128)) {
      throw badRequest("每次最多查询 200 项资源的上级目录");
    }
    Set<String> eligible = tenantResourceCatalog.roleResourceIds(tenant);
    Set<String> seen = new LinkedHashSet<>();
    List<String> pending = resourceIds.stream().filter(eligible::contains).distinct().toList();
    List<Row> result = new ArrayList<>();
    while (!pending.isEmpty()) {
      seen.addAll(pending);
      if (seen.size() > MAX_SUBTREE) {
        throw badRequest("资源层级超过预览限制");
      }
      List<String> parents = new ArrayList<>();
      for (int start = 0; start < pending.size(); start += QUERY_BATCH) {
        var ids = pending.subList(start, Math.min(start + QUERY_BATCH, pending.size()));
        var entries =
            em.createQuery(
                    "select r from TenantResource r where r.tenantId = :tenant"
                        + " and r.id in :ids",
                    TenantResource.class)
                .setParameter("tenant", tenant)
                .setParameter("ids", ids)
                .getResultList();
        var level = rowsTenant(entries, tenant, roleId, eligible);
        result.addAll(level);
        level.stream()
            .map(Row::parentId)
            .filter(Objects::nonNull)
            .filter(eligible::contains)
            .forEach(parents::add);
      }
      pending = parents.stream().filter(id -> !seen.contains(id)).distinct().toList();
    }
    return result;
  }

  private String requireRole(String roleId) {
    final String tenant = roles.requireManagementTenant();
    var caller = AuthorizationContextHolder.getContext();
    if (tenantResourceCatalog == null
        || tenant == null
        || tenant.isBlank()
        || caller == null
        || caller.getUserId() == null
        || !tenant.equals(caller.getAttribute("X-Tenant-Id"))
        || (caller.getScopeType() != org.simplepoint.core.AuthorizationScopeType.TENANT
            && caller.getScopeType() != org.simplepoint.core.AuthorizationScopeType.PERSONAL)) {
      throw new AccessDeniedException("租户资源目录不可用");
    }
    requireReadable(Role.class, "id");
    for (String field : List.of("id", "code", "grantable")) {
      requireReadable(TenantResource.class, field);
    }
    for (String field : List.of("roleId", "resourceCode")) {
      requireReadable(RoleResourceGrant.class, field);
    }
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(Long.class);
    var role = query.from(Role.class);
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    var scope = context == null ? null : context.activeDataScope();
    var allowed =
        org.simplepoint.data.jpa.DataScopePredicates.compile(
            scope == null ? null : scope.condition("createOrgDeptId", "createdBy", context),
            role,
            cb,
            em.getMetamodel());
    query
        .select(cb.count(role))
        .where(
            cb.equal(role.get("tenantId"), tenant),
            cb.equal(role.get("id"), roleId),
            cb.isNull(role.get("deletedAt")),
            allowed == null ? cb.conjunction() : allowed);
    Long count = em.createQuery(query).getSingleResult();
    if (count != 1) {
      throw new AccessDeniedException("角色不存在或不属于当前租户");
    }
    return tenant;
  }

  private Map<String, PermissionPageDescriptor> permissionModels(
      String tenant, java.util.Collection<String> codes) {
    Map<String, PermissionPageDescriptor> result = new java.util.HashMap<>();
    var eligible = tenantResourceCatalog.grantableCodes(tenant, codes);
    List<String> values = codes.stream().filter(eligible::contains).distinct().toList();
    for (int offset = 0; offset < values.size(); offset += QUERY_BATCH) {
      em.createQuery(
              "select r.code,r.permissionMetadata from TenantResource r where r.tenantId=:tenant"
                  + " and r.type='PAGE' and r.permissionMetadata is not null and r.targetResourceId"
                  + " is null and r.code in :codes",
              Object[].class)
          .setParameter("tenant", tenant)
          .setParameter(
              "codes", values.subList(offset, Math.min(offset + QUERY_BATCH, values.size())))
          .setMaxResults(QUERY_BATCH)
          .getResultList()
          .forEach(
              row -> {
                var descriptor = PermissionPageDescriptor.fromJson((String) row[1]);
                if (descriptor != null) {
                  result.put((String) row[0], descriptor);
                }
              });
    }
    return result;
  }

  private static PolicySlot policySlot(
      PermissionPageDescriptor model, List<RoleResourceGrant> grants, boolean data) {
    boolean supported =
        model != null && (data ? model.data() : model.fields()) && grants.size() <= 1;
    String id =
        grants.size() == 1
            ? (data ? grants.getFirst().getDataScopeId() : grants.getFirst().getFieldScopeId())
            : null;
    return new PolicySlot(supported, id);
  }

  private void applyPolicyChanges(
      String tenant, String roleId, List<Change> changes, Map<String, Row> current) {
    boolean updated = false;
    var models = permissionModels(tenant, current.values().stream().map(Row::code).toList());
    for (Change change : changes) {
      if (change.dataPermission() == null && change.fieldPermission() == null) {
        continue;
      }
      Row row = current.get(change.resourceId());
      if (!change.granted() || row == null) {
        throw badRequest("只能为已授权的页面配置权限规则");
      }
      if (change.dataPermission() != null && !row.dataPermission().supported()) {
        throw badRequest("当前页面尚未接入数据权限 ORM 执行链路");
      }
      if (change.fieldPermission() != null && !row.fieldPermission().supported()) {
        throw badRequest("当前页面尚未接入字段权限执行链路");
      }
      String dataId =
          change.dataPermission() == null ? null : trimToNull(change.dataPermission().policyId());
      String fieldId =
          change.fieldPermission() == null ? null : trimToNull(change.fieldPermission().policyId());
      if (dataId != null) {
        DataScope policy =
            em.find(DataScope.class, dataId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (policy == null
            || policy.getDeletedAt() != null
            || !tenant.equals(policy.getTenantId())) {
          throw new AccessDeniedException("数据范围不存在或不属于当前租户");
        }
        if (policy.getType() == org.simplepoint.security.entity.DataScopeType.EXPRESSION) {
          if (!row.code().equals(policy.getResourceCode())) {
            throw badRequest("表达式规则只能绑定到其目标页面");
          }
          org.simplepoint.core.permission.PermissionExpression.validate(
              policy.getExpression(), models.get(row.code()).commonProperties());
        }
      }
      if (fieldId != null) {
        FieldScope policy =
            em.find(FieldScope.class, fieldId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (policy == null
            || policy.getDeletedAt() != null
            || !tenant.equals(policy.getTenantId())) {
          throw new AccessDeniedException("字段范围不存在或不属于当前租户");
        }
        Set<String> model =
            models.get(row.code()) == null ? Set.of() : models.get(row.code()).fieldModels();
        if (model.isEmpty()
            || policy.getEntries().stream()
                .noneMatch(
                    entry ->
                        model.contains(entry.getResource())
                            && tenant.equals(entry.getTenantId())
                            && entry.getAccess() != null)) {
          throw badRequest("所选字段规则没有当前页面的有效字段条目");
        }
      }
      List<RoleResourceGrant> grants =
          em.createQuery(
                  "select g from RoleResourceGrant g where g.tenantId = :tenant and g.roleId ="
                      + " :role and g.resourceCode = :code and g.deletedAt is null",
                  RoleResourceGrant.class)
              .setParameter("tenant", tenant)
              .setParameter("role", roleId)
              .setParameter("code", row.code())
              .getResultList();
      if (grants.size() != 1) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "页面授权记录发生变化或存在重复项，请刷新后重试");
      }
      RoleResourceGrant grant = grants.getFirst();
      grant.setPagePolicy(true);
      if (change.dataPermission() != null) {
        grant.setDataScopeId(dataId);
      }
      if (change.fieldPermission() != null) {
        grant.setFieldScopeId(fieldId);
      }
      updated = true;
    }
    if (updated) {
      em.flush();
      authorizationVersions.refreshTenant(tenant);
    }
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static void requireReadable(Class<?> model, String field) {
    if (!QueryCapabilities.readable(model, field)) {
      throw new AccessDeniedException("无权读取资源授权配置所需字段");
    }
  }

  private static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
