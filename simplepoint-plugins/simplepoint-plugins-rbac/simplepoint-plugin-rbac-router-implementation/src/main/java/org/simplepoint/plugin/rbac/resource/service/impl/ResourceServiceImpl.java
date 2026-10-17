package org.simplepoint.plugin.rbac.resource.service.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceAncestorRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.resource.api.vo.MicroModuleItemVo;
import org.simplepoint.plugin.rbac.resource.service.support.ButtonResourceMetadataRegistry;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.service.BuiltInTenantProvisioner;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceAncestor;
import org.simplepoint.security.entity.ResourceNode;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.security.pojo.dto.ServiceResourceRouteResult;
import org.simplepoint.security.service.ResourceService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default resource service implementation.
 */
@Slf4j
@Service
@RemoteProvider
public class ResourceServiceImpl
    extends BaseServiceImpl<ResourceRepository, Resource, String>
    implements ResourceService {

  private final ResourceAncestorRepository resourceAncestorRepository;
  private final MicroAppService microAppService;
  private final ObjectProvider<TenantPackageRelevanceRepository> tenantPackageRelevanceRepositoryProvider;
  private final ObjectProvider<TenantRepository> tenantRepositoryProvider;
  private final ObjectProvider<RoleResourceGrantRepository> roleResourceGrantRepositoryProvider;
  private final ObjectProvider<ResourceAuthorizationVersionService> resourceAuthorizationVersionServiceProvider;
  private final ObjectProvider<ButtonResourceMetadataRegistry> buttonResourceMetadataRegistryProvider;
  private final ObjectProvider<BuiltInTenantProvisioner> builtInTenantProvisionerProvider;
  private TenantResourceCatalog tenantResourceCatalog;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setTenantResourceCatalog(TenantResourceCatalog tenantResourceCatalog) {
    this.tenantResourceCatalog = tenantResourceCatalog;
  }

  private volatile ServiceResourceRouteResult adminRoutesCache;
  private volatile long adminRoutesCacheExpiry;

  /**
   * Creates the resource service.
   */
  public ResourceServiceImpl(
      ResourceRepository repository,
      DetailsProviderService detailsProviderService,
      ResourceAncestorRepository resourceAncestorRepository,
      MicroAppService microAppService,
      ObjectProvider<TenantPackageRelevanceRepository> tenantPackageRelevanceRepositoryProvider,
      ObjectProvider<TenantRepository> tenantRepositoryProvider,
      ObjectProvider<RoleResourceGrantRepository> roleResourceGrantRepositoryProvider,
      ObjectProvider<ResourceAuthorizationVersionService> resourceAuthorizationVersionServiceProvider,
      ObjectProvider<ButtonResourceMetadataRegistry> buttonResourceMetadataRegistryProvider,
      ObjectProvider<BuiltInTenantProvisioner> builtInTenantProvisionerProvider
  ) {
    super(repository, detailsProviderService);
    this.resourceAncestorRepository = resourceAncestorRepository;
    this.microAppService = microAppService;
    this.tenantPackageRelevanceRepositoryProvider = tenantPackageRelevanceRepositoryProvider;
    this.tenantRepositoryProvider = tenantRepositoryProvider;
    this.roleResourceGrantRepositoryProvider = roleResourceGrantRepositoryProvider;
    this.resourceAuthorizationVersionServiceProvider = resourceAuthorizationVersionServiceProvider;
    this.buttonResourceMetadataRegistryProvider = buttonResourceMetadataRegistryProvider;
    this.builtInTenantProvisionerProvider = builtInTenantProvisionerProvider;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public <S extends Resource> S create(S entity) {
    normalize(entity);
    validateScopeBoundary(entity);
    S saved = super.create(entity);
    adminRoutesCache = null;
    saveAncestors(saved, false);
    return saved;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public <S extends Resource> Resource modifyById(S entity) {
    normalize(entity);
    validateScopeBoundary(entity);
    Resource current = findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("资源不存在"));
    String oldCode = current.getCode();
    Resource updated = (Resource) super.modifyById(entity);
    adminRoutesCache = null;
    saveAncestors(updated, true);
    migrateResourceCode(oldCode, updated.getCode());
    return updated;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    adminRoutesCache = null;
    Set<String> deleteIds = new HashSet<>(ids);
    deleteIds.addAll(resourceAncestorRepository.findChildIdsByAncestorIds(ids));
    Set<String> deletedCodes = findAllByIds(deleteIds).stream()
        .map(Resource::getCode)
        .filter(this::hasText)
        .collect(Collectors.toSet());
    var grantRepository = roleResourceGrantRepositoryProvider.getIfAvailable();
    if (grantRepository != null && !deletedCodes.isEmpty()) {
      grantRepository.deleteAllByResourceCodes(deletedCodes);
    }
    super.removeByIds(deleteIds);
    var refreshService = resourceAuthorizationVersionServiceProvider.getIfAvailable();
    if (refreshService != null) {
      refreshService.refreshByResourceCodes(deletedCodes);
    }
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void sync(String owner, Set<ResourceDeclaration> declarations) {
    if (declarations == null || declarations.isEmpty()) {
      return;
    }
    log.info("Resource sync started for owner: {}", owner);
    adminRoutesCache = null;
    Map<String, Resource> resourcesByCode = new HashMap<>();
    Map<String, Resource> resourcesByPath = new HashMap<>();
    getRepository().loadAll().forEach(resource -> indexResource(resourcesByCode, resourcesByPath, resource));
    Set<String> synchronizedCodes = new HashSet<>();

    ArrayDeque<DeclarationEntry> queue = new ArrayDeque<>();
    declarations.forEach(declaration -> queue.add(new DeclarationEntry(declaration, Set.of())));
    while (!queue.isEmpty()) {
      DeclarationEntry entry = queue.poll();
      ResourceDeclaration declaration = entry.declaration();
      Resource resource = declaration.toResource();
      Set<ResourceScopeType> declaredScopes = declaration.getScopeTypes();
      Set<ResourceScopeType> effectiveScopes = declaredScopes == null || declaredScopes.isEmpty()
          ? (entry.parentScopes().isEmpty()
              ? ResourceScopePolicy.effectiveScopes(Set.of())
              : entry.parentScopes())
          : ResourceScopePolicy.effectiveScopes(declaredScopes);
      if (!entry.parentScopes().isEmpty()
          && !ResourceScopePolicy.isValidChild(entry.parentScopes(), effectiveScopes)) {
        throw new IllegalArgumentException(
            "Resource scope exceeds parent boundary: " + declaration.getCode()
        );
      }
      resource.setScopeTypes(effectiveScopes);
      resource.setPluginId(firstNonBlank(resource.getPluginId(), owner));
      normalize(resource);
      synchronizedCodes.add(resource.getCode());
      Resource existing = resolveExisting(resourcesByCode, resourcesByPath, resource);
      if (existing != null) {
        final String oldCode = existing.getCode();
        final String oldPath = existing.getPath();
        resource.setId(existing.getId());
        // Shared resource manifests are also published by services without this page's controller.
        // An absent contract must not erase the owning service's registered enforcement metadata.
        if (resource.getPermissionMetadata() == null) resource.setPermissionMetadata(existing.getPermissionMetadata());
        resource = getRepository().updateById(resource);
        saveAncestors(resource, true);
        migrateResourceCode(oldCode, resource.getCode());
        if (!Objects.equals(oldCode, resource.getCode())) {
          resourcesByCode.remove(oldCode);
        }
        if (!Objects.equals(oldPath, resource.getPath())) {
          resourcesByPath.remove(oldPath);
        }
      } else {
        resource = create(resource);
      }
      indexResource(resourcesByCode, resourcesByPath, resource);

      if (declaration.getChildren() != null && !declaration.getChildren().isEmpty()) {
        for (ResourceDeclaration child : declaration.getChildren()) {
          child.setParentId(resource.getId());
          queue.offer(new DeclarationEntry(child, effectiveScopes));
        }
      }
    }
    retireUndeclaredResources(owner, synchronizedCodes, resourcesByCode.values());
    provisionBuiltInTenantResources(owner, synchronizedCodes);
    log.info("Resource sync completed for owner: {}", owner);
  }

  private void provisionBuiltInTenantResources(String owner, Set<String> synchronizedCodes) {
    if (builtInTenantProvisionerProvider == null || synchronizedCodes == null || synchronizedCodes.isEmpty()) {
      return;
    }
    BuiltInTenantProvisioner provisioner = builtInTenantProvisionerProvider.getIfAvailable();
    if (provisioner == null) {
      return;
    }
    List<Resource> synchronizedResources = getRepository().findAllByCodes(synchronizedCodes).stream()
        .filter(resource -> Boolean.TRUE.equals(resource.getGrantable()))
        .filter(resource -> Boolean.FALSE.equals(resource.getDisabled()))
        .toList();
    Set<String> tenantCodes = resourceCodesForScope(synchronizedResources, ResourceScopeType.TENANT);
    Set<String> personalCodes = resourceCodesForScope(synchronizedResources, ResourceScopeType.PERSONAL);
    provisioner.provisionApplicationResources(owner, tenantCodes, personalCodes);
  }

  private Set<String> resourceCodesForScope(
      Collection<Resource> resources,
      ResourceScopeType scopeType
  ) {
    return resources.stream()
        .filter(resource -> ResourceScopePolicy.effectiveScopes(resource.getScopeTypes()).contains(scopeType))
        .map(Resource::getCode)
        .filter(this::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @Override
  public ServiceResourceRouteResult routes() {
    AuthorizationContext context = getAuthorizationContext();
    if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
      throw new org.springframework.security.access.AccessDeniedException("授权上下文不可用");
    }
    String activeTenantId = context.getAttribute("X-Tenant-Id");
    if (tenantResourceCatalog != null && context.getScopeType() == AuthorizationScopeType.TENANT
        && hasText(activeTenantId)
        && tenantResourceCatalog.mode(activeTenantId) == TenantResourceMode.CUSTOM) {
      return customRoutes(context, activeTenantId);
    }
    if (AuthorizationScopeGuards.isPlatformAdministrator(context)) {
      boolean cacheable = resourceLabelsAreCacheable();
      ServiceResourceRouteResult cached = adminRoutesCache;
      if (cacheable && cached != null && System.currentTimeMillis() < adminRoutesCacheExpiry) {
        return withScopeContext(cached, context);
      }
      Set<ServiceResourceRouteResult.ServiceEntry> services = new HashSet<>();
      ServiceResourceRouteResult result = ServiceResourceRouteResult.of(
          services,
          buildResourceTree(
              directoryQuery().navigation(Set.of(), true),
              services,
              true,
              loadRemoteEntriesByServiceName()
          )
      );
      if (cacheable) {
        adminRoutesCache = result;
        adminRoutesCacheExpiry = System.currentTimeMillis() + 30_000;
      }
      return withScopeContext(result, context);
    }

    Set<String> resourceCodes = normalizeCodes(context.getResources());
    Set<String> managedCodes = tenantResourceCatalog == null || !hasText(activeTenantId)
        ? Set.of()
        : tenantResourceCatalog.managedGrantableCodes(activeTenantId, resourceCodes);
    resourceCodes.addAll(findPublicAccessCodes());
    resourceCodes.retainAll(filterAccessibleCodes(
        resourceCodes,
        context.getScopeType(),
        Boolean.TRUE.equals(context.getIsAdministrator())
    ));
    resourceCodes.addAll(managedCodes);
    if (resourceCodes.isEmpty()) {
      return withScopeContext(ServiceResourceRouteResult.EMPTY, context);
    }
    List<Resource> resources = new ArrayList<>(directoryQuery().navigation(resourceCodes, false));
    mergeManagedRoutes(resources, activeTenantId, resourceCodes);
    resources = resources.stream().filter(this::isRouteResource).toList();
    Set<ServiceResourceRouteResult.ServiceEntry> services = new HashSet<>();
    return withScopeContext(ServiceResourceRouteResult.of(
        services,
        buildResourceTree(resources, services, true, loadRemoteEntriesByServiceName())
    ), context);
  }

  private ServiceResourceRouteResult customRoutes(AuthorizationContext context, String tenantId) {
    Set<String> held = normalizeCodes(context.getResources());
    Map<String, TenantResource> catalog = new java.util.LinkedHashMap<>();
    for (TenantResource item : tenantResourceCatalog.navigationResources(tenantId, held)) {
      catalog.put(item.getId(), item);
    }
    for (TenantResource item : tenantResourceCatalog.managedNavigationResources(tenantId, held)) {
      catalog.put(item.getId(), item);
    }
    List<Resource> resources = new ArrayList<>();
    for (TenantResource item : catalog.values()) {
      Resource resource = tenantRoute(item);
      if (isRouteResource(resource)) resources.add(resource);
    }
    Set<ServiceResourceRouteResult.ServiceEntry> services = new HashSet<>();
    return withScopeContext(ServiceResourceRouteResult.of(services,
        buildResourceTree(resources, services, true, loadRemoteEntriesByServiceName(), TenantResource.class)), context);
  }

  private void mergeManagedRoutes(
      final List<Resource> resources,
      final String tenantId,
      final Set<String> heldCodes
  ) {
    if (tenantResourceCatalog == null || !hasText(tenantId) || heldCodes.isEmpty()) {
      return;
    }
    List<TenantResource> managed = tenantResourceCatalog.managedNavigationResources(
        tenantId,
        heldCodes
    );
    if (managed.isEmpty()) return;
    Set<String> parentIds = managed.stream()
        .map(TenantResource::getParentId)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Map<String, Resource> merged = new java.util.LinkedHashMap<>();
    resources.forEach(resource -> merged.put(resource.getId(), resource));
    directoryQuery().navigationAncestors(parentIds)
        .forEach(resource -> merged.putIfAbsent(resource.getId(), resource));
    managed.stream().map(ResourceServiceImpl::tenantRoute)
        .forEach(resource -> merged.put(resource.getId(), resource));
    resources.clear();
    resources.addAll(merged.values());
  }

  private static Resource tenantRoute(final TenantResource item) {
    Resource resource = new Resource();
    resource.setId(item.getId());
    resource.setCode(item.getCode());
    resource.setName(item.getName());
    resource.setType(item.getType());
    resource.setPath(item.getPath());
    resource.setComponent(item.getComponent());
    resource.setRouteKind(item.getRouteKind());
    resource.setParentId(item.getParentId());
    resource.setIcon(item.getIcon());
    resource.setSort(item.getSort());
    resource.setDescription(item.getDescription());
    resource.setGrantable(item.isGrantable());
    resource.setDisabled(false);
    resource.setPublicAccess(false);
    resource.setRequireOrgTenant(true);
    resource.setScopeTypes(Set.of(ResourceScopeType.TENANT));
    return resource;
  }

  @Override
  public Page<ResourceNode> limitTree(Map<String, String> attributes, Pageable pageable) {
    var result = directoryQuery().management(attributes, pageable);
    Page<Resource> roots = result.roots();
    List<String> rootIds = roots.map(Resource::getId).stream().toList();
    List<Resource> treeResources = new ArrayList<>(result.descendants());
    treeResources.addAll(roots.getContent());
    List<ResourceNode> tree = buildResourceTree(treeResources, new HashSet<>(), false, Map.of());
    // Navigation trees use natural menu order; this paginated management list must retain SQL order.
    Map<String, Integer> rootOrder = new HashMap<>();
    for (int index = 0; index < rootIds.size(); index++) {
      rootOrder.put(rootIds.get(index), index);
    }
    // The navigation builder promotes orphans. A paginated management tree must expose only its counted roots.
    tree.removeIf(node -> !rootOrder.containsKey(node.getId()));
    tree.sort(Comparator.comparingInt(node -> rootOrder.getOrDefault(node.getId(), Integer.MAX_VALUE)));
    return new PageImpl<>(
        tree,
        pageable,
        roots.getTotalElements()
    );
  }

  @Override
  public Page<ResourceNode> children(Map<String, String> attributes, Pageable pageable) {
    if (pageable.getPageSize() > 100) throw new org.simplepoint.core.query.InvalidQueryException("目录分页大小不能超过100");
    String parentId = firstNonBlank(attribute(attributes, "parentId"), attribute(attributes, "parent"));
    String keyword = firstNonBlank(attribute(attributes, "keyword"), attribute(attributes, "search"));
    var result = directoryQuery().children(parentId, keyword, pageable);
    Page<Resource> page = result.page();
    List<ResourceNode> nodes = page.getContent().stream().map(this::toResourceNode).toList();
    nodes.forEach(node -> node.setHasChildren(result.parentsWithChildren().contains(node.getId())));
    return new PageImpl<>(nodes, pageable, page.getTotalElements());
  }

  private ResourceDirectoryQuery directoryQuery() {
    var context = getAuthorizationContext();
    if (context == null || context.getUserId() == null || context.getUserId().isBlank()) throw new org.springframework.security.access.AccessDeniedException("授权上下文不可用");
    if (permissionEntityManager == null) throw new IllegalStateException("资源目录查询服务不可用");
    String tenant = currentTenantId();
    if (!hasText(tenant)) {
      var tenants = tenantRepositoryProvider.getIfAvailable();
      if (tenants != null) tenant = tenants.findPersonalTenantByOwnerId(context.getUserId()).map(org.simplepoint.plugin.rbac.tenant.api.entity.Tenant::getId).orElse(null);
    }
    return new ResourceDirectoryQuery(permissionEntityManager, context, tenant);
  }

  @Override
  public Page<ResourceNode> assignedTree(Collection<String> codes, Map<String, String> attributes, Pageable pageable) {
    if (pageable.getPageSize() > 100) throw new org.simplepoint.core.query.InvalidQueryException("目录分页大小不能超过100");
    String parentId = firstNonBlank(attribute(attributes, "parentId"), attribute(attributes, "parent"));
    var result = directoryQuery().assigned(normalizeCodes(codes), parentId, pageable);
    List<ResourceNode> nodes = result.page().getContent().stream().map(this::toResourceNode).toList();
    nodes.forEach(node -> {
      node.setHasChildren(result.parentsWithChildren().contains(node.getId()));
      node.setChecked(result.checkedIds().contains(node.getId()));
    });
    return new PageImpl<>(nodes, pageable, result.page().getTotalElements());
  }

  @Override
  public Collection<Resource> findAllByCodes(Collection<String> codes) {
    Set<String> normalizedCodes = normalizeCodes(codes);
    if (normalizedCodes.isEmpty()) {
      return List.of();
    }
    return directoryQuery().byCodes(normalizedCodes, false);
  }

  @Override
  public Collection<Resource> findDirectoryResourcesByCodes(Collection<String> codes) {
    Set<String> normalizedCodes = normalizeCodes(codes);
    if (normalizedCodes.isEmpty()) return List.of();
    var access = org.simplepoint.core.jackson.FieldPermissionPolicy.access(Resource.class, "code");
    if ("MASKED".equals(access) || org.simplepoint.core.jackson.FieldPermissionPolicy.hidden(access)) {
      throw new org.springframework.security.access.AccessDeniedException("资源编码当前不可读取");
    }
    return directoryQuery().byCodes(normalizedCodes, true);
  }

  @Override
  public Collection<Resource> findAllAccessible() {
    AuthorizationContext context = getAuthorizationContext();
    if (context == null) {
      return List.of();
    }
    return directoryQuery().allAccessible();
  }

  @Override
  public Collection<String> filterAccessibleCodes(
      Collection<String> codes,
      AuthorizationScopeType scopeType,
      boolean systemAdministrator
  ) {
    Set<String> normalizedCodes = normalizeCodes(codes);
    if (normalizedCodes.isEmpty() || scopeType == null) {
      return List.of();
    }
    return runtimeQuery().filter(normalizedCodes, scopeType, systemAdministrator, false);
  }

  @Override
  public Collection<String> filterGrantableAccessibleCodes(
      Collection<String> codes,
      AuthorizationScopeType scopeType
  ) {
    Set<String> normalizedCodes = normalizeCodes(codes);
    return normalizedCodes.isEmpty() || scopeType == null ? List.of() : runtimeQuery().filter(normalizedCodes, scopeType, false, true);
  }

  @Override
  public Collection<String> findAllAccessibleCodes(
      AuthorizationScopeType scopeType,
      boolean systemAdministrator
  ) {
    if (scopeType == null) {
      return List.of();
    }
    return runtimeQuery().all(scopeType, systemAdministrator);
  }

  @Override
  public Collection<String> findSubtreeGrantableCodes(String rootId) {
    if (!hasText(rootId)) {
      return List.of();
    }
    return directoryQuery().subtreeCodes(rootId);
  }

  @Override
  public Collection<String> findAllRequireOrgTenantCodes() {
    return runtimeQuery().flagged("requireOrgTenant");
  }

  @Override
  public Collection<String> findPublicAccessCodes() {
    return runtimeQuery().flagged("publicAccess");
  }

  private ResourceRuntimeQuery runtimeQuery() {
    if (permissionEntityManager == null) throw new IllegalStateException("资源鉴权查询服务不可用");
    return new ResourceRuntimeQuery(permissionEntityManager);
  }

  private void saveAncestors(Resource saved, boolean clearExisting) {
    if (!hasText(saved.getId())) {
      return;
    }
    if (clearExisting) {
      resourceAncestorRepository.deleteChild(Set.of(saved.getId()));
    }
    if (!hasText(saved.getParentId())) {
      return;
    }
    Collection<String> ancestors = resourceAncestorRepository.findAncestorIdsByChildIdIn(Set.of(saved.getParentId()));
    List<ResourceAncestor> relations = new ArrayList<>();
    for (String ancestorId : ancestors) {
      ResourceAncestor relation = new ResourceAncestor();
      relation.setChildId(saved.getId());
      relation.setAncestorId(ancestorId);
      relations.add(relation);
    }
    ResourceAncestor direct = new ResourceAncestor();
    direct.setChildId(saved.getId());
    direct.setAncestorId(saved.getParentId());
    relations.add(direct);
    resourceAncestorRepository.saveAll(relations);
  }

  @jakarta.persistence.PersistenceContext
  private jakarta.persistence.EntityManager permissionEntityManager;

  private void migrateResourceCode(String oldCode, String newCode) {
    if (!hasText(oldCode) || !hasText(newCode) || Objects.equals(oldCode, newCode)) {
      return;
    }
    var grantRepository = roleResourceGrantRepositoryProvider.getIfAvailable();
    if (grantRepository != null) {
      grantRepository.updateResourceCode(oldCode, newCode);
    }
    if (permissionEntityManager != null) {
      permissionEntityManager.createQuery("update DataScope d set d.resourceCode=:newCode where d.resourceCode=:oldCode")
          .setParameter("newCode", newCode).setParameter("oldCode", oldCode).executeUpdate();
    }
    var refreshService = resourceAuthorizationVersionServiceProvider.getIfAvailable();
    if (refreshService != null) {
      refreshService.refreshByResourceCodes(Set.of(oldCode, newCode));
    }
  }

  private void retireUndeclaredResources(
      String owner,
      Set<String> synchronizedCodes,
      Collection<Resource> indexedResources
  ) {
    if (!hasText(owner)) {
      return;
    }
    Set<String> retiredIds = indexedResources.stream()
        .filter(resource -> owner.equals(resource.getPluginId()))
        .filter(resource -> hasText(resource.getId()))
        .filter(resource -> !synchronizedCodes.contains(resource.getCode()))
        .map(Resource::getId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (retiredIds.isEmpty()) {
      return;
    }
    removeByIds(retiredIds);
    log.info("Retired {} undeclared resources for owner: {}", retiredIds.size(), owner);
  }

  private void validateScopeBoundary(Resource resource) {
    if (resource == null || !hasText(resource.getParentId())) {
      return;
    }
    if (Objects.equals(resource.getId(), resource.getParentId())) {
      throw new IllegalArgumentException("资源不能将自身设置为父资源");
    }
    Resource parent = findById(resource.getParentId())
        .orElseThrow(() -> new IllegalArgumentException("父资源不存在"));
    if (!ResourceScopePolicy.isValidChild(parent.getScopeTypes(), resource.getScopeTypes())) {
      throw new IllegalArgumentException("子资源的资源级别不能超出父资源边界");
    }
  }

  private List<ResourceNode> buildResourceTree(
      Collection<Resource> resources,
      Set<ServiceResourceRouteResult.ServiceEntry> services,
      boolean clearRuntimeFields,
      Map<String, String> remoteEntries
  ) {
    return buildResourceTree(resources, services, clearRuntimeFields, remoteEntries, Resource.class);
  }

  private List<ResourceNode> buildResourceTree(
      Collection<Resource> resources, Set<ServiceResourceRouteResult.ServiceEntry> services,
      boolean clearRuntimeFields, Map<String, String> remoteEntries, Class<?> sourceType
  ) {
    Map<String, String> readableLabels = new HashMap<>();
    for (Resource resource : resources) readableLabels.put(resource.getId(), readableResourceLabel(resource, sourceType));
    String sortAccess = org.simplepoint.core.jackson.FieldPermissionPolicy.access(sourceType, "sort");
    boolean readableSort = !"MASKED".equals(sortAccess) && !org.simplepoint.core.jackson.FieldPermissionPolicy.hidden(sortAccess);
    List<Resource> sorted = resources.stream().sorted(Comparator
        .comparing((Resource resource) -> readableSort ? resource.getSort() : null, Comparator.nullsLast(Integer::compareTo))
        .thenComparing(resource -> readableLabels.get(resource.getId()), Comparator.nullsLast(String::compareTo))).toList();
    Map<String, ResourceNode> nodeMap = new HashMap<>();
    for (Resource resource : sorted) {
      addServiceEntry(services, resource.getComponent(), remoteEntries);
      ResourceNode node = sourceType == TenantResource.class ? new org.simplepoint.plugin.rbac.tenant.api.vo.TenantResourceNode() : new ResourceNode();
      BeanUtils.copyProperties(resource, node);
      node.setLabel(readableLabels.get(resource.getId()));
      nodeMap.put(resource.getId(), node);
    }
    List<ResourceNode> roots = new ArrayList<>();
    for (Resource resource : sorted) {
      ResourceNode current = nodeMap.get(resource.getId());
      ResourceNode parent = hasText(resource.getParentId()) ? nodeMap.get(resource.getParentId()) : null;
      if (parent == null) {
        roots.add(current);
      } else {
        current.setParentScopeTypes(ResourceScopePolicy.effectiveScopes(parent.getScopeTypes()));
        parent.getChildren().add(current);
      }
    }
    if (clearRuntimeFields) {
      roots.forEach(this::clearRouteFields);
    }
    return roots;
  }

  private ResourceNode toResourceNode(Resource resource) {
    ResourceNode node = new ResourceNode();
    BeanUtils.copyProperties(resource, node);
    node.setLabel(readableResourceLabel(resource));
    node.setChildren(List.of());
    node.setHasChildren(Boolean.FALSE);
    return node;
  }

  private void clearRouteFields(ResourceNode node) {
    node.setCreatedBy(null);
    node.setCreatedAt(null);
    node.setUpdatedBy(null);
    node.setUpdatedAt(null);
    node.setParentId(null);
    node.setId(null);
    node.getChildren().forEach(this::clearRouteFields);
  }

  /** Resolve display fallbacks only after source-field projection; otherwise a hidden name reappears through label. */
  private String readableResourceLabel(Resource resource) {
    return readableResourceLabel(resource, Resource.class);
  }

  private String readableResourceLabel(Resource resource, Class<?> sourceType) {
    var source = new java.util.LinkedHashMap<String, Object>();
    source.put("label", resource.getLabel()); source.put("name", resource.getName());
    source.put("title", resource.getTitle()); source.put("code", resource.getCode());
    var readable = org.simplepoint.core.jackson.FieldPermissionPolicy.project(sourceType, source);
    return firstNonBlank((String) readable.get("label"), (String) readable.get("name"), (String) readable.get("title"), (String) readable.get("code"));
  }

  /** Ordinary administrator navigation retains its cache; request-specific redacted labels never enter or reuse it. */
  private boolean resourceLabelsAreCacheable() {
    return java.util.stream.Stream.of("label", "name", "title", "code")
        .map(field -> org.simplepoint.core.jackson.FieldPermissionPolicy.access(Resource.class, field))
        .noneMatch(access -> "MASKED".equals(access) || org.simplepoint.core.jackson.FieldPermissionPolicy.hidden(access));
  }

  private ServiceResourceRouteResult withScopeContext(ServiceResourceRouteResult result, AuthorizationContext context) {
    if (context == null) {
      return result;
    }
    return result.withAuthorizationContext(Map.of(
        "scopeType", context.getScopeType() == null ? "" : context.getScopeType().name(),
        "actorRole", context.getActorRole() == null ? "" : context.getActorRole().name(),
        "tenantId", context.getAttribute("X-Tenant-Id") == null ? "" : context.getAttribute("X-Tenant-Id"),
        "userId", context.getUserId() == null ? "" : context.getUserId()
    ));
  }

  private Map<String, String> loadRemoteEntriesByServiceName() {
    if (microAppService == null) {
      return Map.of();
    }
    Set<MicroModuleItemVo> remotes = microAppService.loadApps();
    if (remotes == null || remotes.isEmpty()) {
      return Map.of();
    }
    Map<String, String> entries = new HashMap<>();
    for (MicroModuleItemVo remote : remotes) {
      if (hasText(remote.getName())) {
        entries.put(remote.getName(), remote.getEntry());
      }
    }
    return entries;
  }

  private void addServiceEntry(
      Set<ServiceResourceRouteResult.ServiceEntry> services,
      String component,
      Map<String, String> remoteEntries
  ) {
    String serviceName = getServiceName(component);
    if (!hasText(serviceName) || isNonRemoteComponent(component)) {
      return;
    }
    services.add(ServiceResourceRouteResult.ServiceEntry.of(serviceName, remoteEntries.get(serviceName)));
  }

  private String getServiceName(String component) {
    if (!hasText(component)) {
      return null;
    }
    int start = component.charAt(0) == '/' ? 1 : 0;
    int end = component.indexOf('/', start);
    return end == -1 ? component.substring(start) : component.substring(start, end);
  }

  private boolean isNonRemoteComponent(String component) {
    return component != null && (component.startsWith("external:") || component.startsWith("iframe:"));
  }

  private Resource resolveExisting(
      Map<String, Resource> resourcesByCode,
      Map<String, Resource> resourcesByPath,
      Resource resource
  ) {
    if (hasText(resource.getCode()) && resourcesByCode.containsKey(resource.getCode())) {
      return resourcesByCode.get(resource.getCode());
    }
    if (hasText(resource.getPath())) {
      return resourcesByPath.get(resource.getPath());
    }
    return null;
  }

  private void indexResource(Map<String, Resource> resourcesByCode, Map<String, Resource> resourcesByPath, Resource resource) {
    if (resource == null) {
      return;
    }
    if (hasText(resource.getCode())) {
      resourcesByCode.put(resource.getCode(), resource);
    }
    if (hasText(resource.getPath())) {
      resourcesByPath.put(resource.getPath(), resource);
    }
  }

  private void normalize(Resource resource) {
    if (!hasText(resource.getCode()) && hasText(resource.getPath())) {
      resource.setCode(resource.getPath().toLowerCase().replace("/", "."));
    }
    if (!hasText(resource.getName())) {
      resource.setName(firstNonBlank(resource.getLabel(), resource.getTitle(), resource.getCode()));
    }
    if (!hasText(resource.getLabel())) {
      resource.setLabel(firstNonBlank(resource.getName(), resource.getTitle(), resource.getCode()));
    }
    if (resource.getType() == null) {
      resource.setType(hasText(resource.getPath()) ? ResourceType.PAGE : ResourceType.ACTION);
    }
    Set<ResourceScopeType> effectiveScopes = ResourceScopePolicy.effectiveScopes(resource.getScopeTypes());
    resource.setScopeTypes(effectiveScopes);
    if (!hasText(resource.getRouteKind()) && isRouteType(resource.getType())) {
      if (resource.getType() == ResourceType.GROUP) {
        resource.setRouteKind("group");
      } else if (resource.getType() == ResourceType.MODULE) {
        resource.setRouteKind("submenu");
      } else {
        resource.setRouteKind("item");
      }
    }
    if (resource.getPublicAccess() == null) {
      resource.setPublicAccess(Boolean.FALSE);
    }
    resource.setRequireOrgTenant(effectiveScopes.contains(ResourceScopeType.TENANT)
        && !effectiveScopes.contains(ResourceScopeType.PERSONAL));
    if (resource.getGrantable() == null) {
      resource.setGrantable(resource.getType() != ResourceType.GROUP && resource.getType() != ResourceType.MODULE);
    }
    if (effectiveScopes.size() == 1 && effectiveScopes.contains(ResourceScopeType.SYSTEM)) {
      resource.setGrantable(Boolean.FALSE);
    }
    if (resource.getDisabled() == null) {
      resource.setDisabled(Boolean.FALSE);
    }
    applyButtonMetadata(resource);
  }

  private void applyButtonMetadata(Resource resource) {
    if (resource == null || resource.getType() != ResourceType.ACTION || !hasText(resource.getCode())) {
      return;
    }
    ButtonResourceMetadataRegistry registry = buttonResourceMetadataRegistryProvider.getIfAvailable();
    if (registry == null) {
      return;
    }
    ButtonResourceMetadataRegistry.ButtonResourceMetadata metadata = registry.find(resource.getCode());
    if (metadata == null) {
      return;
    }
    if (!hasText(resource.getAlias()) && hasText(metadata.title())) {
      resource.setAlias(metadata.title());
    }
    if (!hasText(resource.getTitle()) && hasText(metadata.title())) {
      resource.setTitle(metadata.title());
    }
    if (!hasText(resource.getIcon()) && hasText(metadata.icon())) {
      resource.setIcon(metadata.icon());
    }
    if (resource.getDanger() == null) {
      resource.setDanger(metadata.danger());
    }
    if (resource.getSort() == null) {
      resource.setSort(metadata.sort());
    }
  }

  private boolean isAccessible(Resource resource, AuthorizationContext context) {
    return resource != null && ResourceScopePolicy.isAccessible(resource.getScopeTypes(), context);
  }

  private record DeclarationEntry(
      ResourceDeclaration declaration,
      Set<ResourceScopeType> parentScopes
  ) {
  }

  private String attribute(Map<String, String> attributes, String key) {
    if (attributes == null || key == null) {
      return null;
    }
    return attributes.get(key);
  }

  private boolean isRouteResource(Resource resource) {
    return resource != null
        && Boolean.FALSE.equals(resource.getDisabled())
        && (hasText(resource.getPath())
            || resource.getType() == ResourceType.GROUP
            || resource.getType() == ResourceType.MODULE);
  }

  private boolean isRouteType(ResourceType type) {
    return type == ResourceType.GROUP || type == ResourceType.MODULE || type == ResourceType.PAGE;
  }

  private Set<String> normalizeCodes(Collection<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return new LinkedHashSet<>();
    }
    return codes.stream()
        .filter(this::hasText)
        .map(String::trim)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
