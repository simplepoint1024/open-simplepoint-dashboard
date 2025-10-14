/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.impl;

import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.plugin.rbac.core.api.repository.UserRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.simplepoint.security.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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

  /**
   * Optional password encoder for encrypting user passwords.
   * If no encoder is configured, passwords will not be encrypted.
   */
  private final PasswordEncoder passwordEncoder;

  /**
   * Constructs a UsersServiceImpl with the specified repository and optional metadata sync service.
   *
   * @param passwordEncoder        the optional PasswordEncoder for encrypting user passwords
   * @param usersRepository        the repository used for user operations
   * @param detailsProviderService the access control service for managing permissions
   */
  public UsersServiceImpl(
      @Autowired(required = false) final PasswordEncoder passwordEncoder,
      final UserRepository usersRepository,
      @Autowired(required = false) final UserContext<BaseUser> userContext,
      final DetailsProviderService detailsProviderService
  ) {
    super(usersRepository, userContext, detailsProviderService);
    this.passwordEncoder = passwordEncoder;
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
  public User loadUserByUsername(String username) throws UsernameNotFoundException {
    var users = findAll(Collections.singletonMap(User.ACCOUNT_FIELD, username));
    if (users.isEmpty()) {
      log.info("No users found for username {}", username);
      return null;
    }
    if (users.size() > 1) {
      log.warn("More than one user found for username: {}", username);
    }
    var user = users.get(0);
    List<String> roles = this.loadRolesByUsername(username);
    user.setAuthorities(roles.stream().map(SimpleGrantedAuthority::new).toList());
    return user;
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
   * @return a list of RolePermissionsRelevance entities representing permissions
   *         assigned to the specified roles
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
   * @throws Exception if an error occurs during the addition or if the username already exists
   */
  @Override
  public <S extends User> S add(S entity) throws Exception {
    return lock(getClass().getName() + ".add", () -> {
      if (passwordEncoder != null) {
        entity.setPassword(passwordEncoder.encode(entity.getPassword()));
      } else {
        log.warn("Password encryption is not configured!");
      }
      User user = new User();
      user.setUsername(entity.getUsername());
      if (exists(user)) {
        throw new UsernameNotFoundException("The user name already exists!");
      }
      return super.add(entity);
    }, 30, 30);
  }

  @Override
  public <S extends User> User modifyById(S entity) throws Exception {
    var password = entity.getPassword();
    if (password != null && !password.isEmpty()) {
      entity.setPassword(password.matches("\\A\\$2([ayb])?\\$(\\d\\d)\\$[./0-9A-Za-z]{53}")
          ? password
          : passwordEncoder.encode(password)
      );

    }
    return super.modifyById(entity);
  }
}

