package org.simplepoint.plugin.rbac.core.service.impl;

import java.util.LinkedHashSet;
import java.util.Map;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.security.context.AuthorizationContextService;
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
  private final ObjectProvider<TenantRepository> tenantRepositoryProvider;

  /**
   * Constructs an AuthorizationContextServiceImpl with the specified UsersService.
   *
   * @param usersService the UsersService to be used for loading user roles and permissions
   */
  public AuthorizationContextServiceImpl(
      UsersService usersService,
      ObjectProvider<FeaturePermissionRelevanceRepository> featurePermissionRelevanceRepositoryProvider,
      ObjectProvider<TenantRepository> tenantRepositoryProvider
  ) {
    this.usersService = usersService;
    this.featurePermissionRelevanceRepositoryProvider = featurePermissionRelevanceRepositoryProvider;
    this.tenantRepositoryProvider = tenantRepositoryProvider;
  }

  @Override
  public AuthorizationContext calculate(String tenantId, String userId, String contextId, Map<String, String> attributes) {
    User user = usersService.findById(userId).orElseThrow(() -> new RuntimeException("用户不存在"));
    if (!Boolean.TRUE.equals(user.superAdmin())
        && tenantId != null
        && !tenantId.isBlank()
        && !"default".equals(tenantId)) {
      var tenantRepository = tenantRepositoryProvider.getIfAvailable();
      if (tenantRepository != null && !tenantRepository.hasUser(tenantId, userId)) {
        throw new AccessDeniedException("当前用户未加入指定租户");
      }
    }
    var roleAuthorityVos = usersService.loadRolesByUserId(tenantId, userId);
    var permissions = new LinkedHashSet<String>();
    var roleIds = roleAuthorityVos.stream().map(RoleGrantedAuthority::getId).toList();
    if (!roleIds.isEmpty()) {
      permissions.addAll(usersService.loadPermissionsInRoleIds(roleIds));
    }
    var featurePermissionRelevanceRepository = featurePermissionRelevanceRepositoryProvider.getIfAvailable();
    if (featurePermissionRelevanceRepository != null && tenantId != null && !tenantId.isBlank()) {
      permissions.addAll(featurePermissionRelevanceRepository.findPermissionAuthoritiesByTenantId(tenantId));
    }
    AuthorizationContext authorizationContext = new AuthorizationContext();
    authorizationContext.setUserId(userId);
    authorizationContext.setContextId(contextId);
    authorizationContext.setPermissions(permissions);
    authorizationContext.setIsAdministrator(user.superAdmin());
    authorizationContext.setRoles(roleAuthorityVos.stream().map(RoleGrantedAuthority::getAuthority).toList());
    authorizationContext.setAttributes(attributes);
    return authorizationContext;
  }
}
