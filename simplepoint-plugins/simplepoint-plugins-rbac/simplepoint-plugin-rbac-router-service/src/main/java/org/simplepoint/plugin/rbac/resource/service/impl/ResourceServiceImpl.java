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
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceAncestorRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.resource.api.vo.MicroModuleItemVo;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceAncestor;
import org.simplepoint.security.entity.ResourceNode;
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
      ObjectProvider<ResourceAuthorizationVersionService> resourceAuthorizationVersionServiceProvider
  ) {
    super(repository, detailsProviderService);
    this.resourceAncestorRepository = resourceAncestorRepository;
    this.microAppService = microAppService;
    this.tenantPackageRelevanceRepositoryProvider = tenantPackageRelevanceRepositoryProvider;
    this.tenantRepositoryProvider = tenantRepositoryProvider;
    this.roleResourceGrantRepositoryProvider = roleResourceGrantRepositoryProvider;
    this.resourceAuthorizationVersionServiceProvider = resourceAuthorizationVersionServiceProvider;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public <S extends Resource> S create(S entity) {
    normalize(entity);
    S saved = super.create(entity);
    adminRoutesCache = null;
    saveAncestors(saved, false);
    return saved;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public <S extends Resource> Resource modifyById(S entity) {
    normalize(entity);
    Resource current = findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("资源不存在"));
    String oldCode = current.getCode();
    Resource updated = (Resource) super.modifyById(entity);
    adminRoutesCache = null;
    saveAncestors(updated, true);
    if (!Objects.equals(oldCode, updated.getCode())) {
      var grantRepository = roleResourceGrantRepositoryProvider.getIfAvailable();
      if (grantRepository != null) {
        grantRepository.updateResourceCode(oldCode, updated.getCode());
      }
      var refreshService = resourceAuthorizationVersionServiceProvider.getIfAvailable();
      if (refreshService != null) {
        refreshService.refreshByResourceCodes(Set.of(oldCode, updated.getCode()));
      }
    }
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

    ArrayDeque<ResourceDeclaration> queue = new ArrayDeque<>(declarations);
    while (!queue.isEmpty()) {
      ResourceDeclaration declaration = queue.poll();
      Resource resource = declaration.toResource();
      resource.setPluginId(firstNonBlank(resource.getPluginId(), owner));
      normalize(resource);
      Resource existing = resolveExisting(resourcesByCode, resourcesByPath, resource);
      if (existing != null) {
        resource.setId(existing.getId());
        resource = getRepository().updateById(resource);
        saveAncestors(resource, true);
      } else {
        resource = create(resource);
      }
      indexResource(resourcesByCode, resourcesByPath, resource);

      if (declaration.getChildren() != null && !declaration.getChildren().isEmpty()) {
        for (ResourceDeclaration child : declaration.getChildren()) {
          child.setParentId(resource.getId());
          queue.offer(child);
        }
      }
    }
    log.info("Resource sync completed for owner: {}", owner);
  }

  @Override
  public ServiceResourceRouteResult routes() {
    AuthorizationContext context = getAuthorizationContext();
    if (context == null) {
      throw new IllegalArgumentException("User context is null or not logged in");
    }
    if (Boolean.TRUE.equals(context.getIsAdministrator())) {
      ServiceResourceRouteResult cached = adminRoutesCache;
      if (cached != null && System.currentTimeMillis() < adminRoutesCacheExpiry) {
        return withScopeContext(cached, context);
      }
      Set<ServiceResourceRouteResult.ServiceEntry> services = new HashSet<>();
      ServiceResourceRouteResult result = ServiceResourceRouteResult.of(
          services,
          buildResourceTree(getRepository().findRouteResourcesAll(), services, true, loadRemoteEntriesByServiceName())
      );
      adminRoutesCache = result;
      adminRoutesCacheExpiry = System.currentTimeMillis() + 30_000;
      return withScopeContext(result, context);
    }

    Set<String> resourceCodes = new LinkedHashSet<>(context.getResources());
    resourceCodes.addAll(findPublicAccessCodes());
    addTenantPackageResources(resourceCodes);
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
  public Collection<Resource> findAllByCodes(Collection<String> codes) {
    Set<String> normalizedCodes = normalizeCodes(codes);
    if (normalizedCodes.isEmpty()) {
      return List.of();
    }
    if (!AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      Set<String> visibleCodes = visibleTenantResourceCodes();
      normalizedCodes.retainAll(visibleCodes);
      if (normalizedCodes.isEmpty()) {
        return List.of();
      }
    }
    return getRepository().findAllByCodes(normalizedCodes);
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

  private void addTenantPackageResources(Set<String> resourceCodes) {
    String tenantId = currentTenantId();
    if (!hasText(tenantId)) {
      return;
    }
    TenantPackageRelevanceRepository repository = tenantPackageRelevanceRepositoryProvider.getIfAvailable();
    if (repository != null) {
      resourceCodes.addAll(repository.findResourceCodesByTenantId(tenantId));
    }
  }

  private Set<String> visibleTenantResourceCodes() {
    if (AuthorizationScopeGuards.isPlatformAdministrator(getAuthorizationContext())) {
      return new LinkedHashSet<>(getRepository().findCodesByTypes(List.of(ResourceType.GROUP, ResourceType.MODULE,
          ResourceType.PAGE, ResourceType.FEATURE, ResourceType.ACTION, ResourceType.API)));
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
    if (!hasText(resource.getRouteKind()) && isRouteType(resource.getType())) {
      resource.setRouteKind(resource.getType() == ResourceType.GROUP || resource.getType() == ResourceType.MODULE ? "group" : "item");
    }
    if (resource.getPublicAccess() == null) {
      resource.setPublicAccess(Boolean.FALSE);
    }
    if (resource.getRequireOrgTenant() == null) {
      resource.setRequireOrgTenant(Boolean.FALSE);
    }
    if (resource.getGrantable() == null) {
      resource.setGrantable(resource.getType() != ResourceType.GROUP && resource.getType() != ResourceType.MODULE);
    }
    if (resource.getDisabled() == null) {
      resource.setDisabled(Boolean.FALSE);
    }
  }

  private boolean isRouteResource(Resource resource) {
    return resource != null && hasText(resource.getPath()) && Boolean.FALSE.equals(resource.getDisabled());
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
