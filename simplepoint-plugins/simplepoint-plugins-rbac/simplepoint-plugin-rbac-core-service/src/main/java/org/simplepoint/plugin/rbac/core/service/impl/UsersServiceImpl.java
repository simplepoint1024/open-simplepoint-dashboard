/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service implementation class for managing User entities
 * in the RBAC (Role-Based Access Control) system.
 * This class extends BaseServiceImpl to inherit common CRUD operations and implements both
 * UsersService and UserDetailsService interfaces to handle user-specific operations and
 * authentication-related logic.
 */
@Slf4j
@Service
public class UsersServiceImpl extends BaseServiceImpl<UserRepository, User, String>
        implements UsersService {

    private final UserRoleRelevanceRepository userRoleRelevanceRepository;
    private final TenantRepository tenantRepository;
    private final RoleRepository roleRepository;

    /**
     * Optional password encoder for encrypting user passwords.
     * If no encoder is configured, passwords will not be encrypted.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Constructs a UsersServiceImpl with the specified repository and optional metadata sync service.
     *
     * @param passwordEncoder             the optional PasswordEncoder for encrypting user passwords
     * @param usersRepository             the repository used for user operations
     * @param detailsProviderService      the access control service for managing permissions
     * @param userRoleRelevanceRepository the repository for managing UserRoleRelevance entities
     */
    public UsersServiceImpl(
            final PasswordEncoder passwordEncoder,
            final UserRepository usersRepository,
            final DetailsProviderService detailsProviderService,
            final UserRoleRelevanceRepository userRoleRelevanceRepository,
            final TenantRepository tenantRepository,
            final RoleRepository roleRepository
    ) {
        super(usersRepository, detailsProviderService);
        this.passwordEncoder = passwordEncoder;
        this.userRoleRelevanceRepository = userRoleRelevanceRepository;
        this.tenantRepository = tenantRepository;
        this.roleRepository = roleRepository;
    }


    /**
     * Loads a user by their username for authentication purposes.
     * This method retrieves the user from the repository and sets their roles as authorities.
     *
     * @param subject the email/phone of the user to load
     * @return the User object with populated authorities
     * @throws UsernameNotFoundException if no user or multiple
     *                                   users are found with the given username
     */
    @Override
    public UserDetails loadUserByUsername(String subject) throws UsernameNotFoundException {
        var user = loadUserByPhoneOrEmail(subject);
        if (user == null) {
            log.warn("User not found: {}", subject);
            throw new UsernameNotFoundException("User not found: " + subject);
        }
        return user;
    }

    /**
     * Loads roles associated with the given username.
     *
     * @param tenantId the tenant ID to which the user belongs
     * @param userId   the username of the user
     * @return a list of role authorities assigned to the user
     */
    @Override
    public Collection<RoleGrantedAuthority> loadRolesByUserId(String tenantId, String userId) {
        return getRepository().loadRolesByUserId(tenantId, userId);
    }

    /**
     * Loads permissions associated with the given role authorities.
     *
     * @param roleIds a list of role authorities
     * @return a list of RolePermissionsRelevance entities representing permissions assigned to the specified roles
     */
    @Override
    public Collection<String> loadPermissionsInRoleIds(List<String> roleIds) {
        return getRepository().loadPermissionsInRoleIds(roleIds);
    }

    /**
     * Adds a new user to the system.
     * This method validates the uniqueness of the username and
     * optionally encrypts the user's password
     * if a PasswordEncoder is configured.
     *
     * @param entity the User object to add
     * @param <S>    the type of the User entity
     * @return the added User object
     */
    @Override
    public <S extends User> S create(S entity) {
        entity.setPassword(passwordEncoder.encode(entity.getPassword()));
        return super.create(entity);
    }

    @Override
    public <S extends User> User modifyById(S entity) {
        var password = entity.getPassword();
        if (password != null && !password.isEmpty()) {
            entity.setPassword(password.matches("\\A\\$2([ayb])?\\$(\\d\\d)\\$[./0-9A-Za-z]{53}")
                    ? password
                    : passwordEncoder.encode(password)
            );

        }
        return super.modifyById(entity);
    }

    /**
     * Retrieves a collection of role authorities associated with a specific userId.
     *
     * @param userId The userId to filter the role authorities.
     * @return A collection of role authorities for the given userId.
     */
    @Override
    public Collection<String> authorized(String userId) {
        requireTenantOwnerOrAdministratorIfTenantScoped();
        return getRepository().authorized(resolveCurrentTenantScope(), userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Collection<UserRoleRelevance> authorize(UserRoleRelevanceDto dto) {
        requireTenantOwnerOrAdministratorIfTenantScoped();
        Set<String> roleIds = dto.getRoleIds();
        validateRolesBelongToCurrentTenant(roleIds);
        Set<UserRoleRelevance> authorities = new HashSet<>(roleIds.size());
        for (String roleId : roleIds) {
            UserRoleRelevance relevance = new UserRoleRelevance();
            relevance.setUserId(dto.getUserId());
            relevance.setRoleId(roleId);
            applyCurrentTenantIdIfNecessary(relevance);
            authorities.add(relevance);
        }
        Collection<UserRoleRelevance> saved = userRoleRelevanceRepository.saveAll(authorities);
        refreshCurrentTenantPermissionVersion();
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unauthorized(UserRoleRelevanceDto dto) {
        requireTenantOwnerOrAdministratorIfTenantScoped();
        userRoleRelevanceRepository.unauthorized(resolveCurrentTenantScope(), dto.getUserId(), dto.getRoleIds());
        refreshCurrentTenantPermissionVersion();
    }

    @Override
    public User loadUserByPhoneOrEmail(String phoneOrEmail) {
        return userRoleRelevanceRepository.loadUserByPhoneOrEmail(phoneOrEmail);
    }

    private void validateRolesBelongToCurrentTenant(Set<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        String tenantId = resolveCurrentTenantScope();
        Set<String> currentTenantRoleIds = roleRepository.findAllByIds(roleIds).stream()
                .filter(Objects::nonNull)
                .filter(role -> Objects.equals(role.getTenantId(), tenantId))
                .map(Role::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (currentTenantRoleIds.size() == roleIds.size()) {
            return;
        }
        LinkedHashSet<String> invalidRoleIds = new LinkedHashSet<>(roleIds);
        invalidRoleIds.removeAll(currentTenantRoleIds);
        throw new IllegalArgumentException("角色不存在或不属于当前租户: " + String.join(",", invalidRoleIds));
    }

    private void requireTenantOwnerOrAdministratorIfTenantScoped() {
        String tenantId = currentTenantId();
        if (tenantId == null || tenantId.isBlank() || "default".equals(tenantId)) {
            return;
        }
        if (Boolean.TRUE.equals(getAuthorizationContext().getIsAdministrator())) {
            return;
        }
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("租户不存在"));
        if (!Objects.equals(tenant.getOwnerId(), getAuthorizationContext().getUserId())) {
            throw new AccessDeniedException("仅租户所有者可以为成员分配当前租户角色");
        }
    }

    private String resolveCurrentTenantScope() {
        String tenantId = currentTenantId();
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId;
    }

    private void refreshCurrentTenantPermissionVersion() {
        String tenantId = currentTenantId();
        if (tenantId == null || tenantId.isBlank() || "default".equals(tenantId)) {
            return;
        }
        tenantRepository.increasePermissionVersion(Set.of(tenantId));
    }

}

