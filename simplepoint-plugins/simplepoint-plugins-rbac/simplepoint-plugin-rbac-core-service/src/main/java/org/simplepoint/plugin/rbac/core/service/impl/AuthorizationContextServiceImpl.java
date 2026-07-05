package org.simplepoint.plugin.rbac.core.service.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.core.AuthorizationActorRole;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationResourceNamespaces;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.context.AuthorizationContextService;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.DataScopeType;
import org.simplepoint.security.entity.FieldAccessType;
import org.simplepoint.security.entity.FieldScope;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.service.ResourceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

/**
 * Implementation of the AuthorizationContextService interface for calculating the authorization context based on provided attributes.
 *
 * @since v0.0.2
 */
@Service
@RemoteProvider
public class AuthorizationContextServiceImpl implements AuthorizationContextService {

  private final UsersService usersService;
  private final ObjectProvider<ResourceService> resourceServiceProvider;
  private final ObjectProvider<TenantPackageRelevanceRepository> tenantPackageRelevanceRepositoryProvider;
  private final ObjectProvider<TenantRepository> tenantRepositoryProvider;
  private final ObjectProvider<RoleResourceGrantRepository> roleResourceGrantRepositoryProvider;
  private final ObjectProvider<DataScopeRepository> dataScopeRepositoryProvider;
  private final ObjectProvider<FieldScopeRepository> fieldScopeRepositoryProvider;
  private final ObjectProvider<OrganizationRepository> organizationRepositoryProvider;

  /**
   * Constructs an AuthorizationContextServiceImpl with the specified UsersService.
   *
   * @param usersService the UsersService to be used for loading user roles and resources
   */
  public AuthorizationContextServiceImpl(
      UsersService usersService,
      ObjectProvider<ResourceService> resourceServiceProvider,
      ObjectProvider<TenantPackageRelevanceRepository> tenantPackageRelevanceRepositoryProvider,
      ObjectProvider<TenantRepository> tenantRepositoryProvider,
      ObjectProvider<RoleResourceGrantRepository> roleResourceGrantRepositoryProvider,
      ObjectProvider<DataScopeRepository> dataScopeRepositoryProvider,
      ObjectProvider<FieldScopeRepository> fieldScopeRepositoryProvider,
      ObjectProvider<OrganizationRepository> organizationRepositoryProvider
  ) {
    this.usersService = usersService;
    this.resourceServiceProvider = resourceServiceProvider;
    this.tenantPackageRelevanceRepositoryProvider = tenantPackageRelevanceRepositoryProvider;
    this.tenantRepositoryProvider = tenantRepositoryProvider;
    this.roleResourceGrantRepositoryProvider = roleResourceGrantRepositoryProvider;
    this.dataScopeRepositoryProvider = dataScopeRepositoryProvider;
    this.fieldScopeRepositoryProvider = fieldScopeRepositoryProvider;
    this.organizationRepositoryProvider = organizationRepositoryProvider;
  }

  @Override
  public AuthorizationContext calculate(String tenantId, String userId, String contextId, Map<String, String> attributes) {
    if (userId == null || userId.isBlank()) {
      throw new BadCredentialsException("认证主体缺少用户标识");
    }
    User user = usersService.findByIdForAuthorization(userId)
        .orElseThrow(() -> new BadCredentialsException("用户不存在"));
    String resolvedTenantId = normalizeTenantId(tenantId);
    Tenant resolvedTenant = null;
    boolean administrator = Boolean.TRUE.equals(user.superAdmin());
    var tenantRepository = tenantRepositoryProvider.getIfAvailable();
    if (!administrator && (resolvedTenantId == null || resolvedTenantId.isBlank()) && tenantRepository != null) {
      resolvedTenant = Optional.ofNullable(tenantRepository.findPersonalTenantByOwnerId(userId))
          .orElse(Optional.empty())
          .orElse(null);
      if (resolvedTenant != null) {
        resolvedTenantId = resolvedTenant.getId();
      }
    }
    Map<String, String> effectiveAttributes = new HashMap<>(attributes == null ? Map.of() : attributes);
    effectiveAttributes.put("X-User-Id", userId);
    if (resolvedTenantId != null) {
      effectiveAttributes.put("X-Tenant-Id", resolvedTenantId);
    }
    if (contextId != null && !contextId.isBlank()) {
      effectiveAttributes.put("X-Context-Id", contextId);
    }
    boolean tenantOwner = false;
    if (resolvedTenantId != null && !resolvedTenantId.isBlank()) {
      if (tenantRepository != null) {
        resolvedTenant = resolvedTenant != null
            ? resolvedTenant
            : tenantRepository.findById(resolvedTenantId).orElseThrow(() -> new AccessDeniedException("指定租户不存在"));
        if (!administrator && !tenantRepository.hasUser(resolvedTenantId, userId)) {
          throw new AccessDeniedException("当前用户未加入指定租户");
        }
        tenantOwner = Objects.equals(resolvedTenant.getOwnerId(), userId);
      } else if (!administrator) {
        throw new AccessDeniedException("无法验证指定租户");
      }
    }
    String selectedRoleId = trimToNull(effectiveAttributes.get("X-Role-Id"));
    var roleAuthorityVos = filterSelectedRole(loadEffectiveRoles(resolvedTenantId, userId), selectedRoleId);
    if (selectedRoleId != null) {
      effectiveAttributes.put("X-Role-Id", selectedRoleId);
    }
    var resources = new LinkedHashSet<String>();
    var roleIds = roleAuthorityVos.stream().map(RoleGrantedAuthority::getId).toList();
    if (!roleIds.isEmpty()) {
      resources.addAll(usersService.loadResourcesInRoleIds(roleIds));
    }
    var resourceService = resourceServiceProvider.getIfAvailable();
    if (resourceService != null) {
      Collection<String> publicResourceCodes = resourceService.findPublicAccessCodes();
      if (publicResourceCodes != null) {
        resources.addAll(publicResourceCodes);
      }
    }
    var tenantPackageRelevanceRepository = tenantPackageRelevanceRepositoryProvider.getIfAvailable();
    if (tenantPackageRelevanceRepository != null
        && resolvedTenantId != null
        && !resolvedTenantId.isBlank()
        && tenantOwner) {
      resources.addAll(tenantPackageRelevanceRepository.findResourceCodesByTenantId(resolvedTenantId));
    }
    AuthorizationScopeType scopeType = resolveScopeType(administrator, resolvedTenantId, resolvedTenant);
    boolean tenantAdmin = hasTenantAdminAuthority(roleAuthorityVos, resources);
    AuthorizationActorRole actorRole = resolveActorRole(administrator, resolvedTenantId, resolvedTenant, tenantOwner, tenantAdmin);
    effectiveAttributes.put("X-Scope-Type", scopeType.name());
    effectiveAttributes.put("X-Actor-Role", actorRole.name());
    AuthorizationContext authorizationContext = new AuthorizationContext();
    authorizationContext.setUserId(userId);
    authorizationContext.setContextId(contextId);
    authorizationContext.setResources(resources);
    authorizationContext.setIsAdministrator(administrator);
    authorizationContext.setRoles(roleAuthorityVos.stream().map(RoleGrantedAuthority::getAuthority).toList());
    authorizationContext.setVersion(resolveAuthorizationVersion(resolvedTenantId, tenantRepository));
    authorizationContext.setAttributes(effectiveAttributes);
    authorizationContext.setScopeType(scopeType);
    authorizationContext.setActorRole(actorRole);

    // Resolve data scope and field permissions for the user's roles
    if (!roleIds.isEmpty()) {
      resolveDataAndFieldScope(roleIds, userId, resolvedTenantId, user, authorizationContext);
    }

    return authorizationContext;
  }

  private String normalizeTenantId(String tenantId) {
    if (tenantId == null) {
      return null;
    }
    String trimmed = tenantId.trim();
    if (trimmed.isEmpty() || "default".equals(trimmed)) {
      return null;
    }
    return trimmed;
  }

  private AuthorizationScopeType resolveScopeType(boolean administrator, String tenantId, Tenant tenant) {
    if ((tenantId == null || tenantId.isBlank()) && administrator) {
      return AuthorizationScopeType.PLATFORM;
    }
    if (tenantId == null || tenantId.isBlank()) {
      return AuthorizationScopeType.PERSONAL;
    }
    if (tenant != null && tenant.getTenantType() == TenantType.PERSONAL) {
      return AuthorizationScopeType.PERSONAL;
    }
    return AuthorizationScopeType.TENANT;
  }

  private Long resolveAuthorizationVersion(String tenantId, TenantRepository tenantRepository) {
    if (tenantId == null || tenantId.isBlank() || tenantRepository == null) {
      return 0L;
    }
    Long authorizationVersion = tenantRepository.getTenantAuthorizationVersion(tenantId);
    return authorizationVersion == null ? 0L : authorizationVersion;
  }

  private AuthorizationActorRole resolveActorRole(
      boolean administrator, String tenantId, Tenant tenant, boolean tenantOwner, boolean tenantAdmin) {
    if (administrator && (tenantId == null || tenantId.isBlank())) {
      return AuthorizationActorRole.PLATFORM_ADMIN;
    }
    if (administrator) {
      return AuthorizationActorRole.TENANT_ADMIN;
    }
    if (tenant == null) {
      return AuthorizationActorRole.PERSONAL_MEMBER;
    }
    if (tenant != null && tenant.getTenantType() == TenantType.PERSONAL) {
      return tenantOwner ? AuthorizationActorRole.PERSONAL_OWNER : AuthorizationActorRole.PERSONAL_MEMBER;
    }
    if (tenantOwner) {
      return AuthorizationActorRole.TENANT_OWNER;
    }
    if (tenantAdmin) {
      return AuthorizationActorRole.TENANT_ADMIN;
    }
    return AuthorizationActorRole.TENANT_MEMBER;
  }

  private boolean hasTenantAdminAuthority(Collection<RoleGrantedAuthority> roles, Collection<String> resources) {
    if (resources != null && resources.contains(AuthorizationResourceNamespaces.TENANT_ADMIN)) {
      return true;
    }
    if (roles == null) {
      return false;
    }
    return roles.stream()
        .map(RoleGrantedAuthority::getAuthority)
        .filter(Objects::nonNull)
        .anyMatch(authority -> "TENANT_ADMIN".equals(authority)
            || "ROLE_TENANT_ADMIN".equals(authority)
            || AuthorizationResourceNamespaces.TENANT_ADMIN.equals(authority));
  }

  private List<RoleGrantedAuthority> loadEffectiveRoles(String tenantId, String userId) {
    LinkedHashMap<String, RoleGrantedAuthority> rolesById = new LinkedHashMap<>();
    usersService.loadRolesByUserId(tenantId, userId)
        .forEach(role -> rolesById.putIfAbsent(role.getId(), role));
    if (tenantId != null && !tenantId.isBlank()) {
      usersService.loadRolesByUserId(null, userId)
          .forEach(role -> rolesById.putIfAbsent(role.getId(), role));
    }
    return List.copyOf(rolesById.values());
  }

  private List<RoleGrantedAuthority> filterSelectedRole(List<RoleGrantedAuthority> roles, String selectedRoleId) {
    if (selectedRoleId == null) {
      return roles;
    }
    return roles.stream()
        .filter(role -> selectedRoleId.equals(role.getId()))
        .findFirst()
        .map(List::of)
        .orElseThrow(() -> new AccessDeniedException("当前用户未拥有指定角色"));
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Loads DataScope and FieldScope configurations for the given role IDs and
   * populates them into the authorization context.
   * Most-permissive strategy is used: for multiple roles with different scopes,
   * the broadest DataScopeType wins; for field permissions, the most permissive
   * FieldAccessType per field wins.
   * DEPT and DEPT_AND_BELOW types additionally populate deptIds by traversing
   * the organization tree starting from the user's orgId.
   */
  private void resolveDataAndFieldScope(
      List<String> roleIds, String userId, String tenantId, User user, AuthorizationContext ctx) {
    var roleResourceGrantRepository = roleResourceGrantRepositoryProvider.getIfAvailable();
    if (roleResourceGrantRepository == null) {
      return;
    }
    List<RoleResourceGrant> grants = roleResourceGrantRepository.findByRoleIdIn(roleIds);

    // --- Data scope resolution ---
    var dataScopeRepo = dataScopeRepositoryProvider.getIfAvailable();
    if (dataScopeRepo != null) {
      Set<String> dataScopeIds = grants.stream()
          .map(RoleResourceGrant::getDataScopeId)
          .filter(Objects::nonNull)
          .collect(Collectors.toSet());

      if (!dataScopeIds.isEmpty()) {
        List<DataScope> dataScopes = dataScopeRepo.findAllById(dataScopeIds);
        if (!dataScopes.isEmpty()) {
          boolean includeSelf = false;
          boolean hasDepartmentScope = false;
          Set<String> effectiveDeptIds = new HashSet<>();
          for (DataScope dataScope : dataScopes) {
            DataScopeType type = dataScope.getType();
            if (type == DataScopeType.ALL) {
              ctx.setDataScopeType(DataScopeType.ALL.name());
              ctx.setDeptIds(Set.of());
              ctx.setDataScopeIncludeSelf(false);
              effectiveDeptIds.clear();
              includeSelf = false;
              break;
            }
            if (type == DataScopeType.SELF) {
              includeSelf = true;
            } else if (type == DataScopeType.CUSTOM) {
              hasDepartmentScope = true;
              effectiveDeptIds.addAll(dataScope.getCustomDeptIds());
            } else if (type == DataScopeType.DEPT || type == DataScopeType.DEPT_AND_BELOW) {
              hasDepartmentScope = true;
              Set<String> deptIdsForScope = resolveDeptIds(type, tenantId, user);
              if (deptIdsForScope.isEmpty()) {
                includeSelf = true;
              } else {
                effectiveDeptIds.addAll(deptIdsForScope);
              }
            }
          }
          if (ctx.getDataScopeType() == null) {
            if (hasDepartmentScope) {
              ctx.setDataScopeType(DataScopeType.CUSTOM.name());
              ctx.setDeptIds(effectiveDeptIds);
              ctx.setDataScopeIncludeSelf(includeSelf);
            } else if (includeSelf) {
              ctx.setDataScopeType(DataScopeType.SELF.name());
              ctx.setDeptIds(Set.of());
              ctx.setDataScopeIncludeSelf(false);
            }
          }
        }
      }
    }

    // Ensure SELF scope falls back correctly when no scope is configured
    if (ctx.getDataScopeType() == null) {
      ctx.setDataScopeType(DataScopeType.SELF.name());
    }

    // --- Field scope resolution ---
    var fieldScopeRepo = fieldScopeRepositoryProvider.getIfAvailable();
    if (fieldScopeRepo != null) {
      Set<String> fieldScopeIds = grants.stream()
          .map(RoleResourceGrant::getFieldScopeId)
          .filter(Objects::nonNull)
          .collect(Collectors.toSet());

      if (!fieldScopeIds.isEmpty()) {
        List<FieldScope> fieldScopes = fieldScopeRepo.findAllById(fieldScopeIds);
        // Merge field permissions; most permissive access type per field key wins
        Map<String, String> fieldPerms = new HashMap<>();
        fieldScopes.forEach(fs -> fs.getEntries().forEach(entry -> {
          String key = entry.getResource() + "#" + entry.getField();
          String existingLevel = fieldPerms.get(key);
          FieldAccessType newAccess = entry.getAccess();
          if (existingLevel == null
              || newAccess.getPermissiveLevel() > FieldAccessType.valueOf(existingLevel).getPermissiveLevel()) {
            fieldPerms.put(key, newAccess.name());
          }
        }));
        if (!fieldPerms.isEmpty()) {
          ctx.setFieldPermissions(fieldPerms);
        }
      }
    }
  }

  /**
   * Resolves the set of department IDs for DEPT or DEPT_AND_BELOW scope types.
   * For DEPT: just the user's own orgId.
   * For DEPT_AND_BELOW: BFS from user's orgId through the organization tree.
   */
  private Set<String> resolveDeptIds(DataScopeType type, String tenantId, User user) {
    String orgId = user.getOrgId();
    if (orgId == null || orgId.isBlank()) {
      return Set.of();
    }
    if (type == DataScopeType.DEPT) {
      return Set.of(orgId);
    }
    // DEPT_AND_BELOW: BFS traversal
    var orgRepo = organizationRepositoryProvider.getIfAvailable();
    if (orgRepo == null) {
      return Set.of(orgId);
    }
    Set<String> allDeptIds = new HashSet<>();
    allDeptIds.add(orgId);
    Set<String> frontier = new HashSet<>(Set.of(orgId));
    while (!frontier.isEmpty()) {
      Collection<String> children = orgRepo.findIdsByParentIds(frontier, tenantId);
      Set<String> newChildren = new HashSet<>(children);
      newChildren.removeAll(allDeptIds); // prevent cycles
      allDeptIds.addAll(newChildren);
      frontier = newChildren;
    }
    return allDeptIds;
  }
}
