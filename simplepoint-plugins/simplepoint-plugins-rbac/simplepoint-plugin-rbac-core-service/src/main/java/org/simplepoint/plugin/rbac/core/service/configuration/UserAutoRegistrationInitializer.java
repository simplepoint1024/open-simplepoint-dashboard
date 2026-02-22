package org.simplepoint.plugin.rbac.core.service.configuration;

import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.data.DataInitRegister;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.core.service.properties.UserRegistrationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * UserAutoRegistrationInitializer is responsible for initializing user registration data during application startup.
 */
@Slf4j
@Component
public class UserAutoRegistrationInitializer {

  /**
   * Registers a data initialization task for user registration.
   *
   * <p>This method creates a data initialization task that checks for the existence of specified users
   * and creates them if they do not already exist in the system. The task is registered under the module name "system-user".</p>
   *
   * @param usersService               the service used to manage user details
   * @param userRegistrationProperties the properties containing user registration information
   * @return a DataInitRegister instance that registers the user registration initialization task
   */
  @Bean
  public DataInitRegister userRegistrationDataInitRegister(
      UsersService usersService,
      UserRegistrationProperties userRegistrationProperties
  ) {
    return () -> new org.simplepoint.api.data.InitTask(
        "system-user",
        () -> userRegistrationProperties.getUsers().forEach(user -> {
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
            usersService.create(user);
          }
        }));
  }
}
