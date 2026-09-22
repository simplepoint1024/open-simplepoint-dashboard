package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.rbac.core.api.service.CurrentUserAccessService;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.security.service.ResourceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Self-service profile access, scoped to the active authorization context. */
@Service
@Transactional(readOnly = true)
public class CurrentUserAccessServiceImpl implements CurrentUserAccessService {
  private static final int MAX_PAGE_SIZE = 60;
  private static final int LOOKUP_BATCH_SIZE = 200;
  private static final int MAX_DETAIL_NODES = 5000;
  private final EntityManager em;
  private final TenantService tenants;
  private final UsersService users;
  private final ResourceService resources;
  private TenantResourceCatalog tenantResourceCatalog;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setTenantResourceCatalog(TenantResourceCatalog tenantResourceCatalog) {
    this.tenantResourceCatalog = tenantResourceCatalog;
  }

  public CurrentUserAccessServiceImpl(EntityManager em, TenantService tenants,
      UsersService users, ResourceService resources) {
    this.em = em;
    this.tenants = tenants;
    this.users = users;
    this.resources = resources;
  }

  @Override
  public Page<ResourceAccess> resources(String roleId, String keyword, String type, Pageable pageable) {
    AuthorizationContext context = requireContext();
    if (pageable == null || pageInvalid(pageable)) {
      throw badRequest("每页最多 60 项，使用固定资源顺序");
    }
    String search = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    if (search.length() > 128) throw badRequest("搜索词不能超过 128 个字符");
    String selectedType = type == null || type.isBlank() ? "ALL" : type.trim().toUpperCase(Locale.ROOT);
    if (!selectedType.equals("ALL") && !selectedType.equals("OTHER")) {
      try {
        ResourceType.valueOf(selectedType);
      } catch (IllegalArgumentException ex) {
        throw badRequest("不支持的资源类型");
      }
    }
    Set<String> effectiveCodes = normalize(context.getResources());
    Set<String> codes = selectedCodes(context, roleId);
    if (codes.isEmpty()) return new PageImpl<>(List.of(), pageable, 0);
    // The PAGE view must not hydrate thousands of ACTION/API resources before pagination.
    Set<String> lookupCodes = selectedType.equals("PAGE") ? pageCodes(codes) : codes;
    Map<String, Resource> metadata = visibleResources(lookupCodes);
    List<ResourceAccess> matches = lookupCodes.stream()
        .map(code -> project(code, metadata.get(code), effectiveCodes.contains(code)))
        .filter(row -> selectedType.equals("ALL") || selectedType.equals(row.type()))
        .filter(row -> search.isEmpty() || Stream.of(row.code(), row.label(), row.path())
            .filter(Objects::nonNull).anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(search)))
        .sorted(Comparator.comparing((ResourceAccess row) -> typeRank(row.type()))
            .thenComparing(ResourceAccess::label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ResourceAccess::code))
        .toList();
    int from = (int) Math.min(pageable.getOffset(), matches.size());
    int to = Math.min(from + pageable.getPageSize(), matches.size());
    List<ResourceAccess> content = matches.subList(from, to);
    if (selectedType.equals("PAGE")) {
      Map<String, List<String>> directories = directories(content.stream()
          .map(row -> metadata.get(row.code())).filter(Objects::nonNull).toList());
      content = content.stream().map(row -> withDirectory(row, directories.get(row.code()))).toList();
    }
    return new PageImpl<>(content, pageable, matches.size());
  }

  @Override
  public PageAccessDetail pageDetail(String roleId, String code) {
    AuthorizationContext context = requireContext();
    if (code == null || code.isBlank()) throw badRequest("页面编码不能为空");
    Set<String> selected = selectedCodes(context, roleId);
    if (!selected.contains(code)) throw new AccessDeniedException("没有该页面的授权");
    Resource page = visibleResources(List.of(code)).get(code);
    if (page == null || page.getType() != ResourceType.PAGE) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面资源不存在");
    }
    Set<String> effective = normalize(context.getResources());
    Map<String, List<String>> directory = directories(List.of(page));
    ResourceAccess pageAccess = withDirectory(project(code, page, effective.contains(code)), directory.get(code));
    List<ResourceAccess> operations = new ArrayList<>();
    if (customCatalog(context)) {
      Map<String, List<TenantResource>> childrenByParent = new HashMap<>();
      TenantResource selectedPage = null;
      for (TenantResource entry : tenantResourceCatalog.activeResources(tenantId(context))) {
        if (code.equals(entry.getCode())) selectedPage = entry;
        childrenByParent.computeIfAbsent(entry.getParentId() == null ? "" : entry.getParentId(),
            ignored -> new ArrayList<>()).add(entry);
      }
      Set<String> seenCustom = new HashSet<>();
      List<String> queue = selectedPage != null && selectedPage.getTargetResourceId() != null
          ? List.of(page.getId(), selectedPage.getTargetResourceId()) : List.of(page.getId());
      int visited = 0;
      while (!queue.isEmpty()) {
        List<String> next = new ArrayList<>();
        for (String parent : queue) {
          for (TenantResource entry : childrenByParent.getOrDefault(parent, List.of())) {
            if (!seenCustom.add(entry.getId())) continue;
            if (++visited > MAX_DETAIL_NODES) throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "页面下级资源过多，请收窄资源层级");
            if (entry.getType() == ResourceType.PAGE) continue;
            if (selected.contains(entry.getCode()) && entry.getType() != ResourceType.GROUP
                && entry.getType() != ResourceType.MODULE) {
              operations.add(project(entry.getCode(), virtual(entry), effective.contains(entry.getCode())));
            }
            next.add(entry.getId());
          }
        }
        queue = next;
      }
      operations.sort(Comparator.comparing(ResourceAccess::label, String.CASE_INSENSITIVE_ORDER)
          .thenComparing(ResourceAccess::code));
      return new PageAccessDetail(pageAccess, List.copyOf(operations), false, false);
    }
    Set<String> seen = new HashSet<>();
    List<String> frontier = List.of(page.getId());
    int total = 0;
    while (!frontier.isEmpty()) {
      List<Resource> children = new ArrayList<>();
      for (int start = 0; start < frontier.size(); start += LOOKUP_BATCH_SIZE) {
        children.addAll(em.createQuery("select r from Resource r where r.parentId in :parents"
            + " and r.deletedAt is null and r.disabled = false", Resource.class)
            .setParameter("parents", frontier.subList(start, Math.min(start + LOOKUP_BATCH_SIZE, frontier.size())))
            .getResultList());
      }
      total += children.size();
      if (total > MAX_DETAIL_NODES) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "页面下级资源过多，请收窄资源层级");
      Map<String, Resource> visible = visibleResources(children.stream().map(Resource::getCode).toList());
      List<String> next = new ArrayList<>();
      for (Resource child : children) {
        if (!seen.add(child.getId()) || !visible.containsKey(child.getCode())
            || child.getType() == ResourceType.PAGE) continue;
        if (selected.contains(child.getCode()) && child.getType() != ResourceType.GROUP
            && child.getType() != ResourceType.MODULE) {
          operations.add(project(child.getCode(), child, effective.contains(child.getCode())));
        }
        next.add(child.getId());
      }
      frontier = next;
    }
    operations.sort(Comparator.comparing(ResourceAccess::label, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(ResourceAccess::code));
    return new PageAccessDetail(pageAccess, List.copyOf(operations), false, false);
  }

  private Set<String> selectedCodes(AuthorizationContext context, String roleId) {
    if (roleId == null || roleId.isBlank()) return normalize(context.getResources());
    if (context.getScopeType() == AuthorizationScopeType.PLATFORM) {
      throw new AccessDeniedException("平台工作空间不提供租户角色授权清单");
    }
    String ownRoleId = roleId.trim();
    boolean owned = tenants.getCurrentUserRoles(tenantId(context)).stream()
        .anyMatch(role -> ownRoleId.equals(role.getId()));
    if (!owned) throw new AccessDeniedException("只能查看本人在当前工作空间拥有的角色");
    return normalize(users.loadResourcesInRoleIds(List.of(ownRoleId)));
  }

  private Set<String> pageCodes(Set<String> codes) {
    if (customCatalog(requireContext())) {
      List<TenantResource> active = tenantResourceCatalog.activeResources(tenantId(requireContext()));
      Set<String> aliasedPlatformPages = active.stream()
          .filter(item -> codes.contains(item.getCode()))
          .map(TenantResource::getTargetResourceId).filter(java.util.Objects::nonNull)
          .collect(java.util.stream.Collectors.toSet());
      return active.stream()
          .filter(item -> item.getType() == ResourceType.PAGE && codes.contains(item.getCode())
              && !(item.isPlatformSource() && aliasedPlatformPages.contains(item.getId())))
          .map(TenantResource::getCode).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
    List<String> all = List.copyOf(codes);
    Set<String> pages = new LinkedHashSet<>();
    for (int start = 0; start < all.size(); start += LOOKUP_BATCH_SIZE) {
      pages.addAll(em.createQuery("select r.code from Resource r where r.code in :codes"
          + " and r.type = :type and r.deletedAt is null and r.disabled = false", String.class)
          .setParameter("codes", all.subList(start, Math.min(start + LOOKUP_BATCH_SIZE, all.size())))
          .setParameter("type", ResourceType.PAGE).getResultList());
    }
    return pages;
  }

  private Map<String, Resource> visibleResources(Collection<String> codes) {
    if (customCatalog(requireContext())) {
      Set<String> requested = new HashSet<>(codes);
      Map<String, Resource> custom = new HashMap<>();
      for (TenantResource entry : tenantResourceCatalog.activeResources(tenantId(requireContext()))) {
        if (requested.contains(entry.getCode())) custom.put(entry.getCode(), virtual(entry));
      }
      return custom;
    }
    Map<String, Resource> result = new HashMap<>();
    List<String> all = List.copyOf(new LinkedHashSet<>(codes));
    for (int start = 0; start < all.size(); start += LOOKUP_BATCH_SIZE) {
      Collection<Resource> loaded = resources.findAllByCodes(all.subList(start,
          Math.min(start + LOOKUP_BATCH_SIZE, all.size())));
      if (loaded == null) continue;
      for (Resource resource : loaded) {
        if (resource.getDeletedAt() == null && !Boolean.TRUE.equals(resource.getDisabled())) {
          result.put(resource.getCode(), resource);
        }
      }
    }
    return result;
  }

  private Map<String, List<String>> directories(List<Resource> pages) {
    if (customCatalog(requireContext())) {
      Map<String, TenantResource> catalog = new HashMap<>();
      tenantResourceCatalog.activeResources(tenantId(requireContext()))
          .forEach(entry -> catalog.put(entry.getId(), entry));
      Map<String, List<String>> result = new HashMap<>();
      for (Resource page : pages) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = page.getParentId();
        while (cursor != null && seen.add(cursor) && names.size() < 20) {
          TenantResource parent = catalog.get(cursor);
          if (parent == null) break;
          names.add(parent.getName());
          cursor = parent.getParentId();
        }
        Collections.reverse(names);
        result.put(page.getCode(), List.copyOf(names));
      }
      return result;
    }
    Map<String, Resource> ancestry = new HashMap<>();
    pages.forEach(page -> ancestry.put(page.getId(), page));
    Set<String> frontier = new LinkedHashSet<>();
    pages.stream().map(Resource::getParentId).filter(Objects::nonNull).forEach(frontier::add);
    for (int depth = 0; depth < 20 && !frontier.isEmpty(); depth++) {
      Set<String> next = new LinkedHashSet<>();
      List<String> ids = List.copyOf(frontier);
      List<Resource> parents = new ArrayList<>();
      for (int start = 0; start < ids.size(); start += LOOKUP_BATCH_SIZE) {
        parents.addAll(em.createQuery("select r from Resource r where r.id in :ids"
            + " and r.deletedAt is null and r.disabled = false", Resource.class)
            .setParameter("ids", ids.subList(start, Math.min(start + LOOKUP_BATCH_SIZE, ids.size())))
            .getResultList());
      }
      Map<String, Resource> visible = visibleResources(parents.stream().map(Resource::getCode).toList());
      for (Resource parent : parents) {
        if (!visible.containsKey(parent.getCode()) || ancestry.putIfAbsent(parent.getId(), parent) != null) continue;
        if (parent.getParentId() != null) next.add(parent.getParentId());
      }
      frontier = next;
    }
    Map<String, List<String>> result = new HashMap<>();
    for (Resource page : pages) {
      List<String> names = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      String id = page.getParentId();
      while (id != null && seen.add(id) && names.size() < 20) {
        Resource parent = ancestry.get(id);
        if (parent == null) break;
        if (parent.getType() == ResourceType.GROUP || parent.getType() == ResourceType.MODULE) {
          names.add(resourceLabel(parent));
        }
        id = parent.getParentId();
      }
      Collections.reverse(names);
      result.put(page.getCode(), List.copyOf(names));
    }
    return result;
  }

  private boolean customCatalog(AuthorizationContext context) {
    String tenant = tenantId(context);
    return tenantResourceCatalog != null && context.getScopeType() == AuthorizationScopeType.TENANT
        && tenant != null && tenantResourceCatalog.mode(tenant) == TenantResourceMode.CUSTOM;
  }

  private static Resource virtual(TenantResource entry) {
    Resource resource = new Resource();
    resource.setId(entry.getId());
    resource.setCode(entry.getCode());
    resource.setName(entry.getName());
    resource.setType(entry.getType());
    resource.setParentId(entry.getParentId());
    resource.setPath(entry.getPath());
    resource.setComponent(entry.getComponent());
    resource.setIcon(entry.getIcon());
    resource.setGrantable(entry.isGrantable());
    resource.setDisabled(!entry.isEnabled());
    return resource;
  }

  private static ResourceAccess withDirectory(ResourceAccess row, List<String> directory) {
    return new ResourceAccess(row.code(), row.label(), row.type(), row.path(), row.icon(),
        row.effective(), row.available(), directory == null ? List.of() : directory);
  }

  @Override
  public OrganizationInfo organization() {
    AuthorizationContext context = requireContext();
    String tenantId = tenantId(context);
    if (tenantId == null || context.getScopeType() == AuthorizationScopeType.PLATFORM) {
      return new OrganizationInfo(null, null, List.of());
    }
    List<TenantUserRelevance> memberships = em.createQuery(
        "select m from TenantUserRelevance m where m.tenantId = :tenant"
            + " and m.userId = :user and m.deletedAt is null and m.enabled = true",
        TenantUserRelevance.class)
        .setParameter("tenant", tenantId).setParameter("user", context.getUserId())
        .setMaxResults(1).getResultList();
    if (memberships.isEmpty() || memberships.getFirst().getOrgId() == null) {
      return new OrganizationInfo(null, null, List.of());
    }
    List<String> path = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String id = memberships.getFirst().getOrgId();
    String leafId = null;
    String leafName = null;
    while (id != null && seen.add(id) && path.size() < 20) {
      List<Organization> found = em.createQuery("select o from Organization o"
          + " where o.id = :id and o.tenantId = :tenant and o.deletedAt is null"
          + " and o.enabled = true", Organization.class)
          .setParameter("id", id).setParameter("tenant", tenantId)
          .setMaxResults(1).getResultList();
      if (found.isEmpty()) break;
      Organization node = found.getFirst();
      if (leafId == null) {
        leafId = node.getId();
        leafName = node.getName();
      }
      path.add(node.getName());
      id = node.getParentId();
    }
    java.util.Collections.reverse(path);
    return new OrganizationInfo(leafId, leafName, List.copyOf(path));
  }

  private static ResourceAccess project(String code, Resource resource, boolean effective) {
    if (resource == null) return new ResourceAccess(code, code, "OTHER", null, null, effective, false, List.of());
    return new ResourceAccess(code, resourceLabel(resource),
        resource.getType() == null ? "OTHER" : resource.getType().name(),
        resource.getPath(), resource.getIcon(), effective, true, List.of());
  }

  private static String resourceLabel(Resource resource) {
    return Stream.of(resource.getAlias(), resource.getName(), resource.getLabel(),
        resource.getTitle(), resource.getCode()).filter(value -> value != null && !value.isBlank())
        .findFirst().orElse(resource.getCode());
  }

  private static int typeRank(String type) {
    return switch (type) {
      case "GROUP" -> 0;
      case "MODULE" -> 1;
      case "PAGE" -> 2;
      case "FEATURE" -> 3;
      case "ACTION" -> 4;
      case "API" -> 5;
      default -> 6;
    };
  }

  private static Set<String> normalize(Collection<String> codes) {
    if (codes == null) return Set.of();
    Set<String> normalized = new LinkedHashSet<>();
    for (String code : codes) {
      if (code != null && !code.isBlank()) normalized.add(code.trim());
    }
    return normalized;
  }

  private static String tenantId(AuthorizationContext context) {
    Map<String, String> attributes = context.getAttributes();
    return attributes == null ? null : attributes.get("X-Tenant-Id");
  }

  private static AuthorizationContext requireContext() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
      throw new AccessDeniedException("当前未认证用户");
    }
    return context;
  }

  private static boolean pageInvalid(Pageable page) {
    return page.isUnpaged() || page.getPageSize() < 1 || page.getPageSize() > MAX_PAGE_SIZE
        || page.getOffset() > Integer.MAX_VALUE || page.getSort().isSorted();
  }

  private static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
