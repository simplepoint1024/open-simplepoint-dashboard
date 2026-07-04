package org.simplepoint.plugin.rbac.core.service.configuration;

import lombok.extern.slf4j.Slf4j;
import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
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
   * Registers a bootstrap contribution for user registration.
   *
   * <p>This method creates a bootstrap contribution that checks for the existence of specified users
   * and creates them if they do not already exist in the system.</p>
   *
   * @param usersService               the service used to manage user details
   * @param userRegistrationProperties the properties containing user registration information
   * @return a platform bootstrap contribution
   */
  @Bean
  public PlatformBootstrapContribution userRegistrationBootstrapContribution(
      UsersService usersService,
      UserRegistrationProperties userRegistrationProperties
  ) {
    return () -> BootstrapContribution.versioned(
        "rbac-core",
        "system",
        "system-user",
        "1",
        100,
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
        })
    );
  }
}
