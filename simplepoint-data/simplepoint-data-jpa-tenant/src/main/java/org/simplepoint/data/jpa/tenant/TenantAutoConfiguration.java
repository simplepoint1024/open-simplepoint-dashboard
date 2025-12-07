package org.simplepoint.data.jpa.tenant;

import org.simplepoint.data.jpa.tenant.decorator.TenantTokenDecorator;
import org.simplepoint.data.jpa.tenant.decorator.TenantUserLoginDecorator;
import org.simplepoint.security.decorator.TokenDecorator;
import org.simplepoint.security.decorator.UserLoginDecorator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Auto-configuration class for tenant-related components.
 * 租户相关组件的自动配置类
 */
@AutoConfiguration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantAutoConfiguration {

  /**
   * Provides the tenant-specific UserLoginDecorator bean.
   * 提供租户特定的 UserLoginDecorator Bean
   *
   * @return the tenant UserLoginDecorator 租户 UserLoginDecorator
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public UserLoginDecorator tenantUserLoginDecorator() {
    return new TenantUserLoginDecorator();
  }

  /**
   * Provides the tenant-specific AccessTokenDecorator bean.
   * 提供租户特定的 AccessTokenDecorator Bean
   *
   * @return the tenant AccessTokenDecorator 租户 AccessTokenDecorator
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public TokenDecorator tenantAccessTokenDecorator() {
    return new TenantTokenDecorator();
  }
}
