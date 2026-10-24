package org.simplepoint.plugin.rbac.resource.service.impl;

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
import org.simplepoint.core.query.TablePagination;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceAncestorRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.resource.api.vo.MicroModuleItemVo;
import org.simplepoint.plugin.rbac.resource.service.support.ButtonResourceMetadataRegistry;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceCatalogWriteBoundary;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationDeployments;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.service.BuiltInTenantProvisioner;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
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

/** Default resource service implementation. */
@Slf4j
@Service
public class ResourceServiceImpl extends BaseServiceImpl<ResourceRepository, Resource, String>
    implements ResourceService {

  private final ResourceAncestorRepository resourceAncestorRepository;
  private final MicroAppService microAppService;
  private final org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects
      catalogEffects;
  private final ObjectProvider<ButtonResourceMetadataRegistry>
      buttonResourceMetadataRegistryProvider;
  private final ObjectProvider<BuiltInTenantProvisioner> builtInTenantProvisionerProvider;
  private TenantResourceCatalog tenantResourceCatalog;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setTenantResourceCatalog(TenantResourceCatalog tenantResourceCatalog) {
    this.tenantResourceCatalog = tenantResourceCatalog;
  }

  private volatile ServiceResourceRouteResult adminRoutesCache;
  private volatile long adminRoutesCacheExpiry;

  private final jakarta.persistence.EntityManager permissionEntityManager;
  private final ResourceCatalogWriteBoundary catalogWrites;
  private final ResourceRegistrationDeployments deployments;

  /** Creates the resource service. */
  public ResourceServiceImpl(
      ResourceRepository repository,
      DetailsProviderService detailsProviderService,
      ResourceAncestorRepository resourceAncestorRepository,
      MicroAppService microAppService,
      ObjectProvider<ButtonResourceMetadataRegistry> buttonResourceMetadataRegistryProvider,
      ObjectProvider<BuiltInTenantProvisioner> builtInTenantProvisionerProvider,
      final jakarta.persistence.EntityManager permissionEntityManager,
      ResourceCatalogWriteBoundary catalogWrites,
      ResourceRegistrationDeployments deployments,
      org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects
          catalogEffects) {
    super(repository, detailsProviderService);
    this.resourceAncestorRepository = resourceAncestorRepository;
    this.microAppService = microAppService;
    this.catalogEffects = Objects.requireNonNull(catalogEffects);
    this.buttonResourceMetadataRegistryProvider = buttonResourceMetadataRegistryProvider;
    this.builtInTenantProvisionerProvider = builtInTenantProvisionerProvider;
    this.permissionEntityManager = permissionEntityManager;
    this.catalogWrites = Objects.requireNonNull(catalogWrites);
    this.deployments = Objects.requireNonNull(deployments);
  }

  @Override
  public Map<String, Object> schema() {
    PlatformResourceBoundary.read(getAuthorizationContext());
    return super.schema();
  }

  @Override
  public java.util.Optional<Resource> findById(String id) {
    PlatformResourceBoundary.read(getAuthorizationContext());
    return super.findById(id);
  }

  @Override
  public List<Resource> findAllByIds(Iterable<String> ids) {
    PlatformResourceBoundary.read(getAuthorizationContext());
    return super.findAllByIds(ids);
  }

  @Override
  public List<Resource> findAll(Map<String, String> attributes) {
    PlatformResourceBoundary.read(getAuthorizationContext());
    return super.findAll(attributes);
  }

  @Override
  public <S extends Resource> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    PlatformResourceBoundary.read(getAuthorizationContext());
    return super.limit(attributes, pageable);
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public <S extends Resource> S create(S entity) {
    PlatformResourceBoundary.write(getAuthorizationContext(), "resources.create");
    return createDeclared(entity);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public List<Resource> create(Collection<Resource> entities) {
    PlatformResourceBoundary.write(getAuthorizationContext(), "resources.create");
    catalogWrites.enter();
    return entities.stream().map(this::createDeclared).toList();
  }

  private <S extends Resource> S createDeclared(S entity) {
    catalogWrites.enter();
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
    PlatformResourceBoundary.write(getAuthorizationContext(), "resources.edit");
    catalogWrites.enter();
    normalize(entity);
    validateScopeBoundary(entity);
    Resource current =
        findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("资源不存在"));
    String oldCode = current.getCode();
    Resource updated = (Resource) super.modifyById(entity);
    adminRoutesCache = null;
    saveAncestors(updated, true);
    if (!Objects.equals(oldCode, updated.getCode())) {
      catalogEffects.renamePlatformCode(oldCode, updated.getCode());
    }
    catalogEffects.synchronize(Set.of(updated.getId()));
    return updated;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    PlatformResourceBoundary.write(getAuthorizationContext(), "resources.delete");
    removeCatalogResources(ids);
  }

  private void removeCatalogResources(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    catalogWrites.enter();
    adminRoutesCache = null;
    Set<String> deleteIds = new HashSet<>(ids);
    deleteIds.addAll(resourceAncestorRepository.findChildIdsByAncestorIds(ids));
    Set<String> deletedCodes =
        super.findAllByIds(deleteIds).stream()
            .map(Resource::getCode)
            .filter(this::hasText)
            .collect(Collectors.toSet());
    resourceAncestorRepository.deleteChild(deleteIds);
    super.removeByIds(deleteIds);
    catalogEffects.retire(deleteIds, deletedCodes);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void sync(String owner, Set<ResourceDeclaration> declarations) {
    if (declarations == null || declarations.isEmpty()) {
      return;
    }
    catalogWrites.enter();
    deployments.requireUnmanaged(owner);
    applySnapshot(owner, declarations);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void syncRegistered(
      String owner, Set<ResourceDeclaration> declarations, String verifiedClient) {
    catalogWrites.enter();
    deployments.requireRegistration(owner, verifiedClient);
    if (declarations != null && !declarations.isEmpty()) {
      applySnapshot(owner, declarations);
    }
  }

  private void applySnapshot(String owner, Set<ResourceDeclaration> declarations) {
    ResourceRegistrationPlan plan =
        ResourceRegistrationPlan.prepare(
            owner, declarations, getRepository().loadAll(), this::normalize);
    log.info("Resource sync started for owner: {}", owner);
    adminRoutesCache = null;
    Map<String, Resource> savedByCode = new HashMap<>();
    var parentChanges =
        new java.util.LinkedHashMap<
            String,
            org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects
                .ParentChange>();
    for (ResourceRegistrationPlan.Entry entry : plan.entries()) {
      Resource resource = entry.resource();
      resource.setParentId(
          entry.externalParentId() != null
              ? entry.externalParentId()
              : entry.parentCode() == null ? null : savedByCode.get(entry.parentCode()).getId());
      if (entry.existing() != null) {
        String oldCode = entry.existing().getCode();
        if (!Objects.equals(entry.existing().getParentId(), resource.getParentId())) {
          parentChanges.put(
              entry.existing().getId(),
              new org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects
                  .ParentChange(entry.existing().getParentId(), resource.getParentId()));
        }
        resource = getRepository().updateById(resource);
        saveAncestors(resource, true);
        if (!Objects.equals(oldCode, resource.getCode())) {
          catalogEffects.renamePlatformCode(oldCode, resource.getCode());
        }
      } else {
        resource = createDeclared(resource);
      }
      savedByCode.put(resource.getCode(), resource);
    }
    retireUndeclaredResources(owner, plan.retiredIds(), plan.codes());
    catalogEffects.synchronize(
        savedByCode.values().stream().map(Resource::getId).toList(), parentChanges);
    provisionBuiltInTenantResources(owner, plan.codes());
    log.info("Resource sync completed for owner: {}", owner);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void removeById(String id) {
    removeByIds(Set.of(id));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void removeAll() {
    PlatformResourceBoundary.write(getAuthorizationContext(), "resources.delete");
    catalogWrites.enter();
    removeByIds(getRepository().loadAll().stream().map(Resource::getId).toList());
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public void removeByOwner(String owner) {
    catalogWrites.enter();
    deployments.requireUnmanaged(owner);
    var plan =
        ResourceRegistrationPlan.prepare(
            owner, Set.of(), getRepository().loadAll(), this::normalize);
    retireUndeclaredResources(owner, plan.retiredIds(), Set.of());
  }

  private void provisionBuiltInTenantResources(String owner, Set<String> synchronizedCodes) {
    if (builtInTenantProvisionerProvider == null
        || synchronizedCodes == null
        || synchronizedCodes.isEmpty()) {
      return;
    }
    BuiltInTenantProvisioner provisioner = builtInTenantProvisionerProvider.getIfAvailable();
    if (provisioner == null) {
      return;
    }
    List<Resource> synchronizedResources =
        getRepository().findAllByCodes(synchronizedCodes).stream()
            // Application membership distributes capabilities, not just role-grantable actions.
            // Hidden runtime hosts and public endpoints still need explicit tenant entitlements.
            .filter(
                resource ->
                    resource.getType() != null
                        && resource.getType() != ResourceType.GROUP
                        && resource.getType() != ResourceType.MODULE)
            .filter(resource -> Boolean.FALSE.equals(resource.getDisabled()))
            .toList();
    Set<String> tenantCodes =
        resourceCodesForScope(synchronizedResources, ResourceScopeType.TENANT);
    Set<String> personalCodes =
        resourceCodesForScope(synchronizedResources, ResourceScopeType.PERSONAL);
    provisioner.provisionApplicationResources(owner, tenantCodes, personalCodes);
  }

  private Set<String> resourceCodesForScope(
      Collection<Resource> resources, ResourceScopeType scopeType) {
    return resources.stream()
        .filter(
            resource ->
                ResourceScopePolicy.effectiveScopes(resource.getScopeTypes()).contains(scopeType))
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
    if (context.getScopeType() == AuthorizationScopeType.TENANT
        || context.getScopeType() == AuthorizationScopeType.PERSONAL) {
      if (!hasText(activeTenantId) || tenantResourceCatalog == null) {
        throw new org.springframework.security.access.AccessDeniedException("租户资源目录不可用");
      }
      return tenantRoutes(context, activeTenantId);
    }
    if (context.getScopeType() != AuthorizationScopeType.PLATFORM || hasText(activeTenantId)) {
      throw new org.springframework.security.access.AccessDeniedException("工作空间范围不一致");
    }
    if (AuthorizationScopeGuards.isPlatformAdministrator(context)) {
      boolean cacheable = resourceLabelsAreCacheable();
      ServiceResourceRouteResult cached = adminRoutesCache;
      if (cacheable && cached != null && System.currentTimeMillis() < adminRoutesCacheExpiry) {
        return withScopeContext(cached, context);
      }
      Set<ServiceResourceRouteResult.ServiceEntry> services = new HashSet<>();
      ServiceResourceRouteResult result =
          ServiceResourceRouteResult.of(
              services,
              buildResourceTree(
                  directoryQuery().navigation(Set.of(), true),
                  services,
                  true,
                  loadRemoteEntriesByServiceName()));
      if (cacheable) {
        adminRoutesCache = result;
        adminRoutesCacheExpiry = System.currentTimeMillis() + 30_000;
      }
      return withScopeContext(result, context);
    }

    Set<String> resourceCodes = normalizeCodes(context.getResources());
    resourceCodes.addAll(findPublicAccessCodes());
    resourceCodes.retainAll(
        filterAccessibleCodes(
            resourceCodes,
            context.getScopeType(),
            Boolean.TRUE.equals(context.getIsAdministrator())));
    if (resourceCodes.isEmpty()) {
      return withScopeContext(ServiceResourceRouteResult.EMPTY, context);
    }
    List<Resource> resources = new ArrayList<>(directoryQuery().navigation(resourceCodes, false));
    resources = resources.stream().filter(this::isRouteResource).toList();
    Set<ServiceResourceRouteResult.ServiceEntry> services = new HashSet<>();
    return withScopeContext(
        ServiceResourceRouteResult.of(
            services,
            buildResourceTree(resources, services, true, loadRemoteEntriesByServiceName())),
        context);
  }

  private ServiceResourceRouteResult tenantRoutes(AuthorizationContext context, String tenantId) {
    Set<String> held = normalizeCodes(context.getResources());
    List<Resource> resources = new ArrayList<>();
    Map<String, org.simplepoint.api.locale.LocalizedText> localizedNames = new HashMap<>();
    ResourceScopeType scope =
        context.getScopeType() == AuthorizationScopeType.PERSONAL
            ? ResourceScopeType.PERSONAL
            : ResourceScopeType.TENANT;
    for (TenantResource item : tenantResourceCatalog.navigationResources(tenantId, held)) {
      if (item.getNameLocalization() != null)
        localizedNames.put(item.getId(), item.getNameLocalization());
      Resource resource = tenantRoute(item, scope);
      if (isRouteResource(resource)) {
        resources.add(resource);
      }
    }
    Set<ServiceResourceRouteResult.ServiceEntry> services = new HashSet<>();
    return withScopeContext(
        ServiceResourceRouteResult.of(
            services,
            buildResourceTree(
                resources,
                services,
                true,
                loadRemoteEntriesByServiceName(),
                TenantResource.class,
                localizedNames)),
        context);
  }

  private static Resource tenantRoute(final TenantResource item, ResourceScopeType scope) {
    Resource resource = new Resource();
    resource.setId(item.getId());
    resource.setCode(item.getCode());
    resource.setName(item.getName());
    resource.setAlias(item.getAlias());
    resource.setLabel(item.getLabel());
    resource.setTitle(item.getTitle());
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
    resource.setPublicAccess(item.isPublicAccess());
    resource.setRequireOrgTenant(scope == ResourceScopeType.TENANT);
    resource.setScopeTypes(Set.of(scope));
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
    // Navigation trees use natural menu order; this paginated management list must retain SQL
    // order.
    Map<String, Integer> rootOrder = new HashMap<>();
    for (int index = 0; index < rootIds.size(); index++) {
      rootOrder.put(rootIds.get(index), index);
    }
    // The navigation builder promotes orphans. A paginated management tree must expose only its
    // counted roots.
    tree.removeIf(node -> !rootOrder.containsKey(node.getId()));
    tree.sort(
        Comparator.comparingInt(node -> rootOrder.getOrDefault(node.getId(), Integer.MAX_VALUE)));
    return new PageImpl<>(tree, pageable, roots.getTotalElements());
  }

  @Override
  public Page<ResourceNode> children(Map<String, String> attributes, Pageable pageable) {
    if (pageable.getPageSize() > TablePagination.MAX_PAGE_SIZE) {
      throw new org.simplepoint.core.query.InvalidQueryException("目录分页大小不能超过500");
    }
    String parentId =
        firstNonBlank(attribute(attributes, "parentId"), attribute(attributes, "parent"));
    String keyword =
        firstNonBlank(attribute(attributes, "keyword"), attribute(attributes, "search"));
    boolean distribution = distributionRequested(attributes);
    var result = directoryQuery(distribution).children(parentId, keyword, pageable);
    Page<Resource> page = result.page();
    List<ResourceNode> nodes = page.getContent().stream().map(this::toResourceNode).toList();
    nodes.forEach(
        node -> {
          node.setHasChildren(result.parentsWithChildren().contains(node.getId()));
          if (distribution) {
            node.setDistributable(
                org.simplepoint.security.ResourceDistributionPolicy.accepts(node));
          }
        });
    return new PageImpl<>(nodes, pageable, page.getTotalElements());
  }

  private boolean distributionRequested(Map<String, String> attributes) {
    boolean distribution =
        org.simplepoint.security.ResourceDirectoryPurpose.parse(attribute(attributes, "purpose"))
            == org.simplepoint.security.ResourceDirectoryPurpose.DISTRIBUTION;
    if (distribution) {
      requireDistributionAdministrator();
    }
    return distribution;
  }

  private void requireDistributionAdministrator() {
    PlatformResourceBoundary.distribute(getAuthorizationContext());
  }

  private ResourceDirectoryQuery directoryQuery() {
    return directoryQuery(false);
  }

  private ResourceDirectoryQuery directoryQuery(boolean distribution) {
    var context = getAuthorizationContext();
    if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
      throw new org.springframework.security.access.AccessDeniedException("授权上下文不可用");
    }
    if (permissionEntityManager == null) {
      throw new IllegalStateException("资源目录查询服务不可用");
    }
    return new ResourceDirectoryQuery(permissionEntityManager, context, distribution);
  }

  @Override
  public Page<ResourceNode> assignedTree(
      Collection<String> codes, Map<String, String> attributes, Pageable pageable) {
    if (pageable.getPageSize() > TablePagination.MAX_PAGE_SIZE) {
      throw new org.simplepoint.core.query.InvalidQueryException("目录分页大小不能超过500");
    }
    String parentId =
        firstNonBlank(attribute(attributes, "parentId"), attribute(attributes, "parent"));
    boolean distribution = distributionRequested(attributes);
    // Keep unavailable assignments visible for removal; only new assignment uses delivery policy.
    var result = directoryQuery().assigned(normalizeCodes(codes), parentId, pageable);
    List<ResourceNode> nodes =
        result.page().getContent().stream().map(this::toResourceNode).toList();
    nodes.forEach(
        node -> {
          node.setHasChildren(result.parentsWithChildren().contains(node.getId()));
          node.setChecked(result.checkedIds().contains(node.getId()));
          if (distribution) {
            node.setDistributable(
                org.simplepoint.security.ResourceDistributionPolicy.accepts(node));
          }
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
    if (normalizedCodes.isEmpty()) {
      return List.of();
    }
    var access = org.simplepoint.core.jackson.FieldPermissionPolicy.access(Resource.class, "code");
    if ("MASKED".equals(access)
        || org.simplepoint.core.jackson.FieldPermissionPolicy.hidden(access)) {
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
      Collection<String> codes, AuthorizationScopeType scopeType, boolean systemAdministrator) {
    Set<String> normalizedCodes = normalizeCodes(codes);
    if (normalizedCodes.isEmpty() || scopeType == null) {
      return List.of();
    }
    return runtimeQuery().filter(normalizedCodes, scopeType, systemAdministrator, false);
  }

  @Override
  public Collection<String> filterGrantableAccessibleCodes(
      Collection<String> codes, AuthorizationScopeType scopeType) {
    Set<String> normalizedCodes = normalizeCodes(codes);
    return normalizedCodes.isEmpty() || scopeType == null
        ? List.of()
        : runtimeQuery().filter(normalizedCodes, scopeType, false, true);
  }

  @Override
  public Collection<String> filterDistributableCodes(Collection<String> codes) {
    requireDistributionAdministrator();
    return runtimeQuery().distributable(normalizeCodes(codes));
  }

  @Override
  public Collection<String> findSubtreeDistributableCodes(String rootId) {
    requireDistributionAdministrator();
    return hasText(rootId) ? directoryQuery(true).subtreeCodes(rootId) : List.of();
  }

  @Override
  public Collection<String> findAllAccessibleCodes(
      AuthorizationScopeType scopeType, boolean systemAdministrator) {
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
    if (permissionEntityManager == null) {
      throw new IllegalStateException("资源鉴权查询服务不可用");
    }
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
    Collection<String> ancestors =
        resourceAncestorRepository.findAncestorIdsByChildIdIn(Set.of(saved.getParentId()));
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

  private void retireUndeclaredResources(
      String owner, Set<String> retiredIds, Set<String> synchronizedCodes) {
    if (retiredIds.isEmpty()) {
      return;
    }
    // The closure table may contain stale edges. Never let it delete a retained or foreign record.
    Set<String> affectedIds = new HashSet<>(retiredIds);
    affectedIds.addAll(resourceAncestorRepository.findChildIdsByAncestorIds(retiredIds));
    for (Resource affected : getRepository().loadByIds(affectedIds)) {
      if (!owner.equals(affected.getPluginId()) || synchronizedCodes.contains(affected.getCode())) {
        throw new org.simplepoint.security.resource.ResourceRegistrationException(
            org.simplepoint.security.resource.ResourceRegistrationException.Code
                .HIERARCHY_CONFLICT);
      }
    }
    removeCatalogResources(retiredIds);
    log.info("Retired {} undeclared resources for owner: {}", retiredIds.size(), owner);
  }

  private void validateScopeBoundary(Resource resource) {
    if (resource == null || !hasText(resource.getParentId())) {
      return;
    }
    if (Objects.equals(resource.getId(), resource.getParentId())) {
      throw new IllegalArgumentException("资源不能将自身设置为父资源");
    }
    Resource parent =
        super.findById(resource.getParentId())
            .orElseThrow(() -> new IllegalArgumentException("父资源不存在"));
    if (!ResourceScopePolicy.isValidChild(parent.getScopeTypes(), resource.getScopeTypes())) {
      throw new IllegalArgumentException("子资源的资源级别不能超出父资源边界");
    }
  }

  private List<ResourceNode> buildResourceTree(
      Collection<Resource> resources,
      Set<ServiceResourceRouteResult.ServiceEntry> services,
      boolean clearRuntimeFields,
      Map<String, String> remoteEntries) {
    return buildResourceTree(
        resources, services, clearRuntimeFields, remoteEntries, Resource.class);
  }

  private List<ResourceNode> buildResourceTree(
      Collection<Resource> resources,
      Set<ServiceResourceRouteResult.ServiceEntry> services,
      boolean clearRuntimeFields,
      Map<String, String> remoteEntries,
      Class<?> sourceType) {
    return buildResourceTree(
        resources, services, clearRuntimeFields, remoteEntries, sourceType, Map.of());
  }

  private List<ResourceNode> buildResourceTree(
      Collection<Resource> resources,
      Set<ServiceResourceRouteResult.ServiceEntry> services,
      boolean clearRuntimeFields,
      Map<String, String> remoteEntries,
      Class<?> sourceType,
      Map<String, org.simplepoint.api.locale.LocalizedText> localizedNames) {
    Map<String, String> readableLabels = new HashMap<>();
    for (Resource resource : resources) {
      readableLabels.put(resource.getId(), readableResourceLabel(resource, sourceType));
    }
    String sortAccess =
        org.simplepoint.core.jackson.FieldPermissionPolicy.access(sourceType, "sort");
    boolean readableSort =
        !"MASKED".equals(sortAccess)
            && !org.simplepoint.core.jackson.FieldPermissionPolicy.hidden(sortAccess);
    List<Resource> sorted =
        resources.stream()
            .sorted(
                Comparator.comparing(
                        (Resource resource) -> readableSort ? resource.getSort() : null,
                        Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(
                        resource -> readableLabels.get(resource.getId()),
                        Comparator.nullsLast(String::compareTo)))
            .toList();
    Map<String, ResourceNode> nodeMap = new HashMap<>();
    for (Resource resource : sorted) {
      addServiceEntry(services, resource.getComponent(), remoteEntries);
      ResourceNode node =
          sourceType == TenantResource.class
              ? new org.simplepoint.plugin.rbac.tenant.api.vo.TenantResourceNode()
              : new ResourceNode();
      BeanUtils.copyProperties(resource, node);
      if (node instanceof org.simplepoint.plugin.rbac.tenant.api.vo.TenantResourceNode tenantNode)
        tenantNode.setNameLocalization(localizedNames.get(resource.getId()));
      node.setLabel(readableLabels.get(resource.getId()));
      nodeMap.put(resource.getId(), node);
    }
    List<ResourceNode> roots = new ArrayList<>();
    for (Resource resource : sorted) {
      ResourceNode current = nodeMap.get(resource.getId());
      ResourceNode parent =
          hasText(resource.getParentId()) ? nodeMap.get(resource.getParentId()) : null;
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

  /**
   * Resolve display fallbacks only after source-field projection; otherwise a hidden name reappears
   * through label.
   */
  private String readableResourceLabel(Resource resource) {
    return readableResourceLabel(resource, Resource.class);
  }

  private String readableResourceLabel(Resource resource, Class<?> sourceType) {
    var source = new java.util.LinkedHashMap<String, Object>();
    source.put("label", resource.getLabel());
    source.put("name", resource.getName());
    source.put("title", resource.getTitle());
    source.put("code", resource.getCode());
    var readable = org.simplepoint.core.jackson.FieldPermissionPolicy.project(sourceType, source);
    return firstNonBlank(
        (String) readable.get("label"),
        (String) readable.get("name"),
        (String) readable.get("title"),
        (String) readable.get("code"));
  }

  /**
   * Ordinary administrator navigation retains its cache; request-specific redacted labels never
   * enter or reuse it.
   */
  private boolean resourceLabelsAreCacheable() {
    return java.util.stream.Stream.of("label", "name", "title", "code")
        .map(
            field ->
                org.simplepoint.core.jackson.FieldPermissionPolicy.access(Resource.class, field))
        .noneMatch(
            access ->
                "MASKED".equals(access)
                    || org.simplepoint.core.jackson.FieldPermissionPolicy.hidden(access));
  }

  private ServiceResourceRouteResult withScopeContext(
      ServiceResourceRouteResult result, AuthorizationContext context) {
    if (context == null) {
      return result;
    }
    return result.withAuthorizationContext(
        Map.of(
            "scopeType", context.getScopeType() == null ? "" : context.getScopeType().name(),
            "actorRole", context.getActorRole() == null ? "" : context.getActorRole().name(),
            "tenantId",
                context.getAttribute("X-Tenant-Id") == null
                    ? ""
                    : context.getAttribute("X-Tenant-Id"),
            "userId", context.getUserId() == null ? "" : context.getUserId()));
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
      Map<String, String> remoteEntries) {
    String serviceName = getServiceName(component);
    if (!hasText(serviceName) || isNonRemoteComponent(component)) {
      return;
    }
    services.add(
        ServiceResourceRouteResult.ServiceEntry.of(serviceName, remoteEntries.get(serviceName)));
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
    return component != null
        && (component.startsWith("external:") || component.startsWith("iframe:"));
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
    Set<ResourceScopeType> effectiveScopes =
        ResourceScopePolicy.effectiveScopes(resource.getScopeTypes());
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
    resource.setRequireOrgTenant(
        effectiveScopes.contains(ResourceScopeType.TENANT)
            && !effectiveScopes.contains(ResourceScopeType.PERSONAL));
    if (resource.getGrantable() == null) {
      resource.setGrantable(
          resource.getType() != ResourceType.GROUP && resource.getType() != ResourceType.MODULE);
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
    if (resource == null
        || resource.getType() != ResourceType.ACTION
        || !hasText(resource.getCode())) {
      return;
    }
    ButtonResourceMetadataRegistry registry =
        buttonResourceMetadataRegistryProvider.getIfAvailable();
    if (registry == null) {
      return;
    }
    ButtonResourceMetadataRegistry.ButtonResourceMetadata metadata =
        registry.find(resource.getCode());
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
