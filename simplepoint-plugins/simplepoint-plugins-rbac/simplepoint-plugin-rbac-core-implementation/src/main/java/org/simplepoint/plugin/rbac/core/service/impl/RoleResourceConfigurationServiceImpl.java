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
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RoleResourceGrantDto;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.service.RoleResourceConfigurationService;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
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
  private TenantResourceCatalog tenantResourceCatalog;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setTenantResourceCatalog(TenantResourceCatalog tenantResourceCatalog) {
    this.tenantResourceCatalog = tenantResourceCatalog;
  }

  /** Creates the bounded resource editor. */
  public RoleResourceConfigurationServiceImpl(
      EntityManager em, RoleService roles, RoleRepository repository) {
    this.em = em;
    this.roles = roles;
    this.repository = repository;
  }

  @Override
  public Page<Row> list(String roleId, String keyword, String state, String type,
      String parentId, boolean rootsOnly, boolean navigationOnly, Pageable page) {
    final String tenant = requireRole(roleId);
    if (page == null || page.isUnpaged() || page.getPageSize() > 100
        || page.getOffset() > Integer.MAX_VALUE || page.getSort().isSorted()) {
      throw badRequest("每页最多 100 项，使用固定资源编码排序");
    }
    String search = keyword == null ? "" : keyword.trim();
    if (search.length() > 128 || !Set.of("all", "granted", "ungranted").contains(state)) {
      throw badRequest("不支持的资源筛选条件");
    }
    ResourceType resourceType = null;
    if (type != null && !type.isBlank()) {
      requireReadable(Resource.class, "type");
      try {
        resourceType = ResourceType.valueOf(type);
      } catch (IllegalArgumentException ex) {
        throw badRequest("不支持的资源类型");
      }
    }
    if (parentId != null || rootsOnly) {
      requireReadable(Resource.class, "parentId");
    }
    if (parentId != null && rootsOnly) {
      throw badRequest("不能同时筛选根资源和子资源");
    }
    if (custom(tenant)) {
      return listCustom(tenant, roleId, search, state, resourceType,
          parentId, rootsOnly, navigationOnly, page);
    }
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(Resource.class);
    var resource = query.from(Resource.class);
    query.select(resource).where(filters(cb, query, resource, tenant, roleId,
        search, state, resourceType, parentId, rootsOnly, navigationOnly));
    query.orderBy(cb.asc(resource.get("sort")), cb.asc(resource.get("name")),
        cb.asc(resource.get("code")), cb.asc(resource.get("id")));
    List<Resource> resources = em.createQuery(query).setFirstResult((int) page.getOffset())
        .setMaxResults(page.getPageSize()).getResultList();
    var count = cb.createQuery(Long.class);
    var counted = count.from(Resource.class);
    count.select(cb.count(counted)).where(filters(cb, count, counted, tenant, roleId,
        search, state, resourceType, parentId, rootsOnly, navigationOnly));
    return new PageImpl<>(rows(resources, tenant, roleId), page,
        em.createQuery(count).getSingleResult());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void save(String roleId, Patch patch) {
    final String tenant = requireRole(roleId);
    if (custom(tenant)) {
      saveCustom(tenant, roleId, patch);
      return;
    }
    if (patch == null || patch.changes() == null || patch.changes().size() > MAX_CHANGES) {
      throw badRequest("每次最多保存 5000 项明确变更");
    }
    if (repository.lockForAuthorization(tenant, roleId).isEmpty()) {
      throw new AccessDeniedException("角色不存在或不属于当前租户");
    }
    Set<String> ids = new LinkedHashSet<>();
    Set<String> codes = new LinkedHashSet<>();
    for (Change change : patch.changes()) {
      if (change == null || change.resourceId() == null || change.resourceCode() == null
          || change.revision() == null || change.granted() == null || !ids.add(change.resourceId())
          || !codes.add(change.resourceCode())) {
        throw badRequest("资源变更缺少标识、版本或存在重复项");
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    Map<String, Row> current = new java.util.HashMap<>();
    List<String> requestedIds = List.copyOf(ids);
    for (int start = 0; start < requestedIds.size(); start += QUERY_BATCH) {
      var cb = em.getCriteriaBuilder();
      var query = cb.createQuery(Resource.class);
      var resource = query.from(Resource.class);
      query.select(resource).where(visible(cb, query, resource, tenant),
          resource.get("id").in(requestedIds.subList(start,
              Math.min(start + QUERY_BATCH, requestedIds.size()))));
      rows(em.createQuery(query).getResultList(), tenant, roleId)
          .forEach(row -> current.put(row.id(), row));
    }
    Set<String> added = new LinkedHashSet<>();
    Set<String> removed = new LinkedHashSet<>();
    for (Change change : patch.changes()) {
      Row row = current.get(change.resourceId());
      if (row == null || !row.grantable() || !Objects.equals(row.code(), change.resourceCode())
          || !Objects.equals(row.revision(), change.revision())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "资源或授权已发生变化，请重新载入该项后确认；本次未保存任何变更");
      }
      if (row.granted() != change.granted()) {
        (change.granted() ? added : removed).add(row.code());
      }
    }
    applyChanges(tenant, roleId, added, removed);
  }

  @Override
  public BulkPreview previewSubtree(String roleId, String resourceId, boolean granted) {
    String tenant = requireRole(roleId);
    if (resourceId == null || resourceId.isBlank()) {
      throw badRequest("请选择资源目录");
    }
    if (custom(tenant)) return previewCustom(resourceId, granted, subtreeCustom(tenant, roleId, resourceId));
    return preview(resourceId, granted, subtree(tenant, roleId, resourceId));
  }

  @Override
  public List<Row> subtreeRows(String roleId, String resourceId) {
    String tenant = requireRole(roleId);
    if (resourceId == null || resourceId.isBlank()) throw badRequest("请选择资源目录");
    if (custom(tenant)) {
      List<TenantResource> items = subtreeCustom(tenant, roleId, resourceId).resources();
      List<Row> result = new ArrayList<>(items.size());
      for (int start = 0; start < items.size(); start += QUERY_BATCH) {
        result.addAll(rowsCustom(items.subList(start, Math.min(start + QUERY_BATCH, items.size())), tenant, roleId));
      }
      return result;
    }
    List<Resource> items = subtree(tenant, roleId, resourceId).resources();
    List<Row> result = new ArrayList<>(items.size());
    for (int start = 0; start < items.size(); start += QUERY_BATCH) {
      result.addAll(rows(items.subList(start, Math.min(start + QUERY_BATCH, items.size())),
          tenant, roleId));
    }
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void saveSubtree(String roleId, BulkCommand command) {
    String tenant = requireRole(roleId);
    if (command == null || command.resourceId() == null || command.resourceId().isBlank()
        || command.granted() == null || command.revision() == null) {
      throw badRequest("批量资源变更缺少目录、授权状态或版本");
    }
    if (custom(tenant)) {
      saveSubtreeCustom(tenant, roleId, command);
      return;
    }
    if (repository.lockForAuthorization(tenant, roleId).isEmpty()) {
      throw new AccessDeniedException("角色不存在或不属于当前租户");
    }
    Subtree current = subtree(tenant, roleId, command.resourceId());
    if (!Objects.equals(current.revision(), command.revision())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "目录资源或授权已发生变化，请重新预览后再提交；本次未保存任何变更");
    }
    Set<String> added = new LinkedHashSet<>();
    Set<String> removed = new LinkedHashSet<>();
    for (Resource resource : current.resources()) {
      if (!Boolean.TRUE.equals(resource.getGrantable())) continue;
      String code = resource.getCode();
      if (command.granted() && !current.grantedCodes().contains(code)) added.add(code);
      if (!command.granted() && current.grantedCodes().contains(code)) removed.add(code);
    }
    applyChanges(tenant, roleId, added, removed);
  }

  private record Subtree(List<Resource> resources, Set<String> grantedCodes, String revision) {}

  private boolean custom(String tenant) {
    return tenantResourceCatalog != null
        && tenantResourceCatalog.mode(tenant) == TenantResourceMode.CUSTOM;
  }

  private Page<Row> listCustom(String tenant, String roleId, String search, String state,
      ResourceType type, String parentId, boolean rootsOnly, boolean navigationOnly, Pageable page) {
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(TenantResource.class);
    var root = query.from(TenantResource.class);
    query.select(root).where(customFilters(cb, query, root, tenant, roleId, search, state,
        type, parentId, rootsOnly, navigationOnly));
    query.orderBy(cb.asc(root.get("sort")), cb.asc(root.get("name")), cb.asc(root.get("code")));
    List<TenantResource> resources = em.createQuery(query).setFirstResult((int) page.getOffset())
        .setMaxResults(page.getPageSize()).getResultList();
    var count = cb.createQuery(Long.class);
    var counted = count.from(TenantResource.class);
    count.select(cb.count(counted)).where(customFilters(cb, count, counted, tenant, roleId,
        search, state, type, parentId, rootsOnly, navigationOnly));
    return new PageImpl<>(rowsCustom(resources, tenant, roleId), page, em.createQuery(count).getSingleResult());
  }

  private Predicate customFilters(CriteriaBuilder cb, CommonAbstractCriteria query,
      Root<TenantResource> resource, String tenant, String roleId, String search, String state,
      ResourceType type, String parentId, boolean rootsOnly, boolean navigationOnly) {
    List<Predicate> predicates = new ArrayList<>();
    predicates.add(cb.equal(resource.get("tenantId"), tenant));
    predicates.add(cb.isTrue(resource.get("enabled")));
    if (!search.isEmpty()) {
      String pattern = "%" + search.replace("!", "!!").replace("%", "!%")
          .replace("_", "!_").toLowerCase(java.util.Locale.ROOT) + "%";
      predicates.add(cb.or(cb.like(cb.lower(resource.get("code")), pattern, '!'),
          cb.like(cb.lower(resource.get("name")), pattern, '!')));
    }
    if (type != null) predicates.add(cb.equal(resource.get("type"), type));
    if (navigationOnly) predicates.add(navigationTypeCustom(cb, resource));
    if (parentId != null) predicates.add(cb.equal(resource.get("parentId"), parentId));
    else if (rootsOnly) predicates.add(cb.isNull(resource.get("parentId")));
    if (!"all".equals(state)) {
      var sub = query.subquery(Integer.class);
      var grant = sub.from(RoleResourceGrant.class);
      sub.select(cb.literal(1)).where(cb.equal(grant.get("tenantId"), tenant),
          cb.equal(grant.get("roleId"), roleId),
          cb.equal(grant.get("resourceCode"), resource.get("code")),
          cb.isNull(grant.get("deletedAt")));
      predicates.add("granted".equals(state) ? cb.exists(sub) : cb.not(cb.exists(sub)));
    }
    return cb.and(predicates.toArray(Predicate[]::new));
  }

  private static Predicate navigationTypeCustom(CriteriaBuilder cb, Root<TenantResource> resource) {
    return cb.not(resource.get("type").in(ResourceType.ACTION, ResourceType.API));
  }

  private List<Row> rowsCustom(List<TenantResource> resources, String tenant, String roleId) {
    if (resources.isEmpty()) return List.of();
    Set<String> grantableCodes = tenantResourceCatalog.grantableCodes(tenant,
        resources.stream().map(TenantResource::getCode).toList());
    Map<String, List<RoleResourceGrant>> grants = em.createQuery(
        "select g from RoleResourceGrant g where g.tenantId = :tenant and g.roleId = :role"
            + " and g.deletedAt is null and g.resourceCode in :codes", RoleResourceGrant.class)
        .setParameter("tenant", tenant).setParameter("role", roleId)
        .setParameter("codes", resources.stream().map(TenantResource::getCode).toList())
        .getResultList().stream().collect(Collectors.groupingBy(RoleResourceGrant::getResourceCode));
    List<Object[]> children = em.createQuery(
        "select distinct r.parentId, r.type from TenantResource r where r.tenantId = :tenant"
            + " and r.enabled = true and r.parentId in :ids", Object[].class)
        .setParameter("tenant", tenant)
        .setParameter("ids", resources.stream().map(TenantResource::getId).toList())
        .getResultList();
    Set<String> parentIds = new java.util.HashSet<>();
    Set<String> navigationParents = new java.util.HashSet<>();
    for (Object[] item : children) {
      parentIds.add((String) item[0]);
      if (item[1] != ResourceType.ACTION && item[1] != ResourceType.API) {
        navigationParents.add((String) item[0]);
      }
    }
    return resources.stream().map(resource -> {
      List<RoleResourceGrant> assigned = grants.getOrDefault(resource.getCode(), List.of());
      return new Row(resource.getId(), resource.getCode(), resource.getName(),
          resource.getType().name(), resource.getPath(), resource.getParentId(),
          resource.getIcon(), parentIds.contains(resource.getId()),
          navigationParents.contains(resource.getId()), resource.getDescription(),
          resource.isGrantable() && grantableCodes.contains(resource.getCode()), !assigned.isEmpty(),
          digest(resource.getId() + "|" + resource.getCode() + "|" + resource.getUpdatedAt()
              + "|" + assigned.stream().map(g -> g.getId() + ":" + g.getUpdatedAt())
                  .sorted().collect(Collectors.joining("|"))),
          new PolicySlot(false, null), new PolicySlot(false, null));
    }).toList();
  }

  private void saveCustom(String tenant, String roleId, Patch patch) {
    if (patch == null || patch.changes() == null || patch.changes().size() > MAX_CHANGES) {
      throw badRequest("每次最多保存 5000 项明确变更");
    }
    if (repository.lockForAuthorization(tenant, roleId).isEmpty()) {
      throw new AccessDeniedException("角色不存在或不属于当前租户");
    }
    Set<String> ids = new LinkedHashSet<>();
    Set<String> codes = new LinkedHashSet<>();
    for (Change change : patch.changes()) {
      if (change == null || change.resourceId() == null || change.resourceCode() == null
          || change.revision() == null || change.granted() == null || !ids.add(change.resourceId())
          || !codes.add(change.resourceCode())) throw badRequest("资源变更缺少标识、版本或存在重复项");
    }
    if (ids.isEmpty()) return;
    Map<String, Row> current = new java.util.HashMap<>();
    List<String> requested = List.copyOf(ids);
    for (int start = 0; start < requested.size(); start += QUERY_BATCH) {
      List<TenantResource> found = em.createQuery("select r from TenantResource r"
          + " where r.tenantId = :tenant and r.enabled = true and r.id in :ids", TenantResource.class)
          .setParameter("tenant", tenant)
          .setParameter("ids", requested.subList(start, Math.min(start + QUERY_BATCH, requested.size())))
          .getResultList();
      rowsCustom(found, tenant, roleId).forEach(row -> current.put(row.id(), row));
    }
    Set<String> added = new LinkedHashSet<>();
    Set<String> removed = new LinkedHashSet<>();
    for (Change change : patch.changes()) {
      Row row = current.get(change.resourceId());
      if (row == null || !row.grantable() || !Objects.equals(row.code(), change.resourceCode())
          || !Objects.equals(row.revision(), change.revision())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "资源或授权已发生变化，请重新载入后确认");
      }
      if (row.granted() != change.granted()) (change.granted() ? added : removed).add(row.code());
    }
    applyChanges(tenant, roleId, added, removed);
  }

  private record CustomSubtree(List<TenantResource> resources, Set<String> grantedCodes,
      Set<String> grantableCodes, String revision) {}

  private CustomSubtree subtreeCustom(String tenant, String roleId, String rootId) {
    TenantResource root = em.find(TenantResource.class, new TenantResourceKey(tenant, rootId));
    if (root == null || !root.isEnabled()) throw new AccessDeniedException("资源目录不存在或无权访问");
    List<TenantResource> resources = new ArrayList<>();
    resources.add(root);
    Set<String> seen = new java.util.HashSet<>();
    seen.add(rootId);
    List<String> parents = List.of(rootId);
    while (!parents.isEmpty()) {
      List<String> next = new ArrayList<>();
      for (int start = 0; start < parents.size(); start += QUERY_BATCH) {
        List<TenantResource> children = em.createQuery("select r from TenantResource r"
            + " where r.tenantId = :tenant and r.enabled = true and r.parentId in :parents",
            TenantResource.class).setParameter("tenant", tenant)
            .setParameter("parents", parents.subList(start, Math.min(start + QUERY_BATCH, parents.size())))
            .setMaxResults(MAX_SUBTREE + 1).getResultList();
        for (TenantResource item : children) {
          if (!seen.add(item.getId())) continue;
          resources.add(item);
          next.add(item.getId());
          if (resources.size() > MAX_SUBTREE) throw badRequest("目录内可见资源超过 5000 项");
        }
      }
      parents = next;
    }
    resources.sort(java.util.Comparator.comparing(TenantResource::getId));
    Set<String> granted = new LinkedHashSet<>();
    StringBuilder revision = new StringBuilder();
    for (TenantResource item : resources) {
      revision.append(item.getId()).append('|').append(item.getCode()).append('|')
          .append(item.getParentId()).append('|').append(item.getUpdatedAt()).append(';');
    }
    for (int start = 0; start < resources.size(); start += QUERY_BATCH) {
      List<String> codes = resources.subList(start, Math.min(start + QUERY_BATCH, resources.size()))
          .stream().map(TenantResource::getCode).toList();
      for (RoleResourceGrant grant : em.createQuery("select g from RoleResourceGrant g"
          + " where g.tenantId = :tenant and g.roleId = :role and g.deletedAt is null"
          + " and g.resourceCode in :codes order by g.resourceCode, g.id", RoleResourceGrant.class)
          .setParameter("tenant", tenant).setParameter("role", roleId)
          .setParameter("codes", codes).getResultList()) {
        granted.add(grant.getResourceCode());
        revision.append(grant.getResourceCode()).append(':').append(grant.getId())
            .append(':').append(grant.getUpdatedAt()).append(';');
      }
    }
    Set<String> grantableCodes = tenantResourceCatalog.grantableCodes(tenant,
        resources.stream().map(TenantResource::getCode).toList());
    return new CustomSubtree(resources, granted, grantableCodes, digest(revision.toString()));
  }

  private BulkPreview previewCustom(String rootId, boolean granted, CustomSubtree subtree) {
    int total = 0;
    int changed = 0;
    for (TenantResource entry : subtree.resources()) {
      if (!subtree.grantableCodes().contains(entry.getCode())) continue;
      total++;
      if (subtree.grantedCodes().contains(entry.getCode()) != granted) changed++;
    }
    return new BulkPreview(rootId, granted, total, changed, subtree.revision());
  }

  private void saveSubtreeCustom(String tenant, String roleId, BulkCommand command) {
    if (repository.lockForAuthorization(tenant, roleId).isEmpty()) {
      throw new AccessDeniedException("角色不存在或不属于当前租户");
    }
    CustomSubtree subtree = subtreeCustom(tenant, roleId, command.resourceId());
    if (!Objects.equals(subtree.revision(), command.revision())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "目录资源或授权已发生变化，请重新预览");
    }
    Set<String> added = new LinkedHashSet<>();
    Set<String> removed = new LinkedHashSet<>();
    for (TenantResource entry : subtree.resources()) {
      if (!subtree.grantableCodes().contains(entry.getCode())) continue;
      if (command.granted() && !subtree.grantedCodes().contains(entry.getCode())) added.add(entry.getCode());
      if (!command.granted() && subtree.grantedCodes().contains(entry.getCode())) removed.add(entry.getCode());
    }
    applyChanges(tenant, roleId, added, removed);
  }

  private BulkPreview preview(String resourceId, boolean granted, Subtree subtree) {
    int total = 0;
    int changed = 0;
    for (Resource resource : subtree.resources()) {
      if (!Boolean.TRUE.equals(resource.getGrantable())) continue;
      total++;
      if (subtree.grantedCodes().contains(resource.getCode()) != granted) changed++;
    }
    return new BulkPreview(resourceId, granted, total, changed, subtree.revision());
  }

  private Subtree subtree(String tenant, String roleId, String rootId) {
    requireReadable(Resource.class, "parentId");
    var cb = em.getCriteriaBuilder();
    var rootQuery = cb.createQuery(Resource.class);
    var root = rootQuery.from(Resource.class);
    rootQuery.select(root).where(visible(cb, rootQuery, root, tenant),
        cb.equal(root.get("id"), rootId));
    List<Resource> resources = new ArrayList<>(em.createQuery(rootQuery).getResultList());
    if (resources.isEmpty()) {
      throw new AccessDeniedException("资源目录不存在或无权访问");
    }
    Set<String> seen = new java.util.HashSet<>();
    seen.add(rootId);
    List<String> parents = List.of(rootId);
    while (!parents.isEmpty()) {
      List<String> next = new ArrayList<>();
      for (int start = 0; start < parents.size(); start += QUERY_BATCH) {
        var query = cb.createQuery(Resource.class);
        var child = query.from(Resource.class);
        query.select(child).where(visible(cb, query, child, tenant),
            child.get("parentId").in(parents.subList(start,
                Math.min(start + QUERY_BATCH, parents.size()))));
        for (Resource item : em.createQuery(query).setMaxResults(MAX_SUBTREE + 1).getResultList()) {
          if (!seen.add(item.getId())) continue;
          resources.add(item);
          next.add(item.getId());
          if (resources.size() > MAX_SUBTREE) {
            throw badRequest("目录内可见资源超过 5000 项，请缩小到下级目录后再操作");
          }
        }
      }
      parents = next;
    }
    resources.sort(java.util.Comparator.comparing(Resource::getId));
    Set<String> grantedCodes = new LinkedHashSet<>();
    StringBuilder revision = new StringBuilder();
    for (Resource item : resources) {
      revision.append(item.getId()).append('|').append(item.getCode()).append('|')
          .append(item.getParentId()).append('|').append(item.getUpdatedAt()).append(';');
    }
    for (int start = 0; start < resources.size(); start += QUERY_BATCH) {
      List<String> codes = resources.subList(start, Math.min(start + QUERY_BATCH, resources.size()))
          .stream().map(Resource::getCode).toList();
      List<RoleResourceGrant> grants = em.createQuery("select g from RoleResourceGrant g"
          + " where g.tenantId = :tenant and g.roleId = :role and g.deletedAt is null"
          + " and g.resourceCode in :codes order by g.resourceCode, g.id", RoleResourceGrant.class)
          .setParameter("tenant", tenant).setParameter("role", roleId)
          .setParameter("codes", codes).getResultList();
      for (RoleResourceGrant grant : grants) {
        grantedCodes.add(grant.getResourceCode());
        revision.append(grant.getResourceCode()).append(':').append(grant.getId())
            .append(':').append(grant.getUpdatedAt()).append(';');
      }
    }
    return new Subtree(resources, grantedCodes, digest(revision.toString()));
  }

  private void applyChanges(String tenant, String roleId, Set<String> added, Set<String> removed) {
    if (!removed.isEmpty() && !hasRolePolicy(tenant, roleId)) {
      List<String> codes = List.copyOf(removed);
      for (int start = 0; start < codes.size(); start += QUERY_BATCH) {
        Long legacy = em.createQuery("select count(g) from RoleResourceGrant g"
            + " where g.tenantId = :tenant and g.roleId = :role and g.deletedAt is null"
            + " and g.resourceCode in :codes and (g.dataScopeId is not null or g.fieldScopeId is not null)", Long.class)
            .setParameter("tenant", tenant).setParameter("role", roleId)
            .setParameter("codes", codes.subList(start, Math.min(start + QUERY_BATCH, codes.size())))
            .getSingleResult();
        if (legacy > 0) {
          throw new ResponseStatusException(HttpStatus.CONFLICT,
              "待撤销的旧授权仍承载数据或字段策略，需先迁移旧策略；本次未保存任何变更");
        }
      }
    }
    List<String> additions = List.copyOf(added);
    for (int start = 0; start < additions.size(); start += QUERY_BATCH) {
      RoleResourceGrantDto dto = new RoleResourceGrantDto();
      dto.setRoleId(roleId);
      dto.setResourceCodes(new LinkedHashSet<>(additions.subList(start,
          Math.min(start + QUERY_BATCH, additions.size()))));
      roles.authorize(dto);
    }
    List<String> removals = List.copyOf(removed);
    for (int start = 0; start < removals.size(); start += QUERY_BATCH) {
      roles.unauthorized(roleId, new LinkedHashSet<>(removals.subList(start,
          Math.min(start + QUERY_BATCH, removals.size()))));
    }
  }

  private boolean hasRolePolicy(String tenant, String roleId) {
    return em.createQuery("select count(b) from RoleScopeBinding b where b.tenantId = :tenant"
        + " and b.roleId = :role and b.deletedAt is null", Long.class)
        .setParameter("tenant", tenant).setParameter("role", roleId).getSingleResult() > 0;
  }

  private String requireRole(String roleId) {
    final String tenant = roles.requireManagementTenant();
    requireReadable(Role.class, "id");
    for (String field : List.of("id", "code", "grantable")) {
      requireReadable(Resource.class, field);
    }
    for (String field : List.of("roleId", "resourceCode")) {
      requireReadable(RoleResourceGrant.class, field);
    }
    Long count = em.createQuery("select count(r) from Role r where r.tenantId = :tenant"
        + " and r.id = :id and r.deletedAt is null", Long.class)
        .setParameter("tenant", tenant).setParameter("id", roleId).getSingleResult();
    if (count != 1) {
      throw new AccessDeniedException("角色不存在或不属于当前租户");
    }
    return tenant;
  }

  private Predicate filters(CriteriaBuilder cb, CommonAbstractCriteria query, Root<Resource> r,
      String tenant, String roleId, String keyword, String state, ResourceType type,
      String parentId, boolean rootsOnly, boolean navigationOnly) {
    List<Predicate> predicates = new ArrayList<>();
    predicates.add(visible(cb, query, r, tenant));
    if (!keyword.isEmpty()) {
      String pattern = "%" + keyword.replace("!", "!!").replace("%", "!%")
          .replace("_", "!_").toLowerCase(java.util.Locale.ROOT) + "%";
      List<Predicate> matches = new ArrayList<>();
      for (String field : List.of("code", "alias", "label", "name", "title", "path")) {
        if (QueryCapabilities.readable(Resource.class, field)) {
          matches.add(cb.like(cb.lower(r.get(field)), pattern, '!'));
        }
      }
      predicates.add(cb.or(matches.toArray(Predicate[]::new)));
    }
    if (type != null) {
      predicates.add(cb.equal(r.get("type"), type));
    }
    if (navigationOnly) {
      predicates.add(navigationType(cb, r));
    }
    if (parentId != null) {
      predicates.add(cb.equal(r.get("parentId"), parentId));
    } else if (rootsOnly) {
      predicates.add(cb.isNull(r.get("parentId")));
    }
    if (!"all".equals(state)) {
      var sub = query.subquery(Integer.class);
      var grant = sub.from(RoleResourceGrant.class);
      sub.select(cb.literal(1)).where(cb.equal(grant.get("tenantId"), tenant),
          cb.equal(grant.get("roleId"), roleId), cb.equal(grant.get("resourceCode"), r.get("code")),
          cb.isNull(grant.get("deletedAt")));
      predicates.add("granted".equals(state) ? cb.exists(sub) : cb.not(cb.exists(sub)));
    }
    return cb.and(predicates.toArray(Predicate[]::new));
  }

  private Predicate visible(CriteriaBuilder cb, CommonAbstractCriteria query, Root<Resource> r,
      String tenant) {
    var context = AuthorizationContextHolder.getContext();
    List<Predicate> scopes = new ArrayList<>();
    ResourceScopeType[] values = ResourceScopeType.values();
    for (int bits = 1; bits < (1 << values.length); bits++) {
      Set<ResourceScopeType> set = EnumSet.noneOf(ResourceScopeType.class);
      for (int i = 0; i < values.length; i++) {
        if ((bits & (1 << i)) != 0) {
          set.add(values[i]);
        }
      }
      if (ResourceScopePolicy.isAccessible(set, context)) {
        scopes.add(cb.equal(r.get("scopeTypes"), set));
      }
    }
    if (ResourceScopePolicy.isAccessible(null, context)) {
      scopes.add(cb.isNull(r.get("scopeTypes")));
    }
    Predicate subscription = cb.conjunction();
    if (!AuthorizationScopeGuards.isPlatformAdministrator(context)) {
      var sub = query.subquery(Integer.class);
      var tp = sub.from(TenantPackageRelevance.class);
      var pa = sub.from(PackageApplicationRelevance.class);
      var ar = sub.from(ApplicationResourceRelevance.class);
      sub.select(cb.literal(1)).where(cb.equal(tp.get("tenantId"), tenant),
          cb.equal(tp.get("packageCode"), pa.get("packageCode")),
          cb.equal(pa.get("applicationCode"), ar.get("applicationCode")),
          cb.equal(ar.get("resourceCode"), r.get("code")),
          cb.isNull(tp.get("deletedAt")), cb.isNull(pa.get("deletedAt")),
          cb.isNull(ar.get("deletedAt")));
      subscription = cb.or(cb.isTrue(r.get("publicAccess")), cb.exists(sub));
    }
    return cb.and(cb.isNull(r.get("deletedAt")),
        cb.isFalse(r.get("disabled")),
        cb.or(scopes.toArray(Predicate[]::new)), subscription);
  }

  private static Predicate navigationType(CriteriaBuilder cb, Root<Resource> resource) {
    return cb.or(cb.isNull(resource.get("type")),
        cb.not(resource.get("type").in(ResourceType.ACTION, ResourceType.API)));
  }

  private List<Row> rows(List<Resource> resources, String tenant, String roleId) {
    if (resources.isEmpty()) {
      return List.of();
    }
    Map<String, List<RoleResourceGrant>> grants = em.createQuery(
        "select g from RoleResourceGrant g where g.tenantId = :tenant and g.roleId = :role"
            + " and g.deletedAt is null and g.resourceCode in :codes", RoleResourceGrant.class)
        .setParameter("tenant", tenant).setParameter("role", roleId)
        .setParameter("codes", resources.stream().map(Resource::getCode).toList())
        .getResultList().stream().collect(Collectors.groupingBy(RoleResourceGrant::getResourceCode));
    Set<String> parents = new java.util.HashSet<>();
    Set<String> navigationParents = new java.util.HashSet<>();
    if (QueryCapabilities.readable(Resource.class, "parentId")) {
      var cb = em.getCriteriaBuilder();
      var childQuery = cb.createTupleQuery();
      var child = childQuery.from(Resource.class);
      childQuery.multiselect(child.get("parentId"), child.get("type")).distinct(true).where(
          child.get("parentId").in(resources.stream().map(Resource::getId).toList()),
          visible(cb, childQuery, child, tenant));
      for (Tuple tuple : em.createQuery(childQuery).getResultList()) {
        String id = tuple.get(0, String.class);
        ResourceType childType = tuple.get(1, ResourceType.class);
        parents.add(id);
        if (childType != ResourceType.ACTION && childType != ResourceType.API) {
          navigationParents.add(id);
        }
      }
    }
    Set<String> parentIds = parents;
    Set<String> navigationParentIds = navigationParents;
    return resources.stream().map(resource -> {
      List<RoleResourceGrant> assigned = grants.getOrDefault(resource.getCode(), List.of());
      String label = java.util.stream.Stream.of(resource.getAlias(), resource.getName(),
          resource.getLabel(), resource.getTitle(), resource.getCode())
          .filter(value -> value != null && !value.isBlank()).findFirst().orElse(resource.getId());
      return new Row(resource.getId(), resource.getCode(), label,
          resource.getType() == null ? null : resource.getType().name(), resource.getPath(),
          resource.getParentId(), resource.getIcon(), parentIds.contains(resource.getId()),
          navigationParentIds.contains(resource.getId()),
          resource.getDescription(), Boolean.TRUE.equals(resource.getGrantable()),
          !assigned.isEmpty(), revision(resource, assigned), new PolicySlot(false, null),
          new PolicySlot(false, null));
    }).toList();
  }

  private static String revision(Resource resource, List<RoleResourceGrant> grants) {
    String value = resource.getId() + "|" + resource.getCode() + "|" + resource.getUpdatedAt()
        + "|" + grants.stream().map(grant -> grant.getId() + ":" + grant.getUpdatedAt())
            .sorted().collect(Collectors.joining("|"));
    return digest(value);
  }

  private static String digest(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
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
