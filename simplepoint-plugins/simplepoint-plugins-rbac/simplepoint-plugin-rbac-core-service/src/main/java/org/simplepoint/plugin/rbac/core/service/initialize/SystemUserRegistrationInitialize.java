/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.initialize;

import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.core.service.properties.UserRegistrationProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Initializes system user registration on application startup.
 *
 * <p>This component automatically registers system users defined in {@link UserRegistrationProperties}.
 * If a user is not already registered, it is saved to {@link UsersService}.
 * </p>
 */
@Slf4j
@Component
@Import(UserRegistrationProperties.class)
public class SystemUserRegistrationInitialize implements ApplicationRunner {

  /**
   * User registration properties containing predefined system users.
   */
  private final UserRegistrationProperties properties;

  /**
   * Service responsible for managing user registration and role assignments.
   */
  private final UsersService usersService;

  /**
   * Constructs an instance of {@code SystemUserRegistrationInitialize}.
   *
   * @param properties   the configuration properties containing system users
   * @param usersService the service for managing users
   * @throws IllegalArgumentException if any parameter is {@code null}
   */
  public SystemUserRegistrationInitialize(
      UserRegistrationProperties properties,
      UsersService usersService
  ) {
    this.properties = properties;
    this.usersService = usersService;
  }

  /**
   * Runs the user registration initialization process on application startup.
   *
   * <p>Iterates through predefined system users, checks if they exist, and registers them if necessary.
   * </p>
   *
   * @param args the application startup arguments
   */
  @Override
  public void run(ApplicationArguments args) {
    log.info("Initialize system user registration");

    properties.getUsers().values().forEach(user -> {
      boolean exists = false;
      try {
        log.info("Initialize system user {}", user.getUsername());

        // Check if the user already exists
        UserDetails existUser = usersService.loadUserByUsername(user.getUsername());
        if (existUser != null) {
          exists = true;
        }
      } catch (Exception ignore) {
        // User does not exist
      }
      if (!exists) {
        usersService.persist(user);
      }
    });

    log.info("Initialize system user registration completed");
  }
}