/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.service;

import org.simplepoint.api.base.BaseService;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.Collection;
import java.util.List;

/**
 * Service interface for managing User entities in the RBAC (Role-Based Access Control) system.
 * This interface extends BaseService to inherit common service operations and provides
 * additional methods for handling user-specific functionality.
 */
@AmqpRemoteClient(to = "security.user")
public interface UsersService extends BaseService<User, String>, UserDetailsService {

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
     * Loads permissions associated with the given role authorities.
     * This method retrieves a list of RolePermissionsRelevance entities that represent
     * the permissions assigned to the specified roles.
     *
     * @param roleIds a list of role authorities for which to load permissions
     * @return a list of RolePermissionsRelevance associated with the specified role authorities
     */
    Collection<String> loadPermissionsInRoleIds(List<String> roleIds);

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
     * Loads a user by their phone number or email address.
     *
     * @param phoneOrEmail the phone number or email address of the user to load
     * @return the User entity corresponding to the provided phone number or email address
     */
    User loadUserByPhoneOrEmail(String phoneOrEmail);
}
