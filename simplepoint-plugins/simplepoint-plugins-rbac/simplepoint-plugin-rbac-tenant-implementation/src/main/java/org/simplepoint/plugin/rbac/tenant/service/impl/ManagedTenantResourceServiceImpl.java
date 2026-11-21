package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Collection;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.platform.resource.ApplicationCatalogRemovalEvents;
import org.simplepoint.plugin.rbac.tenant.api.entity.RetiredApplicationResource;

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
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.error.ManagedTenantResourceErrorCode;
import org.simplepoint.plugin.rbac.tenant.api.error.ManagedTenantResourceException;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedTenantResourceCommand;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedTenantResourceView;
import org.simplepoint.plugin.rbac.tenant.api.service.ManagedTenantResourceService;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable tenant resource lifecycle owned by authenticated platform services. */
@Service
@RemoteProvider
@ConditionalOnProperty(
    prefix = "simplepoint.rbac.managed-resource",
    name = "enabled",
    havingValue = "true")
public class ManagedTenantResourceServiceImpl implements ManagedTenantResourceService {

  private static final int MAX_SUBTREE_SIZE = 5_000;
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}");
  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}");
  private static final Pattern PATH = Pattern.compile("^/(?!/)[A-Za-z0-9/_-]+$");
  private static final Set<ResourceType> TYPES =
      Set.of(
          ResourceType.GROUP,
          ResourceType.MODULE,
          ResourceType.PAGE,
          ResourceType.ACTION,
          ResourceType.FEATURE);

  private final EntityManager entityManager;
  private final ResourceAuthorizationVersionService authorizationVersions;
  private final ManagedTenantResourceCallerGuard callerGuard;

  /** Coordinates managed resources with caller ownership and authorization invalidation. */
  public ManagedTenantResourceServiceImpl(
      final EntityManager entityManager,
      final ResourceAuthorizationVersionService authorizationVersions,
      final ManagedTenantResourceCallerGuard callerGuard) {
    this.entityManager = entityManager;
    this.authorizationVersions = authorizationVersions;
    this.callerGuard = callerGuard;
  }

  @Override
  @Transactional(readOnly = true)
  public org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationDirectoryPage navigationDirectories(
      String tenantId, String rootKey, String keyword, int offset, int limit) {
    String owner = callerGuard.requireOwner();
    String tenant = required(tenantId, 36, "tenantId");
    requiredKey(rootKey);
    if (offset < 0 || offset > 100000 || limit < 1 || limit > 100) throw ManagedNavigationPlan.invalid();
    String search = optional(keyword, 100, "keyword");
    String pattern = "%" + (search == null ? "" : search.toLowerCase(java.util.Locale.ROOT)
        .replace("!", "!!").replace("%", "!%").replace("_", "!_")) + "%";
    var result = new java.util.ArrayList<org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationDirectory>();
    long eligible = 0;
    // Complete structural-directory selection is bounded. Apply availability before offset/count,
    // so unavailable ancestors cannot create empty pages hiding otherwise eligible directories.
    for (int cursor = 0; cursor <= MAX_SUBTREE_SIZE; cursor += 100) {
      var candidates = entityManager.createQuery("from TenantResource r where r.tenantId = :tenant"
          + " and r.type in ('GROUP','MODULE') and (r.managedOwner is null or r.managedOwner <> :owner"
          + " or (r.managedKey <> :root and (r.navigationRootKey is null or r.navigationRootKey <> :root)))"
          + " and (lower(r.name) like :pattern escape '!' or r.code = :exact)"
          + " order by case when r.code = :exact then 0 else 1 end, r.name, r.id", TenantResource.class)
          .setParameter("tenant", tenant).setParameter("owner", owner).setParameter("root", rootKey)
          .setParameter("pattern", pattern).setParameter("exact", search == null ? "" : search)
          .setFirstResult(cursor).setMaxResults(100).getResultList();
      if (cursor == MAX_SUBTREE_SIZE && !candidates.isEmpty()) throw ManagedNavigationPlan.invalid();
      var available = new TenantResourceReadQuery(entityManager, tenant).selectedResources(candidates.stream().map(TenantResource::getCode).toList())
          .stream().map(TenantResource::getId).collect(java.util.stream.Collectors.toSet());
      for (var item : candidates) if (available.contains(item.getId())) {
        if (eligible >= offset && result.size() < limit) result.add(new org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationDirectory(
            item.getId(), item.getCode(), item.getName(), item.getNameLocalization()));
        eligible++;
      }
      if (candidates.size() < 100) break;
    }
    return new org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationDirectoryPage(result, eligible);
  }

  @Override
  @Transactional
  public long applyNavigation(org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationCommand command) {
    String owner = callerGuard.requireOwner();
    var plan = new ManagedNavigationPlan(command);
    String tenant = required(command.tenantId(), 36, "tenantId");
    requiredKey(command.rootKey());
    lockTenant(tenant);
    requireManagedKeyAvailable(owner, tenant, command.rootKey());
    var root = find(owner, tenant, command.rootKey(), true);
    long revision = root == null ? 0 : root.getNavigationRevision();
    if (revision == command.revision() && Objects.equals(root.getNavigationDigest(), plan.digest)) return revision;
    if (command.revision() != revision + 1) throw failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT, "Navigation revision changed");
    TenantResource mount = command.tenantParentCode() == null ? null : tenantReference(tenant, command.tenantParentCode());
    if (command.tenantParentCode() != null && (mount == null || !validParent(mount.getType(), ResourceType.GROUP)))
      throw failure(ManagedTenantResourceErrorCode.PARENT_NOT_FOUND, "Tenant directory is unavailable");
    // A parent can be a descendant from an older layout; checking only the new graph misses this cycle.
    TenantResource ancestor = mount;
    var seen = new java.util.HashSet<String>();
    while (ancestor != null) {
      if (!seen.add(ancestor.getId()) || seen.size() > 64
          || owner.equals(ancestor.getManagedOwner()) && (command.rootKey().equals(ancestor.getManagedKey())
          || command.rootKey().equals(ancestor.getNavigationRootKey()))) throw ManagedNavigationPlan.invalid();
      ancestor = ancestor.getParentId() == null ? null : entityManager.find(TenantResource.class, new TenantResourceKey(tenant, ancestor.getParentId()));
    }
    var nodes = new java.util.LinkedHashMap<String, TenantResource>();
    for (var directory : plan.ordered) {
      var declaration = validate(owner, new ManagedTenantResourceCommand(tenant, directory.managedKey(), directory.resourceCode(), directory.name(),
          ResourceType.GROUP, directory.parentKey(), null, null, null, directory.icon(), directory.sort(), null));
      var item = find(owner, tenant, directory.managedKey(), true);
      if (item == null) {
        requireCodeAvailable(tenant, directory.resourceCode());
        item = new TenantResource(); item.setTenantId(tenant); item.setId(stableId(tenant, owner, directory.managedKey()));
        item.setManagedOwner(owner); item.setManagedKey(directory.managedKey()); item.setCode(directory.resourceCode());
        item.setType(ResourceType.GROUP); item.setName(directory.name()); item.setEnabled(true); item.setGrantable(false);
        entityManager.persist(item);
      } else if (item.getType() != ResourceType.GROUP || !item.getCode().equals(directory.resourceCode())
          || item.getNavigationRootKey() != null && !command.rootKey().equals(item.getNavigationRootKey())) throw ManagedNavigationPlan.invalid();
      // A cancellation before the first registration left a disabled identity reservation only.
      if (item.getNavigationRootKey() == null && item.getNavigationDigest() != null && item.getNavigationDigest().startsWith("#")
          && !item.isManualDisabled() && !item.isFormallyOffline()) item.setEnabled(true);
      item.setParentId(directory.parentKey() == null ? parentId(mount) : nodes.get(directory.parentKey()).getId());
      refreshPresentation(item, null, declaration);
      item.setNameLocalization(directory.nameLocalization()); item.setNavigationRootKey(command.rootKey());
      nodes.put(directory.managedKey(), item);
    }
    for (var page : command.pages()) {
      requiredKey(page.managedKey());
      var item = find(owner, tenant, page.managedKey(), true);
      // Unpublished pages are still part of the owner's complete plan. Publication prepares them later.
      if (item == null) continue;
      if (item.getType() != ResourceType.PAGE || item.getNavigationRootKey() != null
          && !command.rootKey().equals(item.getNavigationRootKey())) throw ManagedNavigationPlan.invalid();
      item.setParentId(nodes.get(page.parentKey()).getId()); item.setName(page.name());
      item.setNameLocalization(page.nameLocalization()); item.setNavigationRootKey(command.rootKey()); item.setUpdatedAt(Instant.now());
    }
    var retained = entityManager.createQuery("from TenantResource r where r.tenantId = :tenant and r.managedOwner = :owner"
        + " and r.navigationRootKey = :root", TenantResource.class).setParameter("tenant", tenant).setParameter("owner", owner)
        .setParameter("root", command.rootKey()).setMaxResults(MAX_SUBTREE_SIZE + 1).getResultList();
    var pageKeys = command.pages().stream().map(org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationCommand.Page::managedKey).collect(java.util.stream.Collectors.toSet());
    if (retained.size() > MAX_SUBTREE_SIZE || retained.stream().anyMatch(item -> item.getType() == ResourceType.PAGE && !pageKeys.contains(item.getManagedKey())))
      throw ManagedNavigationPlan.invalid();
    var removed = retained.stream().filter(item -> item.getType() == ResourceType.GROUP && !nodes.containsKey(item.getManagedKey())).toList();
    var removedIds = removed.stream().map(TenantResource::getId).collect(java.util.stream.Collectors.toSet());
    for (var item : removed) {
      var children = entityManager.createQuery("select r.id from TenantResource r where r.tenantId = :tenant and r.parentId = :parent", String.class)
          .setParameter("tenant", tenant).setParameter("parent", item.getId()).getResultList();
      if (children.stream().anyMatch(id -> !removedIds.contains(id))) throw ManagedNavigationPlan.invalid();
      entityManager.remove(item);
    }
    root = nodes.get(command.rootKey()); root.setNavigationRevision(command.revision()); root.setNavigationDigest(plan.digest);
    entityManager.flush(); authorizationVersions.refreshTenant(tenant);
    return command.revision();
  }

  @Override
  @Transactional
  public boolean cancelNavigation(org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationCommand command) {
    String owner = callerGuard.requireOwner(); var plan = new ManagedNavigationPlan(command);
    String tenant = required(command.tenantId(), 36, "tenantId"); lockTenant(tenant);
    requireManagedKeyAvailable(owner, tenant, command.rootKey());
    var root = find(owner, tenant, requiredKey(command.rootKey()), true);
    long revision = root == null ? 0 : root.getNavigationRevision();
    // The durable cancellation decision fences requests still in flight at the remote boundary.
    if (revision == command.revision()) {
      if (Objects.equals(root.getNavigationDigest(), plan.digest)) return false;
      if (Objects.equals(root.getNavigationDigest(), "!" + plan.digest.substring(1)) || Objects.equals(root.getNavigationDigest(), "#" + plan.digest.substring(1))) return true;
    }
    if (revision + 1 != command.revision()) throw failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT, "Navigation revision changed");
    boolean placeholder = root == null;
    if (root == null) {
      var declaration = plan.ordered.getFirst();
      validate(owner, new ManagedTenantResourceCommand(tenant, declaration.managedKey(), declaration.resourceCode(), declaration.name(), ResourceType.GROUP,
          null, null, null, null, declaration.icon(), declaration.sort(), null));
      requireCodeAvailable(tenant, declaration.resourceCode());
      root = new TenantResource(); root.setTenantId(tenant); root.setId(stableId(tenant, owner, command.rootKey()));
      root.setManagedOwner(owner); root.setManagedKey(command.rootKey()); root.setCode(declaration.resourceCode());
      root.setName(declaration.name()); root.setType(ResourceType.GROUP); root.setEnabled(false); root.setGrantable(false);
      entityManager.persist(root);
    }
    root.setNavigationRevision(command.revision()); root.setNavigationDigest((placeholder ? "#" : "!") + plan.digest.substring(1));
    entityManager.flush(); return true;
  }

  private void requireNavigationFence(String owner, TenantResource existing, ManagedTenantResourceCommand command) {
    if (command.navigationRootKey() == null) {
      if (command.navigationRevision() != null || existing != null && existing.getNavigationRootKey() != null)
        throw failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT, "Navigation declaration requires its current revision");
      return;
    }
    var root = find(owner, command.tenantId(), requiredKey(command.navigationRootKey()), true);
    var parent = command.parentManagedKey() == null ? null : find(owner, command.tenantId(), command.parentManagedKey(), true);
    if (parent == null || !command.navigationRootKey().equals(parent.getNavigationRootKey()))
      throw failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT, "Navigation parent is outside the owning application");
    if (root == null || command.navigationRevision() == null || root.getNavigationRevision() != command.navigationRevision()
        || existing != null && existing.getNavigationRootKey() != null && !existing.getNavigationRootKey().equals(command.navigationRootKey()))
      throw failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT, "Navigation declaration is stale");
  }

  @Override
  @Transactional
  public ManagedTenantResourceView prepare(final ManagedTenantResourceCommand command) {
    String owner = callerGuard.requireOwner();
    org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceOrigin.forManagedOwner(owner);
    Declaration declaration = validate(owner, command);
    lockTenant(declaration.tenantId());
    requireManagedKeyAvailable(owner, declaration.tenantId(), declaration.managedKey());
    TenantResource parent = resolveParent(owner, declaration);
    TenantResource host = resolveHost(declaration);
    TenantResource existing = find(owner, declaration.tenantId(), declaration.managedKey(), true);
    requireNavigationFence(owner, existing, command);
    ManagedNavigationPlan.label(command.name(), command.nameLocalization());
    if (existing != null) {
      requireImmutableDeclaration(existing, parent, host, declaration);
      if (command.nameLocalization() == null && existing.getNameLocalization() != null && !existing.getName().equals(command.name()))
        throw failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT, "Localized resource rename requires its complete metadata");
      refreshPresentation(existing, host, declaration);
      if (command.nameLocalization() != null) existing.setNameLocalization(org.simplepoint.api.locale.LocalizedTexts.validate(command.nameLocalization(), 100, 32));
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
    resource.setParentId(parentId(parent));
    resource.setCode(declaration.resourceCode());
    resource.setType(declaration.type());
    resource.setPath(declaration.path());
    resource.setEnabled(false);
    resource.setGrantable(isGrantable(declaration.type()));
    resource.setLocalModified(false);
    resource.setManualDisabled(false);
    refreshPresentation(resource, host, declaration);
    resource.setNameLocalization(command.nameLocalization());
    resource.setNavigationRootKey(command.navigationRootKey());
    try {
      entityManager.persist(resource);
      entityManager.flush();
    } catch (PersistenceException exception) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
          "Managed resource identity, code or path conflicts with another resource",
          exception);
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
          "Managed tenant resource does not exist");
    }
    return view(resource);
  }

  @Override
  @Transactional
  public ManagedTenantResourceView activateGeneration(
      String tenantId, String managedKey, long generation) {
    return activateWithPermissions(tenantId, managedKey, generation, null);
  }

  @Override
  @Transactional
  public ManagedTenantResourceView activateWithPermissions(
      String tenantId,
      String managedKey,
      long generation,
      org.simplepoint.core.permission.PermissionPageDescriptor permissions) {
    if (generation < 1) {
      throw failure(
          ManagedTenantResourceErrorCode.INVALID_REQUEST, "Publication generation is required");
    }
    String owner = callerGuard.requireOwner();
    lockTenant(tenantId);
    TenantResource resource = find(owner, tenantId, managedKey, true);
    if (resource == null
        || generation < resource.getLifecycleGeneration()
        || generation == resource.getLifecycleGeneration() && resource.isFormallyOffline()) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
          "Publication has been taken offline or superseded");
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
  public ManagedTenantResourceView refreshPermissions(
      String tenantId,
      String managedKey,
      long generation,
      org.simplepoint.core.permission.PermissionPageDescriptor permissions) {
    String owner = callerGuard.requireOwner();
    lockTenant(tenantId);
    TenantResource resource = find(owner, tenantId, managedKey, true);
    boolean legacy =
        resource != null
            && resource.getLifecycleGeneration() == 0
            && resource.getPermissionMetadata() == null
            && generation > 0;
    if (resource == null
        || !resource.isEnabled()
        || resource.isFormallyOffline()
        || generation < 1
        || (!legacy && generation != resource.getLifecycleGeneration())
        || permissions == null) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
          "The active publication generation has changed");
    }
    // Pre-lifecycle publications have generation zero. The trusted owner has verified its
    // active binding; adopt that generation once, without enabling nodes or touching grants.
    List<TenantResource> legacyTree = legacy ? subtree(tenantId, owner, resource) : List.of();
    if (legacyTree.stream()
        .anyMatch(item -> item.getLifecycleGeneration() != 0 || item.isFormallyOffline())) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
          "The legacy resource tree has advanced");
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

  private static void storePermissions(
      TenantResource resource,
      long generation,
      org.simplepoint.core.permission.PermissionPageDescriptor permissions) {
    if (permissions != null) {
      if (resource.getType() != ResourceType.PAGE
          || permissions.models().isEmpty()
          || permissions.models().size() > 21
          || permissions.models().stream().mapToInt(model -> model.properties().size()).sum()
              > 1000) {
        throw failure(
            ManagedTenantResourceErrorCode.INVALID_REQUEST,
            "Invalid managed page permission descriptor");
      }
      if (generation == resource.getLifecycleGeneration()
          && resource.getPermissionMetadata() != null
          && !permissions.equals(
              org.simplepoint.core.permission.PermissionPageDescriptor.fromJson(
                  resource.getPermissionMetadata()))) {
        throw failure(
            ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
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
    TenantResource root =
        find(request.managedOwner(), event.tenantId(), request.managedKey(), true);
    if (root == null
        || !root.getId().equals(event.resourceId())
        || !root.getCode().equals(request.resourceCode())) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
          "Offline request does not own this resource");
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

  /** Erases the exact offline tree and its tenant grants; unselected dependencies block removal. */
  @Transactional
  public void removeApplicationCatalog(EventMessage event) {
    var pages = ApplicationCatalogRemovalEvents.pages(event);
    String tenant = event.tenantId();
    String owner = "app";
    String rootKey = ApplicationCatalogRemovalEvents.rootKey(event.resourceId());
    var pageKeys = pages.stream().map(ApplicationCatalogRemovalEvents::pageKey)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var knownKeys = new LinkedHashSet<>(pageKeys);
    knownKeys.add(rootKey);
    lockTenant(tenant);
    var receipt = entityManager.find(RetiredApplicationResource.class, stableId(tenant, owner, rootKey));
    if (receipt != null) {
      if (!receipt.getOperationId().equals(event.operationId())) throw removalConflict();
      return;
    }
    var root = find(owner, tenant, rootKey, true);
    if (root == null ? event.generation() != 0 : root.getNavigationRevision() != event.generation()) {
      throw removalConflict();
    }
    var roots = entityManager.createQuery("from TenantResource r where r.tenantId = :tenant"
            + " and r.managedOwner = :owner and (r.managedKey in :keys or r.navigationRootKey = :root)",
            TenantResource.class)
        .setParameter("tenant", tenant).setParameter("owner", owner).setParameter("keys", knownKeys)
        .setParameter("root", rootKey).setMaxResults(MAX_SUBTREE_SIZE + 1)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    if (roots.size() > MAX_SUBTREE_SIZE) throw removalConflict();
    roots.stream().filter(r -> ResourceType.PAGE.equals(r.getType())
        && rootKey.equals(r.getNavigationRootKey())).map(TenantResource::getManagedKey).forEach(pageKeys::add);
    var resources = subtree(tenant, owner, roots);
    for (var item : resources) {
      String key = item.getManagedKey();
      if (ResourceType.PAGE.equals(item.getType()) && (!pageKeys.contains(key)
          || !("/app/runtime/" + event.resourceId() + "/" + key.substring("page:".length())).equals(item.getPath()))) {
        throw removalConflict();
      }
      if (!(rootKey.equals(key) || rootKey.equals(item.getNavigationRootKey()) || pageKeys.contains(key)
          || ResourceType.ACTION.equals(item.getType()) && pageKeys.stream().anyMatch(page -> key.startsWith(page + ":")))) {
        throw removalConflict();
      }
      if (!ResourceType.GROUP.equals(item.getType())
          && (item.isEnabled() || item.getLifecycleGeneration() > 0 && !item.isFormallyOffline())) {
        throw removalConflict();
      }
      knownKeys.add(key);
    }
    if (!resources.isEmpty()) {
      var owned = resources.stream().collect(java.util.stream.Collectors.toMap(TenantResource::getId, item -> item));
      var ids = owned.keySet();
      var foreign = entityManager.createQuery("select r.id from TenantResource r where r.tenantId = :tenant"
              + " and r.id not in :ids and (r.parentId in :ids or r.targetResourceId in :ids"
              + " or r.managedHostResourceId in :ids)", String.class)
          .setParameter("tenant", tenant).setParameter("ids", ids).setMaxResults(1).getResultList();
      if (!foreign.isEmpty()) throw removalConflict();
      var codes = resources.stream().map(TenantResource::getCode).toList();
      entityManager.createQuery("delete from RoleResourceGrant g where g.tenantId = :tenant"
              + " and g.resourceCode in :codes")
          .setParameter("tenant", tenant).setParameter("codes", codes).executeUpdate();
      resources.stream().sorted(java.util.Comparator
              .comparingInt((TenantResource item) -> removalDepth(item, owned)).reversed())
          .forEach(entityManager::remove);
    }
    for (String key : knownKeys) {
      var marker = new RetiredApplicationResource();
      marker.setId(stableId(tenant, owner, key));
      marker.setOperationId(event.operationId());
      entityManager.persist(marker);
    }
    entityManager.flush();
    authorizationVersions.refreshTenant(tenant);
  }

  private void requireManagedKeyAvailable(String owner, String tenant, String key) {
    if ("app".equals(owner) && entityManager.find(RetiredApplicationResource.class, stableId(tenant, owner, key)) != null) {
      throw removalConflict();
    }
  }

  private static int removalDepth(TenantResource item, java.util.Map<String, TenantResource> owned) {
    int depth = 0;
    for (var parent = item.getParentId(); parent != null && owned.containsKey(parent);
        parent = owned.get(parent).getParentId()) {
      if (++depth > 64) throw removalConflict();
    }
    return depth;
  }

  private static ManagedTenantResourceException removalConflict() {
    return failure(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
        "The application catalog has active, changed or shared resources");
  }

  private void emRevoke(String tenantId, List<String> codes, Instant now) {
    // Keep shared data/field policies and grant history; remove only effective assignments.
    entityManager
        .createQuery(
            "update RoleResourceGrant g set g.deletedAt = :now "
                + "where g.tenantId = :tenant and g.resourceCode in :codes and g.deletedAt is null")
        .setParameter("now", now)
        .setParameter("tenant", tenantId)
        .setParameter("codes", codes)
        .executeUpdate();
  }

  private ManagedTenantResourceView changeState(
      final String tenantId, final String managedKey, final boolean enabled) {
    String owner = callerGuard.requireOwner();
    String tenant = required(tenantId, 36, "tenantId");
    String key = requiredKey(managedKey);
    lockTenant(tenant);
    TenantResource root = find(owner, tenant, key, true);
    if (root == null) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_NOT_FOUND,
          "Managed tenant resource does not exist");
    }
    List<TenantResource> subtree = subtree(tenant, owner, root);
    if (enabled) {
      if (subtree.stream().anyMatch(TenantResource::isFormallyOffline)) {
        throw failure(
            ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
            "Formal offline requires a newer publication generation");
      }
      requireEnabledManagedAncestors(tenant, owner, root, subtree);
      // A previously prepared page must revalidate its tenant-local host before activation.
      var hosts =
          subtree.stream()
              .map(TenantResource::getManagedHostResourceId)
              .filter(Objects::nonNull)
              .collect(java.util.stream.Collectors.toSet());
      if (!hosts.isEmpty()
          && !new TenantResourceReadQuery(entityManager, tenant)
              .eligibleHostIds(hosts)
              .containsAll(hosts)) {
        throw failure(
            ManagedTenantResourceErrorCode.HOST_NOT_FOUND,
            "Managed page host is unavailable in this tenant");
      }
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
      final String tenantId, final String owner, final TenantResource root) {
    return subtree(tenantId, owner, List.of(root));
  }

  private List<TenantResource> subtree(
      final String tenantId, final String owner, final Collection<TenantResource> roots) {
    List<TenantResource> resources = new ArrayList<>(roots);
    Set<String> seen = roots.stream().map(TenantResource::getId)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<String> parents = List.copyOf(seen);
    while (!parents.isEmpty()) {
      List<TenantResource> children = new ArrayList<>();
      for (int start = 0; start < parents.size(); start += 100) {
        children.addAll(
            entityManager
                .createQuery(
                    "from TenantResource resource where resource.tenantId = :tenant "
                        + "and resource.managedOwner = :owner and resource.parentId in :parents",
                    TenantResource.class)
                .setParameter("tenant", tenantId)
                .setParameter("owner", owner)
                .setParameter(
                    "parents", parents.subList(start, Math.min(start + 100, parents.size())))
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
              "Managed resource subtree exceeds 5000 entries");
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
      final Collection<TenantResource> subtree) {
    Set<String> included =
        subtree.stream().map(TenantResource::getId).collect(java.util.stream.Collectors.toSet());
    String parentId = root.getParentId();
    Set<String> seen = new LinkedHashSet<>();
    while (parentId != null && !included.contains(parentId) && seen.add(parentId)) {
      TenantResource parent =
          entityManager.find(
              TenantResource.class,
              new TenantResourceKey(tenantId, parentId),
              LockModeType.PESSIMISTIC_READ);
      if (parent == null) {
        throw failure(
            ManagedTenantResourceErrorCode.PARENT_NOT_FOUND, "Tenant parent is unavailable");
      }
      if (!owner.equals(parent.getManagedOwner())) {
        if (tenantReference(tenantId, parent.getCode()) == null) {
          throw failure(
              ManagedTenantResourceErrorCode.PARENT_NOT_FOUND, "Tenant parent is unavailable");
        }
        return;
      }
      if (!parent.isEnabled()
          || parent.isManualDisabled()
          || parent.isFormallyOffline()
          || !parent.isSourceAvailable()) {
        throw failure(
            ManagedTenantResourceErrorCode.PARENT_NOT_FOUND,
            "Managed parent must be active before its child");
      }
      parentId = parent.getParentId();
    }
    if (parentId != null) {
      throw failure(
          ManagedTenantResourceErrorCode.PARENT_NOT_FOUND, "Managed parent hierarchy is cyclic");
    }
  }

  private Declaration validate(final String owner, final ManagedTenantResourceCommand command) {
    if (command == null || !TYPES.contains(command.type())) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, "Unsupported resource type");
    }
    final String tenantId = required(command.tenantId(), 36, "tenantId");
    final String managedKey = requiredKey(command.managedKey());
    String resourceCode = required(command.resourceCode(), 120, "resourceCode");
    if (!CODE.matcher(resourceCode).matches() || !resourceCode.startsWith(owner + ".")) {
      throw failure(
          ManagedTenantResourceErrorCode.INVALID_REQUEST,
          "resourceCode must belong to the authenticated owner namespace");
    }
    final String name = required(command.name(), 100, "name");
    String parentManagedKey = optional(command.parentManagedKey(), 160, "parentManagedKey");
    String platformParentCode = optional(command.platformParentCode(), 120, "platformParentCode");
    final String hostResourceCode = optional(command.hostResourceCode(), 120, "hostResourceCode");
    final String path = optional(command.path(), 200, "path");
    final String icon = optional(command.icon(), 100, "icon");
    final String description = optional(command.description(), 255, "description");
    if (parentManagedKey != null && !KEY.matcher(parentManagedKey).matches()) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, "parentManagedKey is invalid");
    }
    if (parentManagedKey != null && platformParentCode != null) {
      throw failure(
          ManagedTenantResourceErrorCode.INVALID_REQUEST,
          "Only one managed or platform parent may be declared");
    }
    if (managedKey.equals(parentManagedKey)) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, "A resource cannot own itself");
    }
    if (command.type() == ResourceType.PAGE) {
      if (hostResourceCode == null || path == null || !PATH.matcher(path).matches()) {
        throw failure(
            ManagedTenantResourceErrorCode.INVALID_REQUEST,
            "Managed pages require a trusted host and a normalized internal path");
      }
    } else if (hostResourceCode != null || path != null) {
      throw failure(
          ManagedTenantResourceErrorCode.INVALID_REQUEST,
          "Only managed pages may declare a host or path");
    }
    if ((command.type() == ResourceType.ACTION || command.type() == ResourceType.FEATURE)
        && parentManagedKey == null) {
      throw failure(
          ManagedTenantResourceErrorCode.INVALID_REQUEST,
          "Managed actions and features require a managed parent");
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
        description);
  }

  private TenantResource resolveParent(final String owner, final Declaration declaration) {
    if (declaration.parentManagedKey() != null) {
      TenantResource parent =
          find(owner, declaration.tenantId(), declaration.parentManagedKey(), true);
      if (parent == null || !validParent(parent.getType(), declaration.type())) {
        throw failure(
            ManagedTenantResourceErrorCode.PARENT_NOT_FOUND,
            "Managed parent does not exist or cannot contain this resource");
      }
      return parent;
    }
    if (declaration.platformParentCode() == null) {
      return null;
    }
    TenantResource parent =
        tenantReference(declaration.tenantId(), declaration.platformParentCode());
    if (parent == null
        || !parent.isPlatformSource()
        || !validParent(parent.getType(), declaration.type())) {
      throw failure(
          ManagedTenantResourceErrorCode.PARENT_NOT_FOUND,
          "Tenant platform parent does not exist or cannot contain this resource");
    }
    return parent;
  }

  private TenantResource resolveHost(final Declaration declaration) {
    if (declaration.hostResourceCode() == null) {
      return null;
    }
    TenantResource host = tenantReference(declaration.tenantId(), declaration.hostResourceCode());
    if (host == null
        || !host.isPlatformSource()
        || host.getType() != ResourceType.PAGE
        || host.getComponent() == null
        || host.getComponent().isBlank()) {
      throw failure(
          ManagedTenantResourceErrorCode.HOST_NOT_FOUND,
          "Managed page host is unavailable in this tenant");
    }
    return host;
  }

  private TenantResource tenantReference(final String tenantId, final String code) {
    return new TenantResourceReadQuery(entityManager, tenantId)
        .selectedResources(List.of(code)).stream()
            .filter(resource -> code.equals(resource.getCode()))
            .findFirst()
            .orElse(null);
  }

  private static boolean validParent(final ResourceType parent, final ResourceType child) {
    if (child == ResourceType.ACTION || child == ResourceType.FEATURE) {
      return parent == ResourceType.PAGE || parent == ResourceType.FEATURE;
    }
    return parent == ResourceType.GROUP || parent == ResourceType.MODULE;
  }

  private void requireImmutableDeclaration(
      final TenantResource existing,
      final TenantResource parent,
      final TenantResource host,
      final Declaration declaration) {
    String expectedParentId = parentId(parent);
    String expectedHostId = host == null ? null : host.getId();
    if (!Objects.equals(existing.getCode(), declaration.resourceCode())
        || existing.getType() != declaration.type()
        || !Objects.equals(existing.getParentId(), expectedParentId)
        || !Objects.equals(existing.getManagedHostResourceId(), expectedHostId)
        || !Objects.equals(existing.getPath(), declaration.path())) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
          "Managed resource identity cannot be rebound to another code, parent, host or path");
    }
  }

  private void refreshPresentation(
      final TenantResource resource, final TenantResource host, final Declaration declaration) {
    resource.setName(declaration.name());
    resource.setIcon(declaration.icon());
    resource.setSort(declaration.sort());
    resource.setDescription(declaration.description());
    resource.setComponent(host == null ? null : host.getComponent());
    resource.setRouteKind(declaration.type() == ResourceType.PAGE ? "item" : null);
    resource.setUpdatedAt(Instant.now());
  }

  private void requireCodeAvailable(final String tenantId, final String resourceCode) {
    boolean present =
        !entityManager
            .createQuery(
                "select resource.id from TenantResource resource "
                    + "where resource.tenantId = :tenant and resource.code = :code",
                String.class)
            .setParameter("tenant", tenantId)
            .setParameter("code", resourceCode)
            .setMaxResults(1)
            .getResultList()
            .isEmpty();
    if (present) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
          "Resource code is already owned inside this tenant");
    }
  }

  private void requirePathAvailable(
      final String tenantId, final String path, final String excludedId) {
    if (path == null) {
      return;
    }
    boolean present =
        !entityManager
            .createQuery(
                "select resource.id from TenantResource resource "
                    + "where resource.tenantId = :tenant and resource.path = :path "
                    + "and (:excluded is null or resource.id <> :excluded)",
                String.class)
            .setParameter("tenant", tenantId)
            .setParameter("path", path)
            .setParameter("excluded", excludedId)
            .setMaxResults(1)
            .getResultList()
            .isEmpty();
    if (present) {
      throw failure(
          ManagedTenantResourceErrorCode.RESOURCE_CONFLICT,
          "Resource path is already owned inside this tenant");
    }
  }

  private TenantResource find(
      final String owner, final String tenantId, final String managedKey, final boolean lock) {
    var query =
        entityManager
            .createQuery(
                "from TenantResource resource where resource.tenantId = :tenant "
                    + "and resource.managedOwner = :owner and resource.managedKey = :key",
                TenantResource.class)
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
    var tenants =
        entityManager
            .createQuery(
                "select t.id from Tenant t where t.id = :tenant and t.deletedAt is null",
                String.class)
            .setParameter("tenant", tenantId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setMaxResults(1)
            .getResultList();
    if (tenants.isEmpty()) {
      throw failure(ManagedTenantResourceErrorCode.TENANT_NOT_FOUND, "Tenant does not exist");
    }
    new TenantResourceReadQuery(entityManager, tenantId).requireReady();
  }

  private static String parentId(final TenantResource parent) {
    return parent == null ? null : parent.getId();
  }

  private static boolean isGrantable(final ResourceType type) {
    return type == ResourceType.PAGE || type == ResourceType.ACTION || type == ResourceType.FEATURE;
  }

  private static String stableId(
      final String tenantId, final String owner, final String managedKey) {
    return UUID.nameUUIDFromBytes(
            (tenantId + "\u0000" + owner + "\u0000" + managedKey).getBytes(StandardCharsets.UTF_8))
        .toString();
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
        resource.getUpdatedAt());
  }

  private static String requiredKey(final String value) {
    String result = required(value, 160, "managedKey");
    if (!KEY.matcher(result).matches()) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, "managedKey is invalid");
    }
    return result;
  }

  private static String required(final String value, final int maximum, final String field) {
    String result = optional(value, maximum, field);
    if (result == null) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, field + " is required");
    }
    return result;
  }

  private static String optional(final String value, final int maximum, final String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String result = value.trim();
    if (result.codePointCount(0, result.length()) > maximum) {
      throw failure(ManagedTenantResourceErrorCode.INVALID_REQUEST, field + " is too long");
    }
    return result;
  }

  private static ManagedTenantResourceException failure(
      final ManagedTenantResourceErrorCode code, final String message) {
    return new ManagedTenantResourceException(code, message);
  }

  private static ManagedTenantResourceException failure(
      final ManagedTenantResourceErrorCode code, final String message, final Throwable cause) {
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
      String description) {}
}
