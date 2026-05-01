package org.simplepoint.plugin.rbac.core.service.impl;

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
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RolePermissionsRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.security.context.AuthorizationContextService;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.DataScopeType;
import org.simplepoint.security.entity.FieldAccessType;
import org.simplepoint.security.entity.FieldScope;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.simplepoint.security.entity.User;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Implementation of the AuthorizationContextService interface for calculating the authorization context based on provided attributes.
 *
 * @since v0.0.2
 */
@Service
@AmqpRemoteService
public class AuthorizationContextServiceImpl implements AuthorizationContextService {

  private final UsersService usersService;
  private final ObjectProvider<FeaturePermissionRelevanceRepository> featurePermissionRelevanceRepositoryProvider;
  private final ObjectProvider<TenantPackageRelevanceRepository> tenantPackageRelevanceRepositoryProvider;
  private final ObjectProvider<TenantRepository> tenantRepositoryProvider;
  private final ObjectProvider<RolePermissionsRelevanceRepository> rolePermissionsRelevanceRepositoryProvider;
  private final ObjectProvider<DataScopeRepository> dataScopeRepositoryProvider;
  private final ObjectProvider<FieldScopeRepository> fieldScopeRepositoryProvider;

  /**
   * Constructs an AuthorizationContextServiceImpl with the specified UsersService.
   *
   * @param usersService the UsersService to be used for loading user roles and permissions
   */
  public AuthorizationContextServiceImpl(
      UsersService usersService,
      ObjectProvider<FeaturePermissionRelevanceRepository> featurePermissionRelevanceRepositoryProvider,
      ObjectProvider<TenantPackageRelevanceRepository> tenantPackageRelevanceRepositoryProvider,
      ObjectProvider<TenantRepository> tenantRepositoryProvider,
      ObjectProvider<RolePermissionsRelevanceRepository> rolePermissionsRelevanceRepositoryProvider,
      ObjectProvider<DataScopeRepository> dataScopeRepositoryProvider,
      ObjectProvider<FieldScopeRepository> fieldScopeRepositoryProvider
  ) {
    this.usersService = usersService;
    this.featurePermissionRelevanceRepositoryProvider = featurePermissionRelevanceRepositoryProvider;
    this.tenantPackageRelevanceRepositoryProvider = tenantPackageRelevanceRepositoryProvider;
    this.tenantRepositoryProvider = tenantRepositoryProvider;
    this.rolePermissionsRelevanceRepositoryProvider = rolePermissionsRelevanceRepositoryProvider;
    this.dataScopeRepositoryProvider = dataScopeRepositoryProvider;
    this.fieldScopeRepositoryProvider = fieldScopeRepositoryProvider;
  }

  @Override
  public AuthorizationContext calculate(String tenantId, String userId, String contextId, Map<String, String> attributes) {
    User user = usersService.findById(userId).orElseThrow(() -> new RuntimeException("用户不存在"));
    boolean tenantOwner = false;
    if (!Boolean.TRUE.equals(user.superAdmin())
        && tenantId != null
        && !tenantId.isBlank()
        && !"default".equals(tenantId)) {
      var tenantRepository = tenantRepositoryProvider.getIfAvailable();
      if (tenantRepository != null) {
        var tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new AccessDeniedException("指定租户不存在"));
        if (!tenantRepository.hasUser(tenantId, userId)) {
          throw new AccessDeniedException("当前用户未加入指定租户");
        }
        tenantOwner = Objects.equals(tenant.getOwnerId(), userId);
      }
    }
    var roleAuthorityVos = usersService.loadRolesByUserId(tenantId, userId);
    var permissions = new LinkedHashSet<String>();
    var roleIds = roleAuthorityVos.stream().map(RoleGrantedAuthority::getId).toList();
    if (!roleIds.isEmpty()) {
      permissions.addAll(usersService.loadPermissionsInRoleIds(roleIds));
    }
    var featurePermissionRelevanceRepository = featurePermissionRelevanceRepositoryProvider.getIfAvailable();
    if (featurePermissionRelevanceRepository != null && !permissions.isEmpty()) {
      permissions.addAll(featurePermissionRelevanceRepository.findFeatureCodesByPermissionAuthorities(permissions));
    }
    if (featurePermissionRelevanceRepository != null) {
      Collection<String> publicFeatureCodes = featurePermissionRelevanceRepository.findPublicAccessFeatureCodes();
      if (publicFeatureCodes != null) {
        permissions.addAll(publicFeatureCodes);
      }
      Collection<String> publicPermissions = featurePermissionRelevanceRepository.findPermissionAuthoritiesByPublicAccessFeatures();
      if (publicPermissions != null) {
        permissions.addAll(publicPermissions);
      }
    }
    var tenantPackageRelevanceRepository = tenantPackageRelevanceRepositoryProvider.getIfAvailable();
    if (tenantPackageRelevanceRepository != null
        && tenantId != null
        && !tenantId.isBlank()
        && tenantOwner
        && !"default".equals(tenantId)) {
      permissions.addAll(tenantPackageRelevanceRepository.findFeatureCodesByTenantId(tenantId));
    }
    if (featurePermissionRelevanceRepository != null
        && tenantId != null
        && !tenantId.isBlank()
        && tenantOwner
        && !"default".equals(tenantId)) {
      permissions.addAll(featurePermissionRelevanceRepository.findPermissionAuthoritiesByTenantId(tenantId));
    }
    AuthorizationContext authorizationContext = new AuthorizationContext();
    authorizationContext.setUserId(userId);
    authorizationContext.setContextId(contextId);
    authorizationContext.setPermissions(permissions);
    authorizationContext.setIsAdministrator(user.superAdmin());
    authorizationContext.setRoles(roleAuthorityVos.stream().map(RoleGrantedAuthority::getAuthority).toList());
    authorizationContext.setAttributes(attributes);

    // Resolve data scope and field permissions for the user's roles
    if (!roleIds.isEmpty()) {
      resolveDataAndFieldScope(roleIds, userId, authorizationContext);
    }

    return authorizationContext;
  }

  /**
   * Loads DataScope and FieldScope configurations for the given role IDs and
   * populates them into the authorization context.
   * Most-permissive strategy is used: for multiple roles with different scopes,
   * the broadest DataScopeType wins; for field permissions, the most permissive
   * FieldAccessType per field wins.
   */
  private void resolveDataAndFieldScope(List<String> roleIds, String userId, AuthorizationContext ctx) {
    var rolePermissionsRepo = rolePermissionsRelevanceRepositoryProvider.getIfAvailable();
    if (rolePermissionsRepo == null) {
      return;
    }
    List<RolePermissionsRelevance> relevances = rolePermissionsRepo.findByRoleIdIn(roleIds);

    // --- Data scope resolution ---
    var dataScopeRepo = dataScopeRepositoryProvider.getIfAvailable();
    if (dataScopeRepo != null) {
      Set<String> dataScopeIds = relevances.stream()
          .map(RolePermissionsRelevance::getDataScopeId)
          .filter(Objects::nonNull)
          .collect(Collectors.toSet());

      if (!dataScopeIds.isEmpty()) {
        List<DataScope> dataScopes = dataScopeRepo.findAllById(dataScopeIds);
        // Pick the most permissive scope type
        DataScope mostPermissive = dataScopes.stream()
            .max(Comparator.comparingInt(ds -> ds.getType().getPermissiveLevel()))
            .orElse(null);
        if (mostPermissive != null) {
          ctx.setDataScopeType(mostPermissive.getType().name());
          if (mostPermissive.getType() == DataScopeType.CUSTOM) {
            // Union of all custom dept IDs across scopes at the same (most permissive) level
            Set<String> allCustomDeptIds = new HashSet<>();
            dataScopes.stream()
                .filter(ds -> ds.getType() == DataScopeType.CUSTOM)
                .forEach(ds -> allCustomDeptIds.addAll(ds.getCustomDeptIds()));
            ctx.setDeptIds(allCustomDeptIds);
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
      Set<String> fieldScopeIds = relevances.stream()
          .map(RolePermissionsRelevance::getFieldScopeId)
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
}
