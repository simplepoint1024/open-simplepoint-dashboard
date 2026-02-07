/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.properties;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import lombok.Data;
import org.simplepoint.api.data.DataInitializeManager;
import org.simplepoint.security.entity.User;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;


/**
 * UserRegistrationProperties is a configuration class that loads user registration settings
 * from application properties with the prefix "simplepoint.security".
 * This class provides a mechanism to store and manage registered users dynamically.
 * It implements {@link InitializingBean} to allow initialization logic after properties are set.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "simplepoint.security")
public class UserRegistrationProperties {

  /**
   * Stores registered users with their associated information.
   */
  private final Set<User> users = new HashSet<>();

  /**
   * Retrieves an unmodifiable view of the registered users.
   *
   * @return an immutable map containing user registrations
   */
  public Set<User> getUsers() {
    return Collections.unmodifiableSet(users);
  }

  /**
   * Adds multiple users to the registration map.
   * Existing users may be overridden if the same keys are provided.
   *
   * @param users the map of users to add
   */
  public void setUsers(Set<User> users) {
    this.users.addAll(users);
  }
}
