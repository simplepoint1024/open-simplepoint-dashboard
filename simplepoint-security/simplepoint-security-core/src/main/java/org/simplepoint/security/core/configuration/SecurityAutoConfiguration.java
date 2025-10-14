package org.simplepoint.security.core.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Auto-configuration class for setting up security-related beans.
 * This configuration provides a PasswordEncoder bean to handle password encryption
 * using the BCrypt hashing algorithm.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityAutoConfiguration {

  /**
   * Defines a PasswordEncoder bean.
   * This bean uses BCryptPasswordEncoder to securely hash and encode passwords.
   *
   * @return a PasswordEncoder instance configured with the BCrypt algorithm
   */
  @Bean
  public PasswordEncoder getPasswordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
