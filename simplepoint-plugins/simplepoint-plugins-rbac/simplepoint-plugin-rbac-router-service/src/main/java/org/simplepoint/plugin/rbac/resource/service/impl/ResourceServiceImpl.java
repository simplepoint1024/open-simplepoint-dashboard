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
import org.simplepoint.plugin.rbac.tenant.api.service.BuiltInTenantProvisioner;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
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
    if (context == null) {
      throw new IllegalArgumentException("User context is null or not logged in");
    }
    if (AuthorizationScopeGuards.isPlatformAdministrator(context)) {
      ServiceResourceRouteResult cached = adminRoutesCache;
      if (cached != null && System.currentTimeMillis() < adminRoutesCacheExpiry) {
        return withScopeContext(cached, context);
      }
      Set<ServiceResourceRouteResult.ServiceEntry> services = new HashSet<>();
      ServiceResourceRouteResult result = ServiceResourceRouteResult.of(
          services,
          buildResourceTree(
              getRepository().findRouteResourcesAll().stream()
                  .filter(resource -> isAccessible(resource, context))
                  .toList(),
              services,
              true,
              loadRemoteEntriesByServiceName()
          )
      );
      adminRoutesCache = result;
      adminRoutesCacheExpiry = System.currentTimeMillis() + 30_000;
      return withScopeContext(result, context);
    }

    Set<String> resourceCodes = new LinkedHashSet<>(context.getResources());
    resourceCodes.addAll(findPublicAccessCodes());
    resourceCodes.retainAll(filterAccessibleCodes(
        resourceCodes,
        context.getScopeType(),
        Boolean.TRUE.equals(context.getIsAdministrator())
    ));
    if (resourceCodes.isEmpty()) {
      return withScopeContext(ServiceResourceRouteResult.EMPTY, context);
    }
    Set<String> routeIds = new LinkedHashSet<>(getRepository().findIdsByCodes(resourceCodes));
    routeIds.addAll(resourceAncestorRepository.findAncestorIdsByChildIdIn(routeIds));
    if (routeIds.isEmpty()) {
      return withScopeContext(ServiceResourceRouteResult.EMPTY, context);
    }
    Collection<Resource> resources = getRepository().loadByIds(routeIds).stream()
        .filter(this::isRouteResource)
        .toList();
    Set<ServiceResourceRouteResult.ServiceEntry> services = new HashSet<>();
    return withScopeContext(ServiceResourceRouteResult.of(
        services,
        buildResourceTree(resources, services, true, loadRemoteEntriesByServiceName())
    ), context);
  }

  @Override
  public Page<ResourceNode> limitTree(Map<String, String> attributes, Pageable pageable) {
    Map<String, String> scopedAttributes = new HashMap<>(attributes == null ? Map.of() : attributes);
    scopedAttributes.put("parentId", "is:null:");
    Page<Resource> roots = limit(scopedAttributes, pageable);
    List<String> rootIds = roots.map(Resource::getId).stream().toList();
    Collection<String> childIds = resourceAncestorRepository.findChildIdsByAncestorIds(rootIds);
    List<Resource> treeResources = new ArrayList<>(findAllByIds(childIds));
    treeResources.addAll(roots.getContent());
    return new PageImpl<>(
        buildResourceTree(treeResources, new HashSet<>(), false, Map.of()),
        pageable,
        roots.getTotalElements()
    );
  }

  @Override
  public Page<ResourceNode> children(Map<String, String> attributes, Pageable pageable) {
    String parentId = firstNonBlank(attribute(attributes, "parentId"), attribute(attributes, "parent"));
    String keyword = firstNonBlank(attribute(attributes, "keyword"), attribute(attributes, "search"));
    Page<Resource> page = hasText(keyword) && !hasText(parentId)
        ? getRepository().findMatches(pageable, keyword)
        : getRepository().findChildren(pageable, parentId, keyword);
    List<ResourceNode> nodes = page.getContent().stream()
        .map(this::toResourceNode)
        .toList();
    Set<String> parentIds = nodes.stream()
        .map(ResourceNode::getId)
        .filter(this::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Collection<String> idsWithChildren = parentIds.isEmpty()
        ? List.of()
        : getRepository().findParentIdsWithChildren(parentIds);
    Set<String> idsWithChildrenSet = new HashSet<>(idsWithChildren);
    nodes.forEach(node -> node.setHasChildren(idsWithChildrenSet.contains(node.getId())));
    return new PageImpl<>(nodes, pageable, page.getTotalElements());
  }

  @Override
  public Page<ResourceNode> assignedTree(Collection<String> codes, Map<String, String> attributes, Pageable pageable) {
    Set<String> normalizedCodes = normalizeCodes(codes);
    if (normalizedCodes.isEmpty()) {
      return Page.empty(pageable);
    }
    if (!AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      Set<String> visibleCodes = visibleTenantResourceCodes();
      normalizedCodes.retainAll(visibleCodes);
      if (normalizedCodes.isEmpty()) {
        return Page.empty(pageable);
      }
    }

    Set<String> selectedIds = new LinkedHashSet<>(getRepository().findIdsByCodes(normalizedCodes));
    if (selectedIds.isEmpty()) {
      return Page.empty(pageable);
    }
    Set<String> includedIds = new LinkedHashSet<>(selectedIds);
    includedIds.addAll(resourceAncestorRepository.findAncestorIdsByChildIdIn(selectedIds));
    String parentId = firstNonBlank(attribute(attributes, "parentId"), attribute(attributes, "parent"));
    Page<Resource> page = getRepository().findChildrenByIds(pageable, includedIds, parentId);
    List<ResourceNode> nodes = page.getContent().stream()
        .map(this::toResourceNode)
        .toList();
    Set<String> parentIds = nodes.stream()
        .map(ResourceNode::getId)
        .filter(this::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Collection<String> idsWithChildren = parentIds.isEmpty()
        ? List.of()
        : getRepository().findParentIdsWithChildrenIn(includedIds, parentIds);
    Set<String> idsWithChildrenSet = new HashSet<>(idsWithChildren);
    nodes.forEach(node -> {
      node.setHasChildren(idsWithChildrenSet.contains(node.getId()));
      node.setChecked(hasText(node.getCode()) && normalizedCodes.contains(node.getCode()));
    });
    return new PageImpl<>(nodes, pageable, page.getTotalElements());
  }

  @Override
  public Collection<Resource> findAllByCodes(Collection<String> codes) {
    Set<String> normalizedCodes = normalizeCodes(codes);
    if (normalizedCodes.isEmpty()) {
      return List.of();
    }
    AuthorizationContext context = getAuthorizationContext();
    if (!AuthorizationScopeGuards.isPlatformAdministrator(context)) {
      Set<String> visibleCodes = visibleTenantResourceCodes();
      normalizedCodes.retainAll(visibleCodes);
      if (normalizedCodes.isEmpty()) {
        return List.of();
      }
    }
    return getRepository().findAllByCodes(normalizedCodes).stream()
        .filter(resource -> isAccessible(resource, context))
        .toList();
  }

  @Override
  public Collection<Resource> findAllAccessible() {
    AuthorizationContext context = getAuthorizationContext();
    if (context == null) {
      return List.of();
    }
    if (AuthorizationScopeGuards.isPlatformAdministrator(context)) {
      return getRepository().loadAll().stream()
          .filter(resource -> isAccessible(resource, context))
          .toList();
    }
    Set<String> visibleCodes = visibleTenantResourceCodes();
    if (visibleCodes.isEmpty()) {
      return List.of();
    }
    Set<String> visibleIds = new LinkedHashSet<>(getRepository().findIdsByCodes(visibleCodes));
    visibleIds.addAll(resourceAncestorRepository.findAncestorIdsByChildIdIn(visibleIds));
    return getRepository().loadByIds(visibleIds).stream()
        .filter(resource -> isAccessible(resource, context))
        .toList();
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
    AuthorizationContext context = new AuthorizationContext();
    context.setScopeType(scopeType);
    context.setIsAdministrator(systemAdministrator);
    return getRepository().findAllByCodes(normalizedCodes).stream()
        .filter(resource -> isAccessible(resource, context))
        .filter(resource -> Boolean.FALSE.equals(resource.getDisabled()))
        .map(Resource::getCode)
        .filter(this::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @Override
  public Collection<String> filterGrantableAccessibleCodes(
      Collection<String> codes,
      AuthorizationScopeType scopeType
  ) {
    Set<String> accessibleCodes = new LinkedHashSet<>(filterAccessibleCodes(codes, scopeType, false));
    if (accessibleCodes.isEmpty()) {
      return List.of();
    }
    return getRepository().findAllByCodes(accessibleCodes).stream()
        .filter(resource -> Boolean.TRUE.equals(resource.getGrantable()))
        .filter(resource -> Boolean.FALSE.equals(resource.getDisabled()))
        .map(Resource::getCode)
        .filter(this::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @Override
  public Collection<String> findAllAccessibleCodes(
      AuthorizationScopeType scopeType,
      boolean systemAdministrator
  ) {
    if (scopeType == null) {
      return List.of();
    }
    AuthorizationContext context = new AuthorizationContext();
    context.setScopeType(scopeType);
    context.setIsAdministrator(systemAdministrator);
    return getRepository().loadAll().stream()
        .filter(resource -> isAccessible(resource, context))
        .filter(resource -> Boolean.FALSE.equals(resource.getDisabled()))
        .map(Resource::getCode)
        .filter(this::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @Override
  public Collection<String> findSubtreeGrantableCodes(String rootId) {
    if (!hasText(rootId)) {
      return List.of();
    }
    Set<String> ids = new LinkedHashSet<>();
    ids.add(rootId);
    ids.addAll(resourceAncestorRepository.findChildIdsByAncestorIds(Set.of(rootId)));
    if (ids.isEmpty()) {
      return List.of();
    }

    Set<String> visibleCodes = AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())
        ? null
        : visibleTenantResourceCodes();
    return getRepository().loadByIds(ids).stream()
        .filter(resource -> isAccessible(resource, getAuthorizationContext()))
        .filter(resource -> Boolean.TRUE.equals(resource.getGrantable()))
        .filter(resource -> Boolean.FALSE.equals(resource.getDisabled()))
        .map(Resource::getCode)
        .filter(this::hasText)
        .filter(code -> visibleCodes == null || visibleCodes.contains(code))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @Override
  public Collection<String> findAllRequireOrgTenantCodes() {
    return getRepository().findCodesByRequireOrgTenant();
  }

  @Override
  public Collection<String> findPublicAccessCodes() {
    return getRepository().findPublicAccessCodes();
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

  private void migrateResourceCode(String oldCode, String newCode) {
    if (!hasText(oldCode) || !hasText(newCode) || Objects.equals(oldCode, newCode)) {
      return;
    }
    var grantRepository = roleResourceGrantRepositoryProvider.getIfAvailable();
    if (grantRepository != null) {
      grantRepository.updateResourceCode(oldCode, newCode);
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
    List<Resource> sorted = resources.stream().sorted(this::compareResources).toList();
    Map<String, ResourceNode> nodeMap = new HashMap<>();
    for (Resource resource : sorted) {
      addServiceEntry(services, resource.getComponent(), remoteEntries);
      ResourceNode node = new ResourceNode();
      BeanUtils.copyProperties(resource, node);
      node.setLabel(firstNonBlank(resource.getLabel(), resource.getName(), resource.getTitle(), resource.getCode()));
      nodeMap.put(resource.getId(), node);
    }
    List<ResourceNode> roots = new ArrayList<>();
    for (Resource resource : sorted) {
      ResourceNode current = nodeMap.get(resource.getId());
      ResourceNode parent = hasText(resource.getParentId()) ? nodeMap.get(resource.getParentId()) : null;
      if (parent == null) {
        roots.add(current);
      } else {
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
    node.setLabel(firstNonBlank(resource.getLabel(), resource.getName(), resource.getTitle(), resource.getCode()));
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

  private Set<String> visibleTenantResourceCodes() {
    if (AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return getRepository().loadAll().stream()
          .filter(resource -> isAccessible(resource, getAuthorizationContext()))
          .map(Resource::getCode)
          .filter(this::hasText)
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }
    String tenantId = currentTenantId();
    if (!hasText(tenantId)) {
      var tenantRepository = tenantRepositoryProvider.getIfAvailable();
      var context = getAuthorizationContext();
      if (tenantRepository != null && context != null && hasText(context.getUserId())) {
        tenantId = tenantRepository.findPersonalTenantByOwnerId(context.getUserId())
            .map(org.simplepoint.plugin.rbac.tenant.api.entity.Tenant::getId)
            .orElse(null);
      }
    }
    TenantPackageRelevanceRepository repository = tenantPackageRelevanceRepositoryProvider.getIfAvailable();
    if (!hasText(tenantId) || repository == null) {
      return new LinkedHashSet<>(findPublicAccessCodes());
    }
    Set<String> codes = new LinkedHashSet<>(repository.findResourceCodesByTenantId(tenantId));
    codes.addAll(findPublicAccessCodes());
    codes.retainAll(filterAccessibleCodes(
        codes,
        getAuthorizationContext().getScopeType(),
        Boolean.TRUE.equals(getAuthorizationContext().getIsAdministrator())
    ));
    return codes;
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

  private int compareResources(Resource left, Resource right) {
    return Comparator
        .comparing((Resource resource) -> resource.getSort(), Comparator.nullsLast(Integer::compareTo))
        .thenComparing(resource -> firstNonBlank(resource.getLabel(), resource.getName(), resource.getTitle(), resource.getCode()))
        .compare(left, right);
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
