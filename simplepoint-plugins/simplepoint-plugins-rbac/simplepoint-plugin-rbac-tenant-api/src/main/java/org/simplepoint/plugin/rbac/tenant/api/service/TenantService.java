package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantPackagesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantUsersRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.simplepoint.plugin.rbac.tenant.api.vo.UserRelevanceVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * TenantService is an interface that defines methods for managing tenant-related operations.
 * It is part of the RBAC (Role-Based Access Control) module of the SimplePoint application.
 * This service will handle operations related to tenants, such as retrieving tenant information,
 * managing tenant data, and other related functionalities.
 */
@AmqpRemoteClient(to = "saas.tenant")
public interface TenantService extends BaseService<Tenant, String> {

  /**
   * Retrieves a set of tenants associated with a specific user ID.
   *
   * @param userId the ID of the user for whom to retrieve associated tenants
   * @return a set of NamedTenantVo objects representing the tenants associated with the user
   */
  Set<NamedTenantVo> getTenantsByUserId(String userId);

  /**
   * Retrieves a set of tenants associated with the currently authenticated user.
   *
   * @return a set of NamedTenantVo objects representing the tenants associated with the current user
   */
  Set<NamedTenantVo> getCurrentUserTenants();

  /**
   * Calculates the permission context ID for a given tenant ID.
   *
   * @param tenantId the ID of the tenant for which to calculate the permission context ID
   * @return the calculated permission context ID as a String
   */
  String calculatePermissionContextId(String tenantId);

  /**
   * Loads package codes assigned to the tenant.
   *
   * @param tenantId tenant identifier
   * @return assigned package codes
   */
  Collection<String> authorizedPackages(String tenantId);

  /**
   * Assigns packages to the tenant.
   *
   * @param dto tenant package dto
   * @return saved relevance records
   */
  Collection<TenantPackageRelevance> authorizePackages(TenantPackagesRelevanceDto dto);

  /**
   * Removes package assignments from the tenant.
   *
   * @param tenantId tenant identifier
   * @param packageCodes package codes to remove
   */
  void unauthorizedPackages(String tenantId, Set<String> packageCodes);

  /**
   * Loads owner candidates for tenant forms.
   *
   * @param pageable pageable
   * @return owner candidates
   */
  Page<UserRelevanceVo> ownerItems(Pageable pageable);

  /**
   * Loads user candidates for the specified tenant member configuration.
   *
   * @param tenantId tenant identifier
   * @param pageable pageable
   * @return tenant member candidates
   */
  Page<UserRelevanceVo> userItems(String tenantId, Pageable pageable);

  /**
   * Loads tenant members for the specified tenant.
   *
   * @param tenantId tenant identifier
   * @return member user ids
   */
  Collection<String> authorizedUsers(String tenantId);

  /**
   * Adds tenant members.
   *
   * @param dto tenant user dto
   * @return saved tenant user relations
   */
  Collection<TenantUserRelevance> authorizeUsers(TenantUsersRelevanceDto dto);

  /**
   * Removes tenant members.
   *
   * @param tenantId tenant identifier
   * @param userIds user ids to remove
   */
  void unauthorizedUsers(String tenantId, Set<String> userIds);
}
