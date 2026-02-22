package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Set;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * TenantServiceImpl provides the implementation for TenantService.
 * It extends BaseServiceImpl to manage Tenant entities.
 * This service is used to interact with the persistence layer for tenant entities.
 */
@Service
public class TenantServiceImpl extends BaseServiceImpl<TenantRepository, Tenant, String> implements TenantService {

  /**
   * Constructs a BaseServiceImpl with the specified repository, user context, and details provider service.
   *
   * @param repository                 the repository to be used for entity operations
   * @param authorizationContextHolder 用于访问与用户相关信息的用户上下文
   * @param detailsProviderService     the service providing additional details
   */
  public TenantServiceImpl(
      TenantRepository repository,
      @Autowired(required = false) final AuthorizationContextHolder authorizationContextHolder,
      DetailsProviderService detailsProviderService
  ) {
    super(repository, authorizationContextHolder, detailsProviderService);
  }

  /**
   * Retrieves the set of tenants associated with a specific user ID.
   *
   * @param userId the ID of the user for whom to retrieve associated tenants
   * @return a set of NamedTenantVo representing the tenants associated with the specified user ID
   */
  @Override
  public Set<NamedTenantVo> getTenantsByUserId(String userId) {
    return getRepository().getTenantsByUserId(userId);
  }

  /**
   * Retrieves the set of tenants associated with the currently authenticated user.
   *
   * @return a set of NamedTenantVo representing the tenants associated with the currently authenticated user
   */
  @Override
  public Set<NamedTenantVo> getCurrentUserTenants() {
    String userId = getAuthorizationContext().getUserId();
    return this.getTenantsByUserId(userId);
  }
}
