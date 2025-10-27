package org.simplepoint.security.oauth2.resourceserver.config;

import static org.springframework.security.config.Customizer.withDefaults;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.oauth2.sdk.GeneralException;
import java.io.IOException;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.security.oauth2.resourceserver.ResourceServerUserContext;
import org.simplepoint.security.oauth2.resourceserver.context.DefaultResourceServerUserContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Auto-configuration for the resource server.
 * 资源服务器的自动配置类
 */
@AutoConfiguration
public class ResourceServerAutoConfiguration {

  /**
   * Creates a UserContext bean for the OAuth2 resource server.
   * 创建 OAuth2 资源服务器的 UserContext Bean
   *
   * @param resourceServerProperties the properties of the OAuth2 resource server
   *                                 OAuth2 资源服务器的属性
   * @param objectMapper             the ObjectMapper for JSON processing
   * @return a UserContext instance for handling authentication 处理身份验证的 UserContext 实例
   * @throws GeneralException if an unexpected error occurs
   *                          如果发生未预期的错误
   * @throws IOException      if there is an issue reading provider metadata
   *                          如果读取提供者元数据时出现问题
   */
  @Bean("resourceServerUserContext")
  @ConditionalOnMissingBean(ResourceServerUserContext.class)
  public ResourceServerUserContext<BaseUser> resourceServerUserContext(
      final OAuth2ResourceServerProperties resourceServerProperties,
      final ObjectMapper objectMapper
  ) throws GeneralException, IOException {
    return new DefaultResourceServerUserContext(objectMapper, resourceServerProperties);
  }

  /**
   * Configures the security filter chain for the application, enforcing authentication
   * for all requests and enabling OAuth2 resource server with JWT authentication.
   *
   * <p>This bean defines a security filter that ensures all incoming requests are authenticated
   * and utilizes OAuth2 JWT tokens for identity verification.</p>
   *
   * @param http the {@link HttpSecurity} instance used for security configurations
   * @return a fully configured {@link SecurityFilterChain} instance
   * @throws Exception if an error occurs during the security configuration process
   */
  @Bean
  public SecurityFilterChain securityWebFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable);
    return http
        .authorizeHttpRequests(request -> request
            .requestMatchers("/actuator/**", "/static/**", "/v3/api-docs/**", "/swagger-ui/**", "/error", "/css/**", "/js/**", "/images/**")
            .permitAll()
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(configurer -> configurer.jwt(withDefaults()))
        .build();
  }
}

