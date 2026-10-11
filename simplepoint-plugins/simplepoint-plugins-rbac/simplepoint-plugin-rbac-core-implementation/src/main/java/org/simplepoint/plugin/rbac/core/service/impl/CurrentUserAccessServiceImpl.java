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
import org.simplepoint.core.jackson.FieldPermissionPolicy;
import org.simplepoint.plugin.rbac.core.api.service.CurrentUserAccessService;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
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
  private static final int LOOKUP_BATCH_SIZE = 100;
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
    var snapshot = new AccessSnapshot(context);
    if (!selectedType.equals("ALL")) requireReadable(snapshot.sourceType, "type");
    // The global PAGE lookup excludes ACTION/API metadata. The custom lookup
    // retains selected aliases/targets and ancestors, without scanning unrelated entries.
    Set<String> lookupCodes = selectedType.equals("PAGE") ? pageCodes(codes, snapshot) : codes;
    Map<String, Resource> metadata = snapshot.visible(lookupCodes);
    List<AccessEntry> matches = lookupCodes.stream()
        .map(code -> new AccessEntry(code, metadata.get(code), project(code, metadata.get(code), effectiveCodes.contains(code), snapshot.sourceType)))
        .filter(entry -> selectedType.equals("ALL") || selectedType.equals(entry.view().type()))
        .filter(entry -> search.isEmpty() || Stream.of(entry.view().code(), entry.view().label(), entry.view().path())
            .filter(Objects::nonNull).anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(search)))
        .sorted(Comparator.comparing(AccessEntry::view, accessOrder()))
        .toList();
    int from = (int) Math.min(pageable.getOffset(), matches.size());
    int to = Math.min(from + pageable.getPageSize(), matches.size());
    List<AccessEntry> page = matches.subList(from, to);
    List<ResourceAccess> content = page.stream().map(AccessEntry::view).toList();
    if (selectedType.equals("PAGE")) {
      Map<String, List<String>> directories = directories(page.stream().map(AccessEntry::metadata).filter(Objects::nonNull).toList(), snapshot);
      content = page.stream().map(entry -> withDirectory(entry.view(), directories.get(entry.code()))).toList();
    }
    return new PageImpl<>(content, pageable, matches.size());
  }

  @Override
  public PageAccessDetail pageDetail(String roleId, String code) {
    AuthorizationContext context = requireContext();
    if (code == null || code.isBlank()) throw badRequest("页面编码不能为空");
    Set<String> selected = selectedCodes(context, roleId);
    if (!selected.contains(code)) throw new AccessDeniedException("没有该页面的授权");
    var snapshot = new AccessSnapshot(context); requireReadable(snapshot.sourceType, "code");
    requireReadable(snapshot.sourceType, "type");
    Resource page = snapshot.visible(List.of(code)).get(code);
    if (page == null || page.getType() != ResourceType.PAGE) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面资源不存在");
    }
    Set<String> effective = normalize(context.getResources());
    Map<String, List<String>> directory = directories(List.of(page), snapshot);
    ResourceAccess pageAccess = withDirectory(project(code, page, effective.contains(code), snapshot.sourceType), directory.get(code));
    List<ResourceAccess> operations = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    TenantResource selectedPage = snapshot.tenantEntries.get(page.getId());
    List<String> frontier = selectedPage != null && selectedPage.getTargetResourceId() != null
        ? List.of(page.getId(), selectedPage.getTargetResourceId()) : List.of(page.getId());
    int total = 0;
    while (!frontier.isEmpty()) {
      var children = new CurrentUserAccessReadQuery(em).children(frontier, snapshot.custom ? snapshot.tenant : null, MAX_DETAIL_NODES - total);
      total += children.size();
      if (total > MAX_DETAIL_NODES) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "页面下级资源过多，请收窄资源层级");
      Map<String, Resource> visible = snapshot.visible(children.stream().map(CurrentUserAccessReadQuery.Edge::code).toList());
      List<String> next = new ArrayList<>();
      for (var child : children) {
        if (!seen.add(child.id()) || !visible.containsKey(child.code()) || child.type() == ResourceType.PAGE) continue;
        if (selected.contains(child.code()) && child.type() != ResourceType.GROUP && child.type() != ResourceType.MODULE) {
          operations.add(project(child.code(), visible.get(child.code()), effective.contains(child.code()), snapshot.sourceType));
        }
        next.add(child.id());
      }
      frontier = next;
    }
    operations.sort(accessOrder());
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

  private Set<String> pageCodes(Set<String> codes, AccessSnapshot snapshot) {
    if (snapshot.custom) {
      snapshot.load(codes);
      var active = snapshot.tenantEntries.values();
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

  /** Request-owned only: no service-level cache can cross users, roles, tenants or field policies. */
  private final class AccessSnapshot {
    final boolean custom; final String tenant; final Class<?> sourceType;
    final Map<String, Resource> byCode = new HashMap<>(); final Map<String, Resource> byId = new HashMap<>();
    final Map<String, TenantResource> tenantEntries = new HashMap<>(); final Set<String> lookedUp = new HashSet<>();
    AccessSnapshot(AuthorizationContext context) {custom = customCatalog(context); tenant = tenantId(context); sourceType = custom ? TenantResource.class : Resource.class;}
    void load(Collection<String> requested) {
      var missing = requested.stream().filter(Objects::nonNull).filter(code -> !lookedUp.contains(code)).distinct().toList();
      if (missing.isEmpty()) return; lookedUp.addAll(missing);
      if (custom) {
        for (var entry : tenantResourceCatalog.selectedResources(tenant, missing)) {
          tenantEntries.put(entry.getId(), entry); remember(virtual(entry)); lookedUp.add(entry.getCode());
        }
      } else {
        for (int start = 0; start < missing.size(); start += LOOKUP_BATCH_SIZE) {
          var loaded = resources.findAllByCodes(missing.subList(start, Math.min(start + LOOKUP_BATCH_SIZE, missing.size())));
          if (loaded != null) for (var row : loaded) if (row.getDeletedAt() == null && !Boolean.TRUE.equals(row.getDisabled())) remember(row);
        }
      }
    }
    void remember(Resource row) {byCode.put(row.getCode(), row); if (row.getId() != null) byId.put(row.getId(), row);}
    Map<String, Resource> visible(Collection<String> requested) {load(requested); var result = new HashMap<String, Resource>();
      for (String code : requested) if (byCode.containsKey(code)) result.put(code, byCode.get(code)); return result;}
  }

  private Map<String, List<String>> directories(List<Resource> pages, AccessSnapshot snapshot) {
    if (snapshot.custom) {
      var catalog = snapshot.tenantEntries;
      Map<String, List<String>> result = new HashMap<>();
      for (Resource page : pages) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = page.getParentId();
        while (cursor != null && seen.add(cursor) && names.size() < 20) {
          TenantResource parent = catalog.get(cursor);
          if (parent == null) break;
          var readable = FieldPermissionPolicy.project(TenantResource.class, Map.of("name", parent.getName()));
          names.add(readable.get("name") instanceof String name && !name.isBlank() ? name : "i18n:permissions.unavailable");
          cursor = parent.getParentId();
        }
        Collections.reverse(names);
        result.put(page.getCode(), List.copyOf(names));
      }
      return result;
    }
    Map<String, Resource> ancestry = new HashMap<>(snapshot.byId);
    Set<String> expanded = new HashSet<>();
    pages.forEach(page -> ancestry.put(page.getId(), page));
    Set<String> frontier = new LinkedHashSet<>();
    pages.stream().map(Resource::getParentId).filter(Objects::nonNull).forEach(frontier::add);
    for (int depth = 0; depth < 20 && !frontier.isEmpty(); depth++) {
      Set<String> next = new LinkedHashSet<>();
      List<String> ids = List.copyOf(frontier);
      var parents = new CurrentUserAccessReadQuery(em).parents(ids);
      Map<String, Resource> visible = snapshot.visible(parents.stream().map(CurrentUserAccessReadQuery.Edge::code).toList());
      for (var edge : parents) {
        Resource parent = visible.get(edge.code());
        if (parent == null || !expanded.add(parent.getId())) continue;
        ancestry.putIfAbsent(parent.getId(), parent);
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
          String label = resourceLabel(parent, Resource.class);
          names.add(label == null || label.equals(parent.getCode()) ? "i18n:permissions.unavailable" : label);
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
    if (tenantId == null || tenantId.isBlank() || context.getScopeType() == AuthorizationScopeType.PLATFORM) {
      return new OrganizationInfo(null, null, List.of());
    }
    var query = new CurrentUserAccessReadQuery(em);
    String id = query.assignedOrganization(tenantId, context.getUserId());
    if (id == null) {
      return new OrganizationInfo(null, null, List.of());
    }
    List<String> path = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String leafId = null;
    String leafName = null;
    while (id != null && seen.add(id) && path.size() < 20) {
      var node = query.organization(tenantId, id);
      if (node == null) break;
      var fields = new java.util.LinkedHashMap<String, Object>(); fields.put("id", node.id()); fields.put("name", node.name());
      var readable = FieldPermissionPolicy.project(Organization.class, fields);
      // A protected display ID can be null; it must not control which node is the leaf.
      if (path.isEmpty()) {
        leafId = (String) readable.get("id");
        leafName = (String) readable.get("name");
      }
      path.add(readable.get("name") instanceof String name && !name.isBlank() ? name : "i18n:permissions.unavailable");
      id = node.parent();
    }
    java.util.Collections.reverse(path);
    return new OrganizationInfo(leafId, leafName, List.copyOf(path));
  }

  private record AccessEntry(String code, Resource metadata, ResourceAccess view) {}

  private static ResourceAccess project(String code, Resource resource, boolean effective, Class<?> sourceType) {
    var values = new java.util.LinkedHashMap<String, Object>(); values.put("code", code);
    if (resource != null) {
      values.put("alias", resource.getAlias()); values.put("name", resource.getName()); values.put("label", resource.getLabel()); values.put("title", resource.getTitle());
      values.put("type", resource.getType() == null ? "OTHER" : resource.getType().name()); values.put("path", resource.getPath()); values.put("icon", resource.getIcon());
    }
    var readable = FieldPermissionPolicy.project(sourceType, values);
    String label = Stream.of("alias", "name", "label", "title", "code").map(readable::get).filter(String.class::isInstance).map(String.class::cast)
        .filter(value -> !value.isBlank()).findFirst().orElse(null);
    return new ResourceAccess((String) readable.get("code"), label, resource == null ? "OTHER" : (String) readable.get("type"),
        (String) readable.get("path"), (String) readable.get("icon"), effective, resource != null, List.of());
  }

  private static String resourceLabel(Resource resource, Class<?> sourceType) {
    return project(resource.getCode(), resource, false, sourceType).label();
  }

  private static Comparator<ResourceAccess> accessOrder() {
    return Comparator.comparing((ResourceAccess row) -> typeRank(row.type()))
        .thenComparing(ResourceAccess::label, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
        .thenComparing(ResourceAccess::code, Comparator.nullsLast(String::compareTo));
  }

  private static void requireReadable(Class<?> source, String field) {
    String access = FieldPermissionPolicy.access(source, field);
    if ("MASKED".equals(access) || FieldPermissionPolicy.hidden(access)) throw new AccessDeniedException("资源字段当前不可用于公开查询");
  }

  private static int typeRank(String type) {
    if (type == null) return 6;
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
