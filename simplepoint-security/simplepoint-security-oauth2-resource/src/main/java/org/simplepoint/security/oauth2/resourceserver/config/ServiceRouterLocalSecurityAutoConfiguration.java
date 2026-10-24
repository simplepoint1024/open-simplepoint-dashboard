package org.simplepoint.security.oauth2.resourceserver.config;

import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.invocation.LocalInvocationContext;
import com.simplepoint.service.router.invocation.ServiceRouterAccessTokenProvider;
import org.simplepoint.core.AuthorizationGrantedAuthorityLoader;
import org.simplepoint.security.oauth2.resourceserver.ServiceRouterLocalAuthentication;
import org.simplepoint.security.oauth2.resourceserver.delegate.JwtAuthenticationConverterDelegate;
import org.simplepoint.security.token.TokenRevocationService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Adds authenticated local service calls when routing is present and isolation is enabled. */
@AutoConfiguration(after = ResourceServerAutoConfiguration.class)
@ConditionalOnClass(LocalInvocationContext.class)
@ConditionalOnBean(ServiceRouterAccessTokenProvider.class)
public class ServiceRouterLocalSecurityAutoConfiguration {

  /**
   * Registers security as one mandatory context contribution, independent of other contributions.
   */
  @Bean
  @ConditionalOnMissingBean(ServiceRouterLocalAuthentication.class)
  public ServiceRouterLocalAuthentication serviceRouterLocalAuthentication(
      ServiceRouterAccessTokenProvider tokens,
      ServiceRouterProperties properties,
      JwtDecoder decoder,
      AuthorizationGrantedAuthorityLoader authorities,
      TokenRevocationService revocations,
      Environment environment) {
    if (properties.getConsumer().isLocalIsolation()
        && !"oauth2".equalsIgnoreCase(properties.getInternalAuth().getMode())) {
      throw new IllegalStateException(
          "Isolated platform service calls require OAuth2 service credentials");
    }
    return new ServiceRouterLocalAuthentication(
        tokens,
        decoder,
        new JwtAuthenticationConverterDelegate(
            authorities,
            revocations,
            environment.getProperty(
                "simplepoint.security.oauth2.token.audience", "simplepoint-api")));
  }
}
