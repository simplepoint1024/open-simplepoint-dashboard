/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.properties;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.simplepoint.security.entity.User;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;


/**
 * UserRegistrationProperties is a configuration class that loads user registration settings
 * from application properties with the prefix "simplepoint.security".
 * This class provides a mechanism to store and manage registered users dynamically.
 * It implements {@link InitializingBean} to allow initialization logic after properties are set.
 */
@ConfigurationProperties(prefix = "simplepoint.security")
public class UserRegistrationProperties implements InitializingBean {

  /**
   * Stores registered users with their associated information.
   */
  private final Map<String, User> users = new HashMap<>();

  /**
   * Called after properties are set. This method can be used to perform initialization logic,
   * but currently does not implement any operations.
   */
  @Override
  public void afterPropertiesSet() {
  }

  /**
   * Retrieves an unmodifiable view of the registered users.
   *
   * @return an immutable map containing user registrations
   */
  public Map<String, User> getUsers() {
    return Collections.unmodifiableMap(users);
  }

  /**
   * Adds multiple users to the registration map.
   * Existing users may be overridden if the same keys are provided.
   *
   * @param users the map of users to add
   */
  public void setUsers(Map<String, User> users) {
    this.users.putAll(users);
  }
}
