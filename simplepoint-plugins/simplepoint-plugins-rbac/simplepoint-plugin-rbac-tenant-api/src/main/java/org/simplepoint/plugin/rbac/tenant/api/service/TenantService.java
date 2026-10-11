package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.command.TenantProfileUpdateCommand;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantPackagesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantUsersRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.simplepoint.plugin.rbac.tenant.api.vo.UserRelevanceVo;
import org.simplepoint.remoting.RemoteContract;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * TenantService is an interface that defines methods for managing tenant-related operations. It is
 * part of the RBAC (Role-Based Access Control) module of the SimplePoint application. This service
 * will handle operations related to tenants, such as retrieving tenant information, managing tenant
 * data, and other related functionalities.
 */
@RemoteContract(name = "saas.tenant")
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
   * @return a set of NamedTenantVo objects representing the tenants associated with the current
   *     user
   */
  Set<NamedTenantVo> getCurrentUserTenants();

  /**
   * Loads the active tenant profile with its administrator details.
   *
   * @return current tenant profile
   */
  Tenant getCurrentTenantProfile();

  /**
   * Updates branding and descriptive fields of the active tenant.
   *
   * @param command editable tenant profile fields
   * @return updated tenant profile
   */
  Tenant updateCurrentTenantProfile(TenantProfileUpdateCommand command);

  /**
   * Retrieves the roles available to the currently authenticated user within a tenant.
   *
   * @param tenantId selected tenant identifier
   * @return role authorities available for switching
   */
  Collection<RoleGrantedAuthority> getCurrentUserRoles(String tenantId);

  /**
   * Calculates the authorization context ID for a given tenant ID.
   *
   * @param tenantId the ID of the tenant for which to calculate the authorization context ID
   * @return the calculated authorization context ID as a String
   */
  default String calculateAuthorizationContextId(String tenantId) {
    return calculateAuthorizationContextId(tenantId, null);
  }

  /**
   * Calculates the authorization context ID for a given tenant and optional selected role.
   *
   * @param tenantId the ID of the tenant for which to calculate the authorization context ID
   * @param roleId selected role ID, or null to include all roles
   * @return the calculated authorization context ID as a String
   */
  String calculateAuthorizationContextId(String tenantId, String roleId);

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
  default Page<UserRelevanceVo> ownerItems(Pageable pageable) {
    return ownerItems(null, pageable);
  }

  /** Loads a page of tenant owner candidates matching the optional keyword. */
  Page<UserRelevanceVo> ownerItems(String keyword, Pageable pageable);

  /**
   * Loads global user candidates for the specified tenant member configuration.
   *
   * @param tenantId tenant identifier used for access checks
   * @param pageable pageable
   * @return user candidates
   */
  default Page<UserRelevanceVo> userItems(String tenantId, Pageable pageable) {
    return userItems(tenantId, null, pageable);
  }

  /** Loads a page of tenant members matching the optional keyword. */
  Page<UserRelevanceVo> userItems(String tenantId, String keyword, Pageable pageable);

  /**
   * Loads a SQL-filtered assignment side; null selected preserves the legacy all-candidate
   * selector.
   */
  Page<UserRelevanceVo> userItems(
      String tenantId, Boolean selected, Map<String, String> filters, Pageable pageable);

  /**
   * Returns the candidate query contract after checking management authority over the target
   * tenant.
   */
  Map<String, Object> userItemsSchema(String tenantId);

  /**
   * Loads tenant members for the specified tenant.
   *
   * @param tenantId tenant identifier
   * @return member user ids
   */
  Collection<String> authorizedUsers(String tenantId);

  /**
   * Capability for the selected tenant, using exactly the member-management write policy; never
   * lists accounts.
   */
  Map<String, Boolean> userManagementCapabilities(String tenantId);

  /**
   * Resolves the requested IDs that are active members of a tenant.
   *
   * <p>This narrow lookup supports tenant-safe assignee and copied-user validation without exposing
   * the full member directory to ordinary members.
   */
  Collection<String> resolveActiveMemberIds(String tenantId, Collection<String> userIds);

  /** Resolves existing, non-deleted role authorities within a tenant, in bounded batches. */
  Collection<String> resolveActiveRoleAuthorities(String tenantId, Collection<String> authorities);

  /**
   * Internal notification fan-out only: ordered, bounded active members of tenant roles. Continue
   * after the last returned user ID; ordinary member credentials cannot enumerate it.
   */
  Collection<String> resolveActiveRoleMemberIds(
      String tenantId, Collection<String> authorities, String afterUserId, int limit);

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
