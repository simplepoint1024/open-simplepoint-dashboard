package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.error.ManagedTenantResourceErrorCode;
import org.simplepoint.plugin.rbac.tenant.api.error.ManagedTenantResourceException;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedTenantResourceCommand;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedTenantResourceView;
import org.simplepoint.plugin.rbac.tenant.api.service.ManagedTenantResourceService;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable tenant resource lifecycle owned by authenticated platform services. */
@Service
@RemoteProvider
@ConditionalOnProperty(prefix = "simplepoint.rbac.managed-resource", name = "enabled", havingValue = "true")
public class ManagedTenantResourceServiceImpl implements ManagedTenantResourceService {

  private static final int MAX_SUBTREE_SIZE = 5_000;
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}");
  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}");
  private static final Pattern PATH = Pattern.compile("^/(?!/)[A-Za-z0-9/_-]+$");
  private static final Set<ResourceType> TYPES = Set.of(
      ResourceType.GROUP,
      ResourceType.MODULE,
      ResourceType.PAGE,
      ResourceType.ACTION,
      ResourceType.FEATURE
  );

  private final EntityManager entityManager;
  private final ResourceRepository platformResources;
  private final ResourceAuthorizationVersionService authorizationVersions;
  private final ManagedTenantResourceCallerGuard callerGuard;

  public ManagedTenantResourceServiceImpl(
      final EntityManager entityManager,
      final ResourceRepository platformResources,
      final ResourceAuthorizationVersionService authorizationVersions,
      final ManagedTenantResourceCallerGuard callerGuard
  ) {
    this.entityManager = entityManager;
    this.platformResources = platformResources;
    this.authorizationVersions = authorizationVersions;
    this.callerGuard = callerGuard;
  }

  @Override
  @Transactional
  public ManagedTenantResourceView prepare(final ManagedTenantResourceCommand command) {
    String owner = callerGuard.requireOwner();
    Declaration declaration = validate(owner, command);
    lockTenant(declaration.tenantId());
    TenantResource parent = resolveParent(owner, declaration);
    Resource host = resolveHost(declaration);
    TenantResource existing = find(owner, declaration.tenantId(), declaration.managedKey(), true);
    if (existing != null) {
      requireImmutableDeclaration(existing, parent, host, declaration);
      refreshPresentation(existing, host, declaration);
      entityManager.flush();
      authorizationVersions.refreshTenant(declaration.tenantId());
      return view(existing);
    }
    requireCodeAvailable(declaration.tenantId(), declaration.resourceCode());
    requirePathAvailable(declaration.tenantId(), declaration.path(), null);
    TenantResource resource = new TenantResource();
    resource.setTenantId(declaration.tenantId());
    resource.setId(stableId(declaration.tenantId(), owner, declaration.managedKey()));
    resource.setManagedOwner(owner);
    resource.setManagedKey(declaration.managedKey());
    resource.setManagedHostResourceId(host == null ? null : host.getId());
    resource.setParentId(parentId(parent, declaration));
    resource.setCode(declaration.resourceCode());
    resource.setType(declaration.type());
    resource.setPath(declaration.path());
    resource.setEnabled(false);
    resource.setGrantable(isGrantable(declaration.type()));
    resource.setLocalModified(false);
    resource.setManualDisabled(false);
    refreshPresentation(resource, host, declaration);
    try {
      entityManager.persist(resource);
      entityManager.flush();
    } catch (PersistenceException exception) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
          "Managed resource identity, code or path conflicts with another resource",
          exception
      );
    }
    authorizationVersions.refreshTenant(declaration.tenantId());
    return view(resource);
  }

  @Override
  @Transactional
  public ManagedTenantResourceView activate(final String tenantId, final String managedKey) {
    return changeState(tenantId, managedKey, true);
  }

  @Override
  @Transactional
  public ManagedTenantResourceView deactivate(final String tenantId, final String managedKey) {
    return changeState(tenantId, managedKey, false);
  }

  @Override
  @Transactional(readOnly = true)
  public ManagedTenantResourceView get(final String tenantId, final String managedKey) {
    String owner = callerGuard.requireOwner();
    String tenant = required(tenantId, 36, "tenantId");
    String key = requiredKey(managedKey);
    TenantResource resource = find(owner, tenant, key, false);
    if (resource == null) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_NOT_FOUND,
          "Managed tenant resource does not exist"
      );
    }
    return view(resource);
  }

  @Override
  @Transactional
  public ManagedTenantResourceView activateGeneration(String tenantId, String managedKey, long generation) {
    return activateWithPermissions(tenantId, managedKey, generation, null);
  }

  @Override
  @Transactional
  public ManagedTenantResourceView activateWithPermissions(String tenantId, String managedKey, long generation,
      org.simplepoint.core.permission.PermissionPageDescriptor permissions) {
    if (generation < 1) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, "Publication generation is required");
    }
    String owner = callerGuard.requireOwner();
    lockTenant(tenantId);
    TenantResource resource = find(owner, tenantId, managedKey, true);
    if (resource == null || generation < resource.getLifecycleGeneration()
        || generation == resource.getLifecycleGeneration() && resource.isFormallyOffline()) {
      throw failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT, "Publication has been taken offline or superseded");
    }
    storePermissions(resource, generation, permissions);
    for (TenantResource item : subtree(tenantId, owner, resource)) {
      item.setLifecycleGeneration(generation);
      item.setFormallyOffline(false);
    }
    var activated = changeState(tenantId, managedKey, true);
    // An already-enabled page can publish changed metadata without a visibility transition.
    entityManager.flush();
    authorizationVersions.refreshTenant(tenantId);
    return activated;
  }

  @Override
  @Transactional
  public ManagedTenantResourceView refreshPermissions(String tenantId, String managedKey, long generation,
      org.simplepoint.core.permission.PermissionPageDescriptor permissions) {
    String owner = callerGuard.requireOwner();
    lockTenant(tenantId);
    TenantResource resource = find(owner, tenantId, managedKey, true);
    boolean legacy = resource != null && resource.getLifecycleGeneration() == 0
        && resource.getPermissionMetadata() == null && generation > 0;
    if (resource == null || !resource.isEnabled() || resource.isFormallyOffline() || generation < 1
        || (!legacy && generation != resource.getLifecycleGeneration()) || permissions == null) {
      throw failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT, "The active publication generation has changed");
    }
    // Pre-lifecycle publications have generation zero. The trusted owner has verified its
    // active binding; adopt that generation once, without enabling nodes or touching grants.
    List<TenantResource> legacyTree = legacy ? subtree(tenantId, owner, resource) : List.of();
    if (legacyTree.stream().anyMatch(item -> item.getLifecycleGeneration() != 0 || item.isFormallyOffline())) {
      throw failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT, "The legacy resource tree has advanced");
    }
    storePermissions(resource, generation, permissions);
    for (TenantResource item : legacyTree) {
      item.setLifecycleGeneration(generation);
      item.setUpdatedAt(Instant.now());
    }
    entityManager.flush();
    authorizationVersions.refreshTenant(tenantId);
    return view(resource);
  }

  private static void storePermissions(TenantResource resource, long generation,
      org.simplepoint.core.permission.PermissionPageDescriptor permissions) {
    if (permissions != null) {
      if (resource.getType() != ResourceType.PAGE || permissions.models().isEmpty()
          || permissions.models().size() > 21
          || permissions.models().stream().mapToInt(model -> model.properties().size()).sum() > 1000) {
        throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, "Invalid managed page permission descriptor");
      }
      if (generation == resource.getLifecycleGeneration() && resource.getPermissionMetadata() != null
          && !permissions.equals(org.simplepoint.core.permission.PermissionPageDescriptor.fromJson(resource.getPermissionMetadata()))) {
        throw failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
            "Permission metadata cannot change within an activated publication generation");
      }
      String metadata = permissions.toJson();
      if (!metadata.equals(resource.getPermissionMetadata())) {
        resource.setPermissionMetadata(metadata);
        resource.setUpdatedAt(Instant.now());
      }
    }
  }

  /** Trusted event adapter entry, deliberately absent from the remotely invocable interface. */
  @Transactional
  public void offlineFromEvent(org.simplepoint.events.api.EventMessage event) {
    var request = org.simplepoint.platform.resource.ResourceLifecycleEvents.request(event);
    lockTenant(event.tenantId());
    TenantResource root = find(request.managedOwner(), event.tenantId(), request.managedKey(), true);
    if (root == null || !root.getId().equals(event.resourceId()) || !root.getCode().equals(request.resourceCode())) {
      throw failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT, "Offline request does not own this resource");
    }
    if (event.generation() < root.getLifecycleGeneration()) {
      return;
    }
    List<TenantResource> resources = subtree(event.tenantId(), request.managedOwner(), root);
    Instant now = Instant.now();
    for (TenantResource item : resources) {
      item.setEnabled(false);
      item.setFormallyOffline(true);
      item.setLifecycleGeneration(event.generation());
      item.setUpdatedAt(now);
    }
    List<String> codes = resources.stream().map(TenantResource::getCode).toList();
    for (int start = 0; start < codes.size(); start += 100) {
      emRevoke(event.tenantId(), codes.subList(start, Math.min(start + 100, codes.size())), now);
    }
    entityManager.flush();
    authorizationVersions.refreshTenant(event.tenantId());
  }

  private void emRevoke(String tenantId, List<String> codes, Instant now) {
    // Keep shared data/field policies and grant history; remove only effective assignments.
    entityManager.createQuery("update RoleResourceGrant g set g.deletedAt = :now "
        + "where g.tenantId = :tenant and g.resourceCode in :codes and g.deletedAt is null")
        .setParameter("now", now).setParameter("tenant", tenantId).setParameter("codes", codes).executeUpdate();
  }

  private ManagedTenantResourceView changeState(
      final String tenantId,
      final String managedKey,
      final boolean enabled
  ) {
    String owner = callerGuard.requireOwner();
    String tenant = required(tenantId, 36, "tenantId");
    String key = requiredKey(managedKey);
    lockTenant(tenant);
    TenantResource root = find(owner, tenant, key, true);
    if (root == null) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_NOT_FOUND,
          "Managed tenant resource does not exist"
      );
    }
    List<TenantResource> subtree = subtree(tenant, owner, root);
    if (enabled) {
      if (subtree.stream().anyMatch(TenantResource::isFormallyOffline)) {
        throw failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
            "Formal offline requires a newer publication generation");
      }
      requireEnabledManagedAncestors(tenant, owner, root, subtree);
    }
    Instant timestamp = Instant.now();
    boolean changed = false;
    for (TenantResource resource : subtree) {
      if (resource.isEnabled() != enabled) {
        resource.setEnabled(enabled);
        resource.setUpdatedAt(timestamp);
        changed = true;
      }
    }
    if (changed) {
      entityManager.flush();
      authorizationVersions.refreshTenant(tenant);
    }
    return view(root);
  }

  private List<TenantResource> subtree(
      final String tenantId,
      final String owner,
      final TenantResource root
  ) {
    List<TenantResource> resources = new ArrayList<>();
    resources.add(root);
    Set<String> seen = new LinkedHashSet<>();
    seen.add(root.getId());
    List<String> parents = List.of(root.getId());
    while (!parents.isEmpty()) {
      List<TenantResource> children = new ArrayList<>();
      for (int start = 0; start < parents.size(); start += 100) {
        children.addAll(entityManager.createQuery(
                "from TenantResource resource where resource.tenantId = :tenant "
                    + "and resource.managedOwner = :owner and resource.parentId in :parents",
                TenantResource.class
            )
            .setParameter("tenant", tenantId)
            .setParameter("owner", owner)
            .setParameter(
                "parents",
                parents.subList(start, Math.min(start + 100, parents.size()))
            )
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList());
      }
      List<String> next = new ArrayList<>();
      for (TenantResource child : children) {
        if (!seen.add(child.getId())) {
          continue;
        }
        resources.add(child);
        next.add(child.getId());
        if (resources.size() > MAX_SUBTREE_SIZE) {
          throw failure(
              ManagedTenantResourceErrorCode.INVALID_REQUEST,
              "Managed resource subtree exceeds 5000 entries"
          );
        }
      }
      parents = next;
    }
    return resources;
  }

  private void requireEnabledManagedAncestors(
      final String tenantId,
      final String owner,
      final TenantResource root,
      final Collection<TenantResource> subtree
  ) {
    Set<String> included = subtree.stream().map(TenantResource::getId)
        .collect(java.util.stream.Collectors.toSet());
    String parentId = root.getParentId();
    Set<String> seen = new LinkedHashSet<>();
    while (parentId != null && !included.contains(parentId) && seen.add(parentId)) {
      TenantResource parent = entityManager.find(
          TenantResource.class,
          new TenantResourceKey(tenantId, parentId),
          LockModeType.PESSIMISTIC_READ
      );
      if (parent == null || !owner.equals(parent.getManagedOwner())) {
        return;
      }
      if (!parent.isEnabled()) {
        throw failure(
            ManagedTenantResourceErrorCode.PARENT_NOT_FOUND,
            "Managed parent must be active before its child"
        );
      }
      parentId = parent.getParentId();
    }
  }

  private Declaration validate(
      final String owner,
      final ManagedTenantResourceCommand command
  ) {
    if (command == null || !TYPES.contains(command.type())) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, "Unsupported resource type");
    }
    String tenantId = required(command.tenantId(), 36, "tenantId");
    String managedKey = requiredKey(command.managedKey());
    String resourceCode = required(command.resourceCode(), 120, "resourceCode");
    if (!CODE.matcher(resourceCode).matches() || !resourceCode.startsWith(owner + ".")) {
      throw failure(
          ManagedTenantResourceErrorCode.INVALID_REQUEST,
          "resourceCode must belong to the authenticated owner namespace"
      );
    }
    String name = required(command.name(), 100, "name");
    String parentManagedKey = optional(command.parentManagedKey(), 160, "parentManagedKey");
    String platformParentCode = optional(command.platformParentCode(), 120, "platformParentCode");
    String hostResourceCode = optional(command.hostResourceCode(), 120, "hostResourceCode");
    String path = optional(command.path(), 200, "path");
    String icon = optional(command.icon(), 100, "icon");
    String description = optional(command.description(), 255, "description");
    if (parentManagedKey != null && !KEY.matcher(parentManagedKey).matches()) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, "parentManagedKey is invalid");
    }
    if (parentManagedKey != null && platformParentCode != null) {
      throw failure(
          ManagedTenantResourceErrorCode.INVALID_REQUEST,
          "Only one managed or platform parent may be declared"
      );
    }
    if (managedKey.equals(parentManagedKey)) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, "A resource cannot own itself");
    }
    if (command.type() == ResourceType.PAGE) {
      if (hostResourceCode == null || path == null || !PATH.matcher(path).matches()) {
        throw failure(
            ManagedTenantResourceErrorCode.INVALID_REQUEST,
            "Managed pages require a trusted host and a normalized internal path"
        );
      }
    } else if (hostResourceCode != null || path != null) {
      throw failure(
          ManagedTenantResourceErrorCode.INVALID_REQUEST,
          "Only managed pages may declare a host or path"
      );
    }
    if ((command.type() == ResourceType.ACTION || command.type() == ResourceType.FEATURE)
        && parentManagedKey == null) {
      throw failure(
          ManagedTenantResourceErrorCode.INVALID_REQUEST,
          "Managed actions and features require a managed parent"
      );
    }
    return new Declaration(
        tenantId,
        managedKey,
        resourceCode,
        name,
        command.type(),
        parentManagedKey,
        platformParentCode,
        hostResourceCode,
        path,
        icon,
        command.sort(),
        description
    );
  }

  private TenantResource resolveParent(final String owner, final Declaration declaration) {
    if (declaration.parentManagedKey() != null) {
      TenantResource parent = find(
          owner,
          declaration.tenantId(),
          declaration.parentManagedKey(),
          true
      );
      if (parent == null || !validParent(parent.getType(), declaration.type())) {
        throw failure(
            ManagedTenantResourceErrorCode.PARENT_NOT_FOUND,
            "Managed parent does not exist or cannot contain this resource"
        );
      }
      return parent;
    }
    if (declaration.platformParentCode() == null) {
      return null;
    }
    Resource platformParent = platform(declaration.platformParentCode());
    if (platformParent == null || !activeTenantResource(platformParent)
        || !validParent(platformParent.getType(), declaration.type())) {
      throw failure(
          ManagedTenantResourceErrorCode.PARENT_NOT_FOUND,
          "Platform parent does not exist or cannot contain this resource"
      );
    }
    TenantResource reference = new TenantResource();
    reference.setId(platformParent.getId());
    reference.setType(platformParent.getType());
    return reference;
  }

  private Resource resolveHost(final Declaration declaration) {
    if (declaration.hostResourceCode() == null) {
      return null;
    }
    Resource host = platform(declaration.hostResourceCode());
    if (host == null || !activeTenantResource(host) || host.getType() != ResourceType.PAGE
        || host.getComponent() == null || host.getComponent().isBlank()) {
      throw failure(
          ManagedTenantResourceErrorCode.HOST_NOT_FOUND,
          "Managed page host is unavailable"
      );
    }
    return host;
  }

  private Resource platform(final String code) {
    Collection<Resource> resources = platformResources.findAllByCodes(List.of(code));
    return resources == null ? null : resources.stream()
        .filter(resource -> code.equals(resource.getCode()))
        .findFirst()
        .orElse(null);
  }

  private static boolean activeTenantResource(final Resource resource) {
    return resource.getDeletedAt() == null
        && !Boolean.TRUE.equals(resource.getDisabled())
        && ResourceScopePolicy.effectiveScopes(resource.getScopeTypes())
            .contains(ResourceScopeType.TENANT);
  }

  private static boolean validParent(
      final ResourceType parent,
      final ResourceType child
  ) {
    if (child == ResourceType.ACTION || child == ResourceType.FEATURE) {
      return parent == ResourceType.PAGE || parent == ResourceType.FEATURE;
    }
    return parent == ResourceType.GROUP || parent == ResourceType.MODULE;
  }

  private void requireImmutableDeclaration(
      final TenantResource existing,
      final TenantResource parent,
      final Resource host,
      final Declaration declaration
  ) {
    String expectedParentId = parentId(parent, declaration);
    String expectedHostId = host == null ? null : host.getId();
    if (!Objects.equals(existing.getCode(), declaration.resourceCode())
        || existing.getType() != declaration.type()
        || !Objects.equals(existing.getParentId(), expectedParentId)
        || !Objects.equals(existing.getManagedHostResourceId(), expectedHostId)
        || !Objects.equals(existing.getPath(), declaration.path())) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
          "Managed resource identity cannot be rebound to another code, parent, host or path"
      );
    }
  }

  private void refreshPresentation(
      final TenantResource resource,
      final Resource host,
      final Declaration declaration
  ) {
    resource.setName(declaration.name());
    resource.setIcon(declaration.icon());
    resource.setSort(declaration.sort());
    resource.setDescription(declaration.description());
    resource.setComponent(host == null ? null : host.getComponent());
    resource.setRouteKind(declaration.type() == ResourceType.PAGE ? "item" : null);
    resource.setUpdatedAt(Instant.now());
  }

  private void requireCodeAvailable(final String tenantId, final String resourceCode) {
    boolean present = !platformResources.findAllByCodes(List.of(resourceCode)).isEmpty()
        || !entityManager.createQuery(
            "select resource.id from TenantResource resource "
                + "where resource.tenantId = :tenant and resource.code = :code",
            String.class
        )
        .setParameter("tenant", tenantId)
        .setParameter("code", resourceCode)
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
    if (present) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
          "Resource code is already owned inside this tenant"
      );
    }
  }

  private void requirePathAvailable(
      final String tenantId,
      final String path,
      final String excludedId
  ) {
    if (path == null) {
      return;
    }
    boolean present = !platformResources.findAllByPaths(List.of(path)).isEmpty()
        || !entityManager.createQuery(
            "select resource.id from TenantResource resource "
                + "where resource.tenantId = :tenant and resource.path = :path "
                + "and (:excluded is null or resource.id <> :excluded)",
            String.class
        )
        .setParameter("tenant", tenantId)
        .setParameter("path", path)
        .setParameter("excluded", excludedId)
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
    if (present) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
          "Resource path is already owned inside this tenant"
      );
    }
  }

  private TenantResource find(
      final String owner,
      final String tenantId,
      final String managedKey,
      final boolean lock
  ) {
    var query = entityManager.createQuery(
            "from TenantResource resource where resource.tenantId = :tenant "
                + "and resource.managedOwner = :owner and resource.managedKey = :key",
            TenantResource.class
        )
        .setParameter("tenant", tenantId)
        .setParameter("owner", owner)
        .setParameter("key", managedKey)
        .setMaxResults(1);
    if (lock) {
      query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
    }
    return query.getResultStream().findFirst().orElse(null);
  }

  private void lockTenant(final String tenantId) {
    Tenant tenant = entityManager.find(Tenant.class, tenantId, LockModeType.PESSIMISTIC_WRITE);
    if (tenant == null) {
      throw failure(
          ManagedTenantResourceErrorCode.TENANT_NOT_FOUND,
          "Tenant does not exist"
      );
    }
  }

  private static String parentId(
      final TenantResource parent,
      final Declaration declaration
  ) {
    return parent == null ? null : parent.getId();
  }

  private static boolean isGrantable(final ResourceType type) {
    return type == ResourceType.PAGE || type == ResourceType.ACTION || type == ResourceType.FEATURE;
  }

  private static String stableId(
      final String tenantId,
      final String owner,
      final String managedKey
  ) {
    return UUID.nameUUIDFromBytes(
        (tenantId + "\u0000" + owner + "\u0000" + managedKey)
            .getBytes(StandardCharsets.UTF_8)
    ).toString();
  }

  private static ManagedTenantResourceView view(final TenantResource resource) {
    return new ManagedTenantResourceView(
        resource.getId(),
        resource.getTenantId(),
        resource.getManagedOwner(),
        resource.getManagedKey(),
        resource.getCode(),
        resource.getName(),
        resource.getType(),
        resource.getParentId(),
        resource.getPath(),
        resource.getComponent(),
        resource.getRouteKind(),
        resource.isEnabled(),
        resource.isGrantable(),
        resource.getUpdatedAt()
    );
  }

  private static String requiredKey(final String value) {
    String result = required(value, 160, "managedKey");
    if (!KEY.matcher(result).matches()) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, "managedKey is invalid");
    }
    return result;
  }

  private static String required(
      final String value,
      final int maximum,
      final String field
  ) {
    String result = optional(value, maximum, field);
    if (result == null) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, field + " is required");
    }
    return result;
  }

  private static String optional(
      final String value,
      final int maximum,
      final String field
  ) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String result = value.trim();
    if (result.length() > maximum) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, field + " is too long");
    }
    return result;
  }

  private static ManagedTenantResourceException failure(
      final ManagedTenantResourceErrorCode code,
      final String message
  ) {
    return new ManagedTenantResourceException(code, message);
  }

  private static ManagedTenantResourceException failure(
      final ManagedTenantResourceErrorCode code,
      final String message,
      final Throwable cause
  ) {
    return new ManagedTenantResourceException(code, message, cause);
  }

  private record Declaration(
      String tenantId,
      String managedKey,
      String resourceCode,
      String name,
      ResourceType type,
      String parentManagedKey,
      String platformParentCode,
      String hostResourceCode,
      String path,
      String icon,
      Integer sort,
      String description
  ) {}
}
