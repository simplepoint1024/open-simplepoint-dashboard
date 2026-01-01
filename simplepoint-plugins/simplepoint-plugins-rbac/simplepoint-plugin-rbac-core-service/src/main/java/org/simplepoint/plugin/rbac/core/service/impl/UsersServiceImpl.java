/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.decorator.UserLoginDecorator;
import org.simplepoint.security.entity.PermissionGrantedAuthority;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  /**
   * Optional password encoder for encrypting user passwords.
   * If no encoder is configured, passwords will not be encrypted.
   */
  private final PasswordEncoder passwordEncoder;

  /**
   * Set of user login decorators for customizing the login process.
   */
  private final Set<UserLoginDecorator> userLoginDecorators;

  /**
   * ObjectMapper for JSON operations.
   */
  private final ObjectMapper objectMapper;

  /**
   * Constructs a UsersServiceImpl with the specified repository and optional metadata sync service.
   *
   * @param passwordEncoder             the optional PasswordEncoder for encrypting user passwords
   * @param usersRepository             the repository used for user operations
   * @param detailsProviderService      the access control service for managing permissions
   * @param userRoleRelevanceRepository the repository for managing UserRoleRelevance entities
   * @param userLoginDecorators         the set of user login decorators
   * @param objectMapper                the ObjectMapper for JSON operations
   * @param userContext                 the optional user context for retrieving current user information
   */
  public UsersServiceImpl(
      final PasswordEncoder passwordEncoder,
      final UserRepository usersRepository,
      @Autowired(required = false) final UserContext<BaseUser> userContext,
      final DetailsProviderService detailsProviderService,
      final UserRoleRelevanceRepository userRoleRelevanceRepository,
      final Set<UserLoginDecorator> userLoginDecorators, ObjectMapper objectMapper
  ) {
    super(usersRepository, userContext, detailsProviderService);
    this.passwordEncoder = passwordEncoder;
    this.userRoleRelevanceRepository = userRoleRelevanceRepository;
    this.userLoginDecorators = userLoginDecorators;
    this.objectMapper = objectMapper;
  }


  /**
   * Loads a user by their username for authentication purposes.
   * This method retrieves the user from the repository and sets their roles as authorities.
   *
   * @param username the username of the user to load
   * @return the User object with populated authorities
   * @throws UsernameNotFoundException if no user or multiple
   *                                   users are found with the given username
   */
  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    Map<String, String> ext = new HashMap<>();
    String resolvedUsername = resolveUsername(username, ext);
    var users = findAll(Map.of(User.ACCOUNT_FIELD, resolvedUsername));
    if (users.isEmpty()) {
      log.warn("User not found: {}", resolvedUsername);
      throw new UsernameNotFoundException("User not found: " + resolvedUsername);
    }
    if (users.size() > 1) {
      log.error("Duplicate users found for resolvedUsername: {}", resolvedUsername);
      throw new IllegalStateException("Duplicate users found: " + resolvedUsername);
    }

    var user = users.getFirst();
    if (user.getDecorator() == null) {
      user.setDecorator(objectMapper.createObjectNode());
    }
    List<String> roles = loadRolesByUsername(resolvedUsername);
    if (user.getSuperAdmin()) {
      roles.add("SYSTEM");
    }
    List<GrantedAuthority> authorities = new ArrayList<>();
    for (String role : roles) {
      var permission = "ROLE_" + role;
      authorities.add(new SimpleGrantedAuthority(permission));
    }
    // 将额外的权限提供者的权限添加到用户权限中
    List<RolePermissionsRelevance> permissions = loadPermissionsInRoleAuthorities(roles);
    if (!permissions.isEmpty()) {
      authorities.addAll(permissions.stream().map(pm -> new PermissionGrantedAuthority(pm.getRoleAuthority(), pm.getPermissionAuthority())).toList());
    }
    user.setAuthorities(authorities);
    return afterLogin(user, ext);
  }

  /**
   * Resolves the username using the configured UserLoginDecorators.
   *
   * @param tenantUsername the username provided by the tenant
   * @param ext            additional context information
   * @return the resolved username
   */
  private String resolveUsername(String tenantUsername, Map<String, String> ext) {
    String username = tenantUsername;
    for (UserLoginDecorator decorator : userLoginDecorators) {
      username = decorator.resolveUsername(username, ext);
    }
    return username;
  }

  /**
   * Applies post-login decorations to the UserDetails using the configured UserLoginDecorators.
   *
   * @param userDetails the original UserDetails
   * @param ext         additional context information
   * @return the decorated UserDetails
   */
  private UserDetails afterLogin(UserDetails userDetails, Map<String, String> ext) {
    UserDetails decorated = userDetails;
    for (UserLoginDecorator decorator : userLoginDecorators) {
      decorated = decorator.afterLogin(decorated, ext);
    }
    return decorated;
  }


  /**
   * Loads roles associated with the given username.
   *
   * @param username the username of the user
   * @return a list of role authorities assigned to the user
   */
  @Override
  public List<String> loadRolesByUsername(String username) {
    return getRepository().loadRolesByUsername(username);
  }

  /**
   * Loads permissions associated with the given role authorities.
   *
   * @param roleAuthorities a list of role authorities
   * @return a list of RolePermissionsRelevance entities representing permissions assigned to the specified roles
   */
  @Override
  public List<RolePermissionsRelevance> loadPermissionsInRoleAuthorities(List<String> roleAuthorities) {
    return getRepository().loadPermissionsInRoleAuthorities(roleAuthorities);
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
  public <S extends User> S persist(S entity) {
    entity.setPassword(passwordEncoder.encode(entity.getPassword()));
    return super.persist(entity);
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
   * Retrieves a collection of role authorities associated with a specific username.
   *
   * @param username The username to filter the role authorities.
   * @return A collection of role authorities for the given username.
   */
  @Override
  public Collection<String> authorized(String username) {
    return getRepository().authorized(username);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<UserRoleRelevance> authorize(UserRoleRelevanceDto dto) {
    Set<String> roleAuthorities = dto.getRoleAuthorities();
    Set<UserRoleRelevance> authorities = new HashSet<>(roleAuthorities.size());
    for (String roleAuthority : roleAuthorities) {
      UserRoleRelevance relevance = new UserRoleRelevance();
      relevance.setUsername(dto.getUsername());
      relevance.setAuthority(roleAuthority);
      authorities.add(relevance);
    }
    return userRoleRelevanceRepository.saveAll(authorities);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorized(UserRoleRelevanceDto dto) {
    userRoleRelevanceRepository.unauthorized(dto.getUsername(), dto.getRoleAuthorities());
  }

}

