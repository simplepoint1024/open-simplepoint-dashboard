/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.plugin.rbac.core.api.pojo.command.ChangePasswordCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.command.UserProfileUpdateCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserPickerItem;
import org.simplepoint.remoting.RemoteContract;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * Service interface for managing User entities in the RBAC (Role-Based Access Control) system.
 * This interface extends BaseService to inherit common service operations and provides
 * additional methods for handling user-specific functionality.
 */
@RemoteContract(name = "security.user")
public interface UsersService extends BaseService<User, String>, UserDetailsService {

  /**
     * Loads a user for authorization-context calculation using only the user ID.
     *
     * @param userId the user ID to load
     * @return the user when present
     */
  Optional<User> findByIdForAuthorization(String userId);

  /**
   * Loads active users in one query for trusted internal profile decoration.
   *
   * @param userIds user IDs to load
   * @return active users matching the IDs
   */
  Collection<User> findAllByIdsForAuthorization(Collection<String> userIds);

  /**
     * Loads the roles associated with the given username.
     * This method retrieves a list of role authorities assigned to the specified user.
     *
     * @param tenantId the tenant ID to which the user belongs
     * @param userId   the username of the user whose roles are to be loaded
     * @return a list of role authorities associated with the user
     */
  Collection<RoleGrantedAuthority> loadRolesByUserId(String tenantId, String userId);

  /**
     * Loads resource codes associated with the given roles.
     *
     * @param roleIds a list of role ids
     * @return resource codes granted to the specified roles
     */
  Collection<String> loadResourcesInRoleIds(List<String> roleIds);

  /**
     * Retrieve a collection of role authorities associated with a specific userId.
     *
     * @param userId The userId to filter the role authorities.
     * @return A collection of role authorities for the given userId.
     */
  Collection<String> authorized(String userId);


  /**
     * Authorize roles based on the provided RoleSelectDto.
     *
     * @param dto The RoleSelectDto containing authorization criteria.
     * @return A collection of UserRoleRelevance entities that match the authorization criteria.
     */
  Collection<UserRoleRelevance> authorize(UserRoleRelevanceDto dto);

  /**
     * unauthorized roles based on the provided RoleSelectDto.
     *
     * @param dto The RoleSelectDto containing unauthorization criteria.
     */
  void unauthorized(UserRoleRelevanceDto dto);

  /**
     * Changes the password of the user identified by userId.
     * Verifies the current password, checks new/confirm match, then updates.
     *
     * @param userId  the ID of the user whose password is to be changed
     * @param command the change-password command with currentPassword, newPassword, confirmPassword
     */
  void changePassword(String userId, ChangePasswordCommand command);

  /**
   * Updates the editable profile fields of the current user without exposing
   * account permissions, credentials, or verification flags to mass assignment.
   *
   * @param userId authenticated user ID
   * @param command editable profile fields
   * @return updated user
   */
  User updateCurrentProfile(String userId, UserProfileUpdateCommand command);

  /**
     * Loads a user by their phone number or email address.
     *
     * @param phoneOrEmail the phone number or email address of the user to load
     * @return the User entity corresponding to the provided phone number or email address
     */
  User loadUserByPhoneOrEmail(String phoneOrEmail);

  /**
     * Returns a paginated list of roles available for assignment in the current tenant scope.
     * Uses the current X-Tenant-Id (org or personal tenant) as the scope.
     *
     * @param pageable the pagination parameters
     * @return a page of roles in the current tenant scope
     */
  Page<RoleRelevanceVo> roleCandidates(Pageable pageable);

  /**
   * Searches the global user directory for a remote picker. Empty and overly
   * short keywords intentionally return no rows so a form never loads the full
   * directory on open.
   *
   * @param keyword email or phone-number prefix
   * @param pageable bounded page request
   * @return matching enabled users
   */
  Page<UserPickerItem> searchPickerItems(String keyword, Pageable pageable);

  /**
   * Resolves selected IDs for single- and multi-select form value hydration.
   *
   * @param userIds selected user IDs
   * @return selected users in the requested order where possible
   */
  Collection<UserPickerItem> resolvePickerItems(Collection<String> userIds);
}
