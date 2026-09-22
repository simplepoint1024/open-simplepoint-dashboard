package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Transactional tenant catalog. Platform resource IDs remain stable in each tenant namespace. */
@Service
@Transactional(readOnly = true)
public class TenantResourceCatalogImpl implements TenantResourceCatalog {
  private final EntityManager em;
  private final TenantRepository tenants;
  private final TenantPackageRelevanceRepository packages;
  private final ResourceRepository platform;

  public TenantResourceCatalogImpl(EntityManager em, TenantRepository tenants,
      TenantPackageRelevanceRepository packages, ResourceRepository platform) {
    this.em = em;
    this.tenants = tenants;
    this.packages = packages;
    this.platform = platform;
  }

  @Override
  public TenantResourceMode mode(String tenantId) {
    return tenants.findById(tenantId).map(Tenant::getResourceMode)
        .filter(Objects::nonNull).orElse(TenantResourceMode.PLATFORM);
  }

  @Override
  @Transactional
  public TenantResourceMode selectMode(TenantResourceMode target) {
    if (target == null) throw badRequest("请选择资源加载类型");
    Tenant tenant = managerTenant();
    em.lock(tenant, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    em.refresh(tenant);
    if (tenant.getResourceMode() == target) return target;
    if (target == TenantResourceMode.CUSTOM) {
      sync(tenant, false); // A failed first import leaves PLATFORM effective.
    }
    tenant.setResourceMode(target);
    em.flush();
    tenants.increaseAuthorizationVersion(List.of(tenant.getId()));
    return target;
  }

  @Override
  @Transactional
  public SyncResult synchronize(boolean overwrite) {
    Tenant tenant = lockedCustomManagerTenant();
    return sync(tenant, overwrite);
  }

  @Override
  public SyncPreview previewSynchronization(boolean overwrite) {
    Tenant tenant = customManagerTenant();
    Desired desired = desired(tenant.getId());
    List<TenantResource> current = entries(tenant.getId());
    Map<String, TenantResource> imported = new HashMap<>();
    for (TenantResource entry : current) {
      if (entry.isPlatformSource()) imported.put(entry.getPlatformResourceId(), entry);
    }
    List<SyncItem> additions = desired.resources().values().stream()
        .filter(source -> !imported.containsKey(source.getId())).limit(100)
        .map(source -> new SyncItem(source.getId(), source.getCode(), source.getName())).toList();
    int added = (int) desired.resources().keySet().stream().filter(id -> !imported.containsKey(id)).count();
    int refreshed = desired.resources().size() - added;
    List<SyncItem> deactivations = new ArrayList<>();
    int deactivated = 0;
    Set<String> platformPaths = new HashSet<>();
    desired.resources().values().stream().map(Resource::getPath).filter(Objects::nonNull)
        .forEach(platformPaths::add);
    for (TenantResource entry : current) {
      boolean lostPlatform = entry.isPlatformSource()
          && !desired.resources().containsKey(entry.getPlatformResourceId());
      boolean lostTarget = !entry.isPlatformSource() && entry.getTargetResourceId() != null
          && !eligiblePageTarget(desired, entry.getTargetResourceId());
      boolean pathConflict = !entry.isPlatformSource() && entry.getPath() != null
          && platformPaths.contains(entry.getPath());
      if (entry.isEnabled() && (lostPlatform || lostTarget || pathConflict)) {
        deactivated++;
        if (deactivations.size() < 100) {
          deactivations.add(new SyncItem(entry.getId(), entry.getCode(), entry.getName()));
        }
      }
    }
    return new SyncPreview(added, refreshed, deactivated, additions, List.copyOf(deactivations), overwrite);
  }

  private static boolean eligiblePageTarget(Desired desired, String targetId) {
    Resource source = desired.resources().get(targetId);
    return source != null && source.getType() == ResourceType.PAGE
        && Boolean.TRUE.equals(source.getGrantable())
        && (desired.entitled().contains(source.getCode()) || Boolean.TRUE.equals(source.getPublicAccess()));
  }

  private SyncResult sync(Tenant tenant, boolean overwrite) {
    String tenantId = tenant.getId();
    Desired desired = desired(tenantId);
    Set<String> entitled = desired.entitled();
    Map<String, Resource> all = desired.resources();
    Map<String, TenantResource> existing = new HashMap<>();
    for (TenantResource entry : entries(tenantId)) {
      if (entry.isPlatformSource()) existing.put(entry.getPlatformResourceId(), entry);
    }
    int added = 0;
    int refreshed = 0;
    int deactivated = 0;
    for (Resource source : all.values()) {
      TenantResource entry = existing.remove(source.getId());
      boolean newEntry = entry == null;
      if (entry == null) {
        entry = new TenantResource();
        entry.setTenantId(tenantId);
        entry.setId(source.getId());
        entry.setPlatformResourceId(source.getId());
        added++;
      } else {
        refreshed++;
      }
      // Security fields always follow platform entitlement, even for locally edited labels.
      entry.setCode(source.getCode());
      entry.setType(source.getType());
      entry.setComponent(source.getComponent());
      entry.setRouteKind(source.getRouteKind());
      entry.setGrantable((entitled.contains(source.getCode()) || Boolean.TRUE.equals(source.getPublicAccess()))
          && Boolean.TRUE.equals(source.getGrantable()));
      if (overwrite) entry.setManualDisabled(false);
      entry.setEnabled(!entry.isManualDisabled());
      if (overwrite || !entry.isLocalModified() || entry.getName() == null) {
        entry.setName(source.getName());
        entry.setPath(source.getPath());
        entry.setIcon(source.getIcon());
        entry.setSort(source.getSort());
        entry.setParentId(source.getParentId());
        entry.setDescription(source.getDescription());
        entry.setLocalModified(false);
      }
      entry.setUpdatedAt(Instant.now());
      if (newEntry) em.persist(entry);
    }
    for (TenantResource revoked : existing.values()) {
      if (revoked.isEnabled()) deactivated++;
      revoked.setEnabled(false);
      revoked.setGrantable(false);
      revoked.setUpdatedAt(Instant.now());
    }
    Map<String, TenantResource> activePlatform = new HashMap<>();
    Set<String> activePlatformPaths = new HashSet<>();
    for (TenantResource entry : entries(tenantId)) {
      if (entry.isPlatformSource() && entry.isEnabled() && entry.isGrantable()) {
        activePlatform.put(entry.getId(), entry);
      }
      if (entry.isPlatformSource() && entry.isEnabled() && entry.getPath() != null) {
        activePlatformPaths.add(entry.getPath());
      }
    }
    for (TenantResource entry : entries(tenantId)) {
      if (entry.isPlatformSource() || entry.getTargetResourceId() == null) continue;
      boolean allowed = activePlatform.containsKey(entry.getTargetResourceId())
          && (entry.getPath() == null || !activePlatformPaths.contains(entry.getPath()));
      if (entry.isEnabled() && !allowed) deactivated++;
      entry.setEnabled(allowed && !entry.isManualDisabled());
      entry.setUpdatedAt(Instant.now());
    }
    em.flush();
    tenants.increaseAuthorizationVersion(List.of(tenantId));
    return new SyncResult(added, refreshed, deactivated);
  }

  private record Desired(Map<String, Resource> resources, Set<String> entitled) {}

  private Desired desired(String tenantId) {
    Set<String> entitled = new LinkedHashSet<>(packages.findResourceCodesByTenantId(tenantId));
    Map<String, Resource> all = new LinkedHashMap<>();
    Map<String, Resource> byId = new HashMap<>();
    for (Resource resource : platform.loadAll()) {
      if (resource.getDeletedAt() != null || Boolean.TRUE.equals(resource.getDisabled())) continue;
      byId.put(resource.getId(), resource);
      if (entitled.contains(resource.getCode()) || Boolean.TRUE.equals(resource.getPublicAccess())) {
        if (ResourceScopePolicy.effectiveScopes(resource.getScopeTypes()).contains(ResourceScopeType.TENANT)) {
          all.put(resource.getId(), resource);
        }
      }
    }
    // Navigation parents are structural only unless the package also grants them.
    for (Resource selected : List.copyOf(all.values())) {
      Resource cursor = selected;
      Set<String> seen = new HashSet<>();
      while (cursor.getParentId() != null && seen.add(cursor.getParentId())) {
        cursor = byId.get(cursor.getParentId());
        if (cursor == null) break;
        all.putIfAbsent(cursor.getId(), cursor);
      }
    }
    return new Desired(all, entitled);
  }

  @Override
  public Page<TenantResource> list(String keyword, String parentId, boolean rootsOnly, Pageable pageable) {
    Tenant tenant = managerTenant();
    if (pageable == null || pageable.isUnpaged() || pageable.getPageSize() > 100
        || pageable.getSort().isSorted()) throw badRequest("每页最多 100 条资源");
    String search = keyword == null ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT);
    if (rootsOnly && parentId != null) throw badRequest("根资源与上级资源筛选不能同时使用");
    if (search.length() > 128) throw badRequest("搜索词过长");
    if (pageable.getOffset() > Integer.MAX_VALUE) throw badRequest("分页范围过大");
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(TenantResource.class);
    var root = query.from(TenantResource.class);
    query.select(root).where(listFilter(cb, root, tenant.getId(), search, parentId, rootsOnly));
    query.orderBy(cb.asc(root.get("sort")), cb.asc(root.get("name")), cb.asc(root.get("id")));
    List<TenantResource> content = em.createQuery(query).setFirstResult((int) pageable.getOffset())
        .setMaxResults(pageable.getPageSize()).getResultList();
    var count = cb.createQuery(Long.class);
    var counted = count.from(TenantResource.class);
    count.select(cb.count(counted)).where(listFilter(cb, counted, tenant.getId(), search, parentId, rootsOnly));
    return new PageImpl<>(content, pageable, em.createQuery(count).getSingleResult());
  }

  @Override
  public TenantResource get(String id) {
    Tenant tenant = managerTenant();
    TenantResource resource = find(tenant.getId(), id);
    if (resource == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
    return resource;
  }

  private jakarta.persistence.criteria.Predicate listFilter(jakarta.persistence.criteria.CriteriaBuilder cb,
      jakarta.persistence.criteria.Root<TenantResource> root, String tenantId, String search,
      String parentId, boolean rootsOnly) {
    List<jakarta.persistence.criteria.Predicate> filters = new ArrayList<>();
    filters.add(cb.equal(root.get("tenantId"), tenantId));
    if (parentId != null) filters.add(cb.equal(root.get("parentId"), parentId));
    else if (rootsOnly) filters.add(cb.isNull(root.get("parentId")));
    if (!search.isEmpty()) {
      String pattern = "%" + search.replace("!", "!!").replace("%", "!%")
          .replace("_", "!_") + "%";
      filters.add(cb.or(cb.like(cb.lower(root.get("code")), pattern, '!'),
          cb.like(cb.lower(root.get("name")), pattern, '!')));
    }
    return cb.and(filters.toArray(jakarta.persistence.criteria.Predicate[]::new));
  }

  @Override
  @Transactional
  public TenantResource create(Mutation mutation) {
    Tenant tenant = lockedCustomManagerTenant();
    validate(mutation);
    if (mutation.type() == ResourceType.ACTION || mutation.type() == ResourceType.API
        || mutation.type() == ResourceType.FEATURE) {
      throw badRequest("租户自建资源只支持目录、模块和页面；接口与操作权限由平台授权");
    }
    TenantResource entry = new TenantResource();
    entry.setTenantId(tenant.getId());
    entry.setId(UUID.randomUUID().toString());
    entry.setCode("tenant." + tenant.getId() + "." + entry.getId());
    applyMutation(tenant.getId(), entry, mutation);
    entry.setGrantable(entry.getType() == ResourceType.PAGE);
    em.persist(entry);
    em.flush();
    tenants.increaseAuthorizationVersion(List.of(tenant.getId()));
    return entry;
  }

  @Override
  @Transactional
  public TenantResource update(String id, Mutation mutation) {
    Tenant tenant = lockedCustomManagerTenant();
    validate(mutation);
    TenantResource entry = find(tenant.getId(), id);
    if (entry == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
    if (entry.isPlatformSource() && mutation.type() != entry.getType()) {
      throw badRequest("平台来源资源的类型不能修改");
    }
    if (!entry.isPlatformSource() && (mutation.type() == ResourceType.ACTION
        || mutation.type() == ResourceType.API || mutation.type() == ResourceType.FEATURE)) {
      throw badRequest("租户自建资源只支持目录、模块和页面");
    }
    applyMutation(tenant.getId(), entry, mutation);
    entry.setLocalModified(true);
    entry.setUpdatedAt(Instant.now());
    em.flush();
    tenants.increaseAuthorizationVersion(List.of(tenant.getId()));
    return entry;
  }

  @Override
  @Transactional
  public void delete(String id) {
    Tenant tenant = lockedCustomManagerTenant();
    TenantResource entry = find(tenant.getId(), id);
    if (entry == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
    if (entry.isPlatformSource()) throw badRequest("平台来源资源不可删除，可停用或由平台撤销授权");
    if (entries(tenant.getId()).stream().anyMatch(child -> Objects.equals(id, child.getParentId()))) {
      throw badRequest("请先移动或删除下级资源");
    }
    em.createQuery("update RoleResourceGrant g set g.deletedAt = :deletedAt"
        + " where g.tenantId = :tenant and g.resourceCode = :code and g.deletedAt is null")
        .setParameter("deletedAt", Instant.now())
        .setParameter("tenant", tenant.getId()).setParameter("code", entry.getCode())
        .executeUpdate();
    em.remove(entry);
    em.flush();
    tenants.increaseAuthorizationVersion(List.of(tenant.getId()));
  }

  private void applyMutation(String tenantId, TenantResource entry, Mutation mutation) {
    String parentId = blankToNull(mutation.parentId());
    if (Objects.equals(parentId, entry.getId())) throw badRequest("资源不能作为自身的上级");
    TenantResource parentResource = parentId == null ? null : find(tenantId, parentId);
    if (parentId != null && (parentResource == null || !parentResource.isEnabled()
        || parentResource.getType() == ResourceType.ACTION || parentResource.getType() == ResourceType.API
        || (isNavigationType(mutation.type()) && !isNavigationType(parentResource.getType())))) {
      throw badRequest("上级资源不存在、已停用或不支持下级资源");
    }
    Set<String> visited = new HashSet<>();
    String cursor = parentId;
    while (cursor != null && visited.add(cursor)) {
      if (Objects.equals(cursor, entry.getId())) throw badRequest("资源层级不能形成循环");
      TenantResource parent = find(tenantId, cursor);
      cursor = parent == null ? null : parent.getParentId();
    }
    if (cursor != null) throw badRequest("上级资源层级存在循环");
    entry.setParentId(parentId);
    entry.setName(mutation.name().trim());
    entry.setType(mutation.type());
    entry.setIcon(blankToNull(mutation.icon()));
    entry.setSort(mutation.sort());
    entry.setDescription(blankToNull(mutation.description()));
    if (entry.isPlatformSource()) {
      // Platform route/component remain authoritative; only presentation can change.
      if (Boolean.TRUE.equals(mutation.enabled())
          && !desired(tenantId).resources().containsKey(entry.getId())) {
        throw badRequest("平台已撤销该资源，租户不能重新启用");
      }
      if (mutation.enabled() != null) {
        entry.setManualDisabled(!mutation.enabled());
        entry.setEnabled(mutation.enabled());
      }
      return;
    }
    if (entry.getType() == ResourceType.PAGE) {
      TenantResource target = find(tenantId, blankToNull(mutation.targetResourceId()));
      if (target == null || !target.isEnabled() || !target.isPlatformSource()
          || target.getType() != ResourceType.PAGE || !target.isGrantable()
          || "hidden".equalsIgnoreCase(target.getRouteKind())
          || !grantableCodes(tenantId, Set.of(target.getCode())).contains(target.getCode())) {
        throw badRequest("自建页面必须绑定当前租户已获授权的平台页面");
      }
      String path = blankToNull(mutation.path());
      if (path == null || path.length() > 200 || !path.matches("^/(?!/)[A-Za-z0-9/_-]+$")) {
        throw badRequest("页面路径必须以 / 开头，只能包含字母、数字、斜杠、下划线和连字符");
      }
      if (entries(tenantId).stream().anyMatch(other -> !other.getId().equals(entry.getId())
          && other.isEnabled() && path.equals(other.getPath()))) throw badRequest("页面路径已存在");
      entry.setTargetResourceId(target.getId());
      entry.setPath(path);
      entry.setComponent(target.getComponent());
      entry.setRouteKind(target.getRouteKind());
      entry.setGrantable(true);
    } else {
      entry.setTargetResourceId(null);
      entry.setPath(null);
      entry.setComponent(null);
      entry.setRouteKind(null);
      entry.setGrantable(false);
    }
    if (mutation.enabled() != null) {
      entry.setManualDisabled(!mutation.enabled());
      entry.setEnabled(mutation.enabled());
    }
  }

  private static void validate(Mutation mutation) {
    if (mutation == null || mutation.name() == null || mutation.name().isBlank()
        || mutation.name().length() > 100 || mutation.type() == null
        || mutation.icon() != null && mutation.icon().length() > 100
        || mutation.description() != null && mutation.description().length() > 255) {
      throw badRequest("资源名称、类型或长度无效");
    }
  }

  private static boolean isNavigationType(ResourceType type) {
    return type == ResourceType.GROUP || type == ResourceType.MODULE || type == ResourceType.PAGE;
  }

  @Override
  public List<TenantResource> activeResources(String tenantId) {
    Map<String, TenantResource> active = new HashMap<>();
    em.createQuery("select r from TenantResource r where r.tenantId = :tenant and r.enabled = true",
        TenantResource.class).setParameter("tenant", tenantId).getResultList()
        .forEach(entry -> active.put(entry.getId(), entry));
    return active.values().stream().filter(entry -> {
      Set<String> visited = new HashSet<>();
      TenantResource cursor = entry;
      while (cursor.getParentId() != null) {
        if (!visited.add(cursor.getId())) return false;
        cursor = active.get(cursor.getParentId());
        if (cursor == null) return false;
      }
      return true;
    }).toList();
  }

  @Override
  public Set<String> grantableCodes(String tenantId, Collection<String> requested) {
    if (requested == null || requested.isEmpty()) return Set.of();
    Set<String> codes = new HashSet<>(requested);
    List<TenantResource> active = activeResources(tenantId);
    Map<String, TenantResource> byId = new HashMap<>();
    active.forEach(entry -> byId.put(entry.getId(), entry));
    Set<String> livePlatformCodes = livePlatformCodes(tenantId, active.stream()
        .filter(TenantResource::isPlatformSource).map(TenantResource::getCode).toList());
    Set<String> result = new LinkedHashSet<>();
    for (TenantResource entry : active) {
      if (!entry.isGrantable() || !codes.contains(entry.getCode())) continue;
      if (entry.isPlatformSource() && livePlatformCodes.contains(entry.getCode())) {
        result.add(entry.getCode());
      } else if (!entry.isPlatformSource() && entry.getTargetResourceId() != null) {
        TenantResource target = byId.get(entry.getTargetResourceId());
        if (target != null && target.isGrantable() && livePlatformCodes.contains(target.getCode())) {
          result.add(entry.getCode());
        }
      }
    }
    return result;
  }

  @Override
  public Set<String> effectivePlatformCodes(String tenantId, Collection<String> requested) {
    if (requested == null || requested.isEmpty()) return Set.of();
    Set<String> codes = new HashSet<>(requested);
    Map<String, TenantResource> byId = new HashMap<>();
    for (TenantResource entry : activeResources(tenantId)) byId.put(entry.getId(), entry);
    Set<String> livePlatformCodes = livePlatformCodes(tenantId, byId.values().stream()
        .filter(TenantResource::isPlatformSource).map(TenantResource::getCode).toList());
    Set<String> result = new LinkedHashSet<>();
    for (TenantResource entry : byId.values()) {
      if (!entry.isGrantable() || !codes.contains(entry.getCode())) continue;
      if (entry.isPlatformSource() && livePlatformCodes.contains(entry.getCode())) result.add(entry.getCode());
      else if (entry.getTargetResourceId() != null) {
        TenantResource target = byId.get(entry.getTargetResourceId());
        if (target != null && target.isGrantable() && target.isPlatformSource()
            && livePlatformCodes.contains(target.getCode())) result.add(target.getCode());
      }
    }
    return result;
  }

  private Set<String> livePlatformCodes(String tenantId, Collection<String> candidateCodes) {
    if (candidateCodes == null || candidateCodes.isEmpty()) return Set.of();
    Set<String> entitled = packages.findResourceCodesByTenantId(tenantId);
    Set<String> live = new HashSet<>();
    Collection<Resource> current = platform.findAllByCodes(candidateCodes);
    if (current == null) return live;
    for (Resource resource : current) {
      if (resource.getDeletedAt() == null && !Boolean.TRUE.equals(resource.getDisabled())
          && ResourceScopePolicy.effectiveScopes(resource.getScopeTypes()).contains(ResourceScopeType.TENANT)
          && (entitled.contains(resource.getCode()) || Boolean.TRUE.equals(resource.getPublicAccess()))) {
        live.add(resource.getCode());
      }
    }
    return live;
  }

  private List<TenantResource> entries(String tenantId) {
    return em.createQuery("select r from TenantResource r where r.tenantId = :tenant", TenantResource.class)
        .setParameter("tenant", tenantId).getResultList();
  }

  private TenantResource find(String tenantId, String id) {
    return id == null ? null : em.find(TenantResource.class, new TenantResourceKey(tenantId, id));
  }

  private Tenant managerTenant() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    String tenantId = context == null ? null : context.getAttribute("X-Tenant-Id");
    if (tenantId == null || tenantId.isBlank()) throw new AccessDeniedException("请切换到组织工作空间");
    Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new AccessDeniedException("租户不存在"));
    if (tenant.getTenantType() != TenantType.ORGANIZATION) {
      throw new AccessDeniedException("仅组织租户支持自定义资源");
    }
    AuthorizationScopeGuards.requireOrganizationTenantManager(context, tenant.getOwnerId());
    return tenant;
  }

  private Tenant customManagerTenant() {
    Tenant tenant = managerTenant();
    if (tenant.getResourceMode() != TenantResourceMode.CUSTOM) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "自定义资源未开启");
    }
    return tenant;
  }

  private Tenant lockedCustomManagerTenant() {
    Tenant tenant = managerTenant();
    em.lock(tenant, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    em.refresh(tenant);
    if (tenant.getResourceMode() != TenantResourceMode.CUSTOM) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "自定义资源未开启");
    }
    return tenant;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
