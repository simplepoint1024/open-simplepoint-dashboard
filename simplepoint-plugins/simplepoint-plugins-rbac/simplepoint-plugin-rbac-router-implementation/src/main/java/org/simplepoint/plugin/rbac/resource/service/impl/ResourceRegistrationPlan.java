package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.simplepoint.security.resource.ResourceRegistrationException.Code.HIERARCHY_CONFLICT;
import static org.simplepoint.security.resource.ResourceRegistrationException.Code.IDENTITY_CONFLICT;
import static org.simplepoint.security.resource.ResourceRegistrationException.Code.INVALID_DECLARATION;
import static org.simplepoint.security.resource.ResourceRegistrationException.Code.OWNERSHIP_CONFLICT;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.resource.ResourceRegistrationException;
import org.springframework.beans.BeanUtils;

/** Prepares a detached complete snapshot before any resource, grant or ancestor is modified. */
record ResourceRegistrationPlan(List<Entry> entries, Set<String> codes, Set<String> retiredIds) {
  private static final int MAX_RESOURCES = 10_000;
  private static final int MAX_DEPTH = 64;

  ResourceRegistrationPlan {
    entries = List.copyOf(entries);
    codes = Set.copyOf(codes);
    retiredIds = Set.copyOf(retiredIds);
  }

  static ResourceRegistrationPlan prepare(
      String owner,
      Set<ResourceDeclaration> declarations,
      Collection<Resource> persisted,
      Consumer<Resource> normalize) {
    requireText(owner, 128);
    if (declarations == null || declarations.size() > MAX_RESOURCES) {
      throw failure(INVALID_DECLARATION);
    }
    Map<String, Resource> byCode = new HashMap<>();
    Map<String, Resource> byPath = new HashMap<>();
    Map<String, Resource> byId = new HashMap<>();
    for (Resource resource : persisted) {
      index(byCode, resource.getCode(), resource);
      index(byPath, resource.getPath(), resource);
      index(byId, resource.getId(), resource);
    }
    Map<String, Draft> drafts = flatten(owner, declarations);
    Map<String, String> claimedIds = new HashMap<>();
    for (Draft draft : drafts.values()) {
      Resource resource = draft.resource();
      Resource codeMatch = byCode.get(resource.getCode());
      Resource pathMatch = byPath.get(resource.getPath());
      requireOwner(owner, codeMatch);
      requireOwner(owner, pathMatch);
      if (codeMatch != null
          && pathMatch != null
          && !Objects.equals(codeMatch.getId(), pathMatch.getId())) {
        throw failure(IDENTITY_CONFLICT);
      }
      Resource existing = codeMatch != null ? codeMatch : pathMatch;
      if (existing != null) {
        if (claimedIds.putIfAbsent(existing.getId(), resource.getCode()) != null) {
          throw failure(IDENTITY_CONFLICT);
        }
        resource.setId(existing.getId());
        if (resource.getPermissionMetadata() == null) {
          resource.setPermissionMetadata(existing.getPermissionMetadata());
        }
      }
    }

    Map<String, List<String>> children = new HashMap<>();
    Map<String, String> parents = new HashMap<>();
    Map<String, Resource> externalParents = new HashMap<>();
    ArrayDeque<String> ready = new ArrayDeque<>();
    for (Draft draft : drafts.values()) {
      String parentCode = draft.parentCode();
      String parentId = draft.resource().getParentId();
      if (draft.parentReference() != null) {
        var reference = draft.parentReference();
        requireText(reference.owner(), 128);
        requireText(reference.code(), 120);
        if (parentCode != null || hasText(parentId)) throw failure(HIERARCHY_CONFLICT);
        parentCode = reference.code();
        if (owner.equals(reference.owner())) {
          if (!drafts.containsKey(parentCode)) throw failure(HIERARCHY_CONFLICT);
        } else {
          Resource parent = byCode.get(parentCode);
          if (parent == null || !reference.owner().equals(parent.getPluginId())) {
            throw failure(OWNERSHIP_CONFLICT);
          }
          if (!Boolean.TRUE.equals(parent.getSharedDirectory())
              || !sharedDirectory(parent)
              || Boolean.TRUE.equals(parent.getDisabled())
              || parent.getDeletedAt() != null) {
            throw failure(HIERARCHY_CONFLICT);
          }
          externalParents.put(draft.resource().getCode(), parent);
        }
      }
      if (hasText(parentId)) {
        Resource parent = byId.get(parentId);
        if (parent == null) {
          throw failure(HIERARCHY_CONFLICT);
        }
        requireOwner(owner, parent);
        String referencedCode = claimedIds.get(parentId);
        if (referencedCode == null || (parentCode != null && !parentCode.equals(referencedCode))) {
          throw failure(HIERARCHY_CONFLICT);
        }
        parentCode = referencedCode;
      }
      String code = draft.resource().getCode();
      if (parentCode == null || externalParents.containsKey(code)) {
        if (parentCode != null) parents.put(code, parentCode);
        ready.add(code);
      } else {
        parents.put(code, parentCode);
        children.computeIfAbsent(parentCode, ignored -> new ArrayList<>()).add(code);
      }
    }

    List<Entry> ordered = new ArrayList<>();
    Map<String, Integer> depths = new HashMap<>();
    while (!ready.isEmpty()) {
      String code = ready.remove();
      Resource resource = drafts.get(code).resource();
      String parentCode = parents.get(code);
      Resource externalParent = externalParents.get(code);
      int depth =
          parentCode == null
              ? 1
              : externalParent != null
                  ? ancestorDepth(externalParent, byId) + 1
                  : depths.get(parentCode) + 1;
      if (depth > MAX_DEPTH) {
        throw failure(HIERARCHY_CONFLICT);
      }
      depths.put(code, depth);
      if (parentCode != null) {
        Resource parent =
            externalParent == null ? drafts.get(parentCode).resource() : externalParent;
        if (resource.getScopeTypes() == null || resource.getScopeTypes().isEmpty()) {
          resource.setScopeTypes(parent.getScopeTypes());
        }
        if (!ResourceScopePolicy.isValidChild(parent.getScopeTypes(), resource.getScopeTypes())) {
          throw failure(HIERARCHY_CONFLICT);
        }
      }
      normalize.accept(resource);
      if (Boolean.TRUE.equals(resource.getSharedDirectory()) && !sharedDirectory(resource)) {
        throw failure(INVALID_DECLARATION);
      }
      ordered.add(
          new Entry(
              resource,
              byId.get(resource.getId()),
              parentCode,
              externalParent == null ? null : externalParent.getId()));
      ready.addAll(children.getOrDefault(code, List.of()));
    }
    if (ordered.size() != drafts.size()) {
      throw failure(HIERARCHY_CONFLICT);
    }

    validatePlannedHierarchy(ordered, byId);

    Set<String> retired = new HashSet<>();
    for (Resource existing : persisted) {
      if (owner.equals(existing.getPluginId()) && !claimedIds.containsKey(existing.getId())) {
        retired.add(existing.getId());
      }
    }
    // A snapshot may not indirectly move, remove or change scopes of foreign descendants.
    // Derive protected ancestors from the actual parent graph, independently of closure rows.
    Set<String> protectedIds = new HashSet<>();
    ArrayDeque<String> ancestors = new ArrayDeque<>();
    for (Resource existing : persisted) {
      if (!owner.equals(existing.getPluginId()) && hasText(existing.getParentId())) {
        ancestors.add(existing.getParentId());
      }
    }
    while (!ancestors.isEmpty()) {
      String id = ancestors.remove();
      Resource ancestor = byId.get(id);
      if (protectedIds.add(id) && ancestor != null && hasText(ancestor.getParentId())) {
        ancestors.add(ancestor.getParentId());
      }
    }
    if (retired.stream().anyMatch(protectedIds::contains)) {
      throw failure(OWNERSHIP_CONFLICT);
    }
    for (Entry entry : ordered) {
      Resource existing = entry.existing();
      if (existing != null && protectedIds.contains(existing.getId())) {
        Resource parent =
            entry.parentCode() == null
                ? null
                : entry.externalParentId() != null
                    ? byId.get(entry.externalParentId())
                    : drafts.get(entry.parentCode()).resource();
        String parentId = parent == null ? null : parent.getId();
        if (Boolean.TRUE.equals(existing.getSharedDirectory())
            && (!Boolean.TRUE.equals(entry.resource().getSharedDirectory())
                || !sharedDirectory(entry.resource()))) throw failure(HIERARCHY_CONFLICT);
        if ((parent != null && parentId == null)
            || !Objects.equals(existing.getParentId(), parentId)
            || !ResourceScopePolicy.effectiveScopes(existing.getScopeTypes())
                .equals(entry.resource().getScopeTypes())) {
          throw failure(HIERARCHY_CONFLICT);
        }
      }
    }
    return new ResourceRegistrationPlan(ordered, drafts.keySet(), retired);
  }

  private static Map<String, Draft> flatten(String owner, Set<ResourceDeclaration> declarations) {
    Map<String, Draft> drafts = new LinkedHashMap<>();
    Set<String> paths = new HashSet<>();
    var visited = new IdentityHashMap<ResourceDeclaration, Boolean>();
    ArrayDeque<Pending> queue = new ArrayDeque<>();
    declarations.forEach(item -> queue.add(new Pending(item, null, 1)));
    while (!queue.isEmpty()) {
      Pending pending = queue.remove();
      ResourceDeclaration item = pending.declaration();
      if (item == null
          || pending.depth() > MAX_DEPTH
          || visited.put(item, Boolean.TRUE) != null
          || drafts.size() >= MAX_RESOURCES) {
        throw failure(INVALID_DECLARATION);
      }
      requireText(item.getCode(), 120);
      if (item.getPluginId() != null && !owner.equals(item.getPluginId())) {
        throw failure(OWNERSHIP_CONFLICT);
      }
      if (item.getPath() != null) {
        requireText(item.getPath(), 200);
        if (!paths.add(item.getPath())) {
          throw failure(IDENTITY_CONFLICT);
        }
      }
      Resource resource = new Resource();
      BeanUtils.copyProperties(item, resource);
      resource.setPluginId(owner);
      if (item.getScopeTypes() != null && !item.getScopeTypes().isEmpty()) {
        if (item.getScopeTypes().stream().anyMatch(Objects::isNull)) {
          throw failure(INVALID_DECLARATION);
        }
        resource.setScopeTypes(ResourceScopePolicy.effectiveScopes(item.getScopeTypes()));
      }
      if (drafts.putIfAbsent(
              item.getCode(), new Draft(resource, pending.parentCode(), item.getParentReference()))
          != null) {
        throw failure(IDENTITY_CONFLICT);
      }
      if (item.getChildren() != null) {
        if (queue.size() + drafts.size() + item.getChildren().size() > MAX_RESOURCES) {
          throw failure(INVALID_DECLARATION);
        }
        for (ResourceDeclaration child : item.getChildren()) {
          queue.add(new Pending(child, item.getCode(), pending.depth() + 1));
        }
      }
    }
    return drafts;
  }

  private static boolean sharedDirectory(Resource resource) {
    return (resource.getType() == org.simplepoint.security.entity.ResourceType.GROUP
            || resource.getType() == org.simplepoint.security.entity.ResourceType.MODULE)
        && !Boolean.TRUE.equals(resource.getGrantable())
        && !hasText(resource.getComponent())
        && !hasText(resource.getMethod())
        && !hasText(resource.getPattern());
  }

  private static int ancestorDepth(Resource resource, Map<String, Resource> byId) {
    Set<String> seen = new HashSet<>();
    for (Resource cursor = resource; cursor != null; ) {
      if (!seen.add(cursor.getId()) || seen.size() > MAX_DEPTH) throw failure(HIERARCHY_CONFLICT);
      String parent = cursor.getParentId();
      cursor = parent == null ? null : byId.get(parent);
      if (parent != null && cursor == null) throw failure(HIERARCHY_CONFLICT);
    }
    return seen.size();
  }

  /** Checks the merged graph: a foreign mount must not introduce a cycle through retained nodes. */
  private static void validatePlannedHierarchy(List<Entry> entries, Map<String, Resource> byId) {
    Map<String, String> parents = new HashMap<>();
    Map<String, String> codesById = new HashMap<>();
    byId.forEach((id, resource) -> codesById.put(id, resource.getCode()));
    for (Entry entry : entries) {
      if (entry.resource().getId() != null) {
        codesById.put(entry.resource().getId(), entry.resource().getCode());
      }
    }
    for (Resource resource : byId.values()) {
      if (resource.getParentId() != null) {
        Resource parent = byId.get(resource.getParentId());
        if (parent != null)
          parents.put(codesById.get(resource.getId()), codesById.get(parent.getId()));
      }
    }
    for (Entry entry : entries) {
      parents.remove(entry.resource().getCode());
      if (entry.parentCode() != null) parents.put(entry.resource().getCode(), entry.parentCode());
    }
    for (Entry entry : entries) {
      Set<String> seen = new HashSet<>();
      for (String code = entry.resource().getCode(); code != null; code = parents.get(code)) {
        if (!seen.add(code) || seen.size() > MAX_DEPTH) throw failure(HIERARCHY_CONFLICT);
      }
    }
  }

  private static void index(Map<String, Resource> index, String key, Resource resource) {
    if (hasText(key)) {
      Resource previous = index.putIfAbsent(key, resource);
      if (previous != null && !Objects.equals(previous.getId(), resource.getId())) {
        throw failure(IDENTITY_CONFLICT);
      }
    }
  }

  private static void requireOwner(String owner, Resource resource) {
    if (resource != null && !owner.equals(resource.getPluginId())) {
      throw failure(OWNERSHIP_CONFLICT);
    }
  }

  private static void requireText(String value, int maximum) {
    if (!hasText(value)
        || !value.equals(value.strip())
        || value.codePointCount(0, value.length()) > maximum) {
      throw failure(INVALID_DECLARATION);
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static ResourceRegistrationException failure(ResourceRegistrationException.Code code) {
    return new ResourceRegistrationException(code);
  }

  record Entry(Resource resource, Resource existing, String parentCode, String externalParentId) {}

  private record Draft(
      Resource resource,
      String parentCode,
      org.simplepoint.security.ResourceParentReference parentReference) {}

  private record Pending(ResourceDeclaration declaration, String parentCode, int depth) {}
}
