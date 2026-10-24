/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.cloud.oauth.server.client.ClientIdMetadataDocumentProperties;
import org.simplepoint.cloud.oauth.server.client.ClientIdMetadataDocumentRegisteredClientRepository;
import org.simplepoint.cloud.oauth.server.client.DatabaseOauth2UserService;
import org.simplepoint.cloud.oauth.server.client.DatabaseOidcUserService;
import org.simplepoint.cloud.oauth.server.client.DevicePublicClientAuthenticationConverter;
import org.simplepoint.cloud.oauth.server.client.DevicePublicClientAuthenticationProvider;
import org.simplepoint.cloud.oauth.server.expansion.oidc.OidcConfigurerExpansion;
import org.simplepoint.cloud.oauth.server.handler.LoginAuthenticationFailureHandler;
import org.simplepoint.cloud.oauth.server.handler.LoginAuthenticationSuccessHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;

/** Configuration class for the authorization server. 授权服务器的配置类 */
@Slf4j
@Configuration
@EnableWebSecurity
public class AuthorizationServerConfiguration {

  /** Single logout path shared by the security adapter and metadata planner. */
  public static final String LOGOUT_PATH = "/logout";

  private static final AuthenticationEntryPoint LOGIN_AUTHENTICATION_ENTRY_POINT =
      new GatewayAwareLoginAuthenticationEntryPoint();

  /**
   * Adds standards-based Client ID Metadata Document lookup around persistent clients.
   *
   * @param delegate persistent client repository
   * @param properties CIMD security properties
   * @param objectMapper JSON mapper
   * @return metadata-aware registered client repository
   */
  @Bean
  @Primary
  public RegisteredClientRepository clientIdMetadataRegisteredClientRepository(
      @Qualifier("registeredClientRepositoryImpl") final RegisteredClientRepository delegate,
      final ClientIdMetadataDocumentProperties properties,
      final ObjectMapper objectMapper) {
    return new ClientIdMetadataDocumentRegisteredClientRepository(
        delegate, properties, objectMapper);
  }

  /**
   * Protects the internal service-router endpoint with service JWTs.
   *
   * @param http the HTTP security configuration
   * @param environment Spring environment
   * @return the service-router security filter chain
   * @throws Exception if security configuration fails
   */
  @Bean
  @Order(0)
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
      org.simplepoint.platform.composition.ModulePlan.class)
  public SecurityFilterChain serviceRouterSecurityFilterChain(
      final HttpSecurity http, final Environment environment) throws Exception {
    final String serviceRouterExposePath =
        environment.getProperty(
            "simplepoint.service-router.provider.expose-path",
            "/_simplepoint/service-router/invoke");
    final String requiredAuthority =
        environment.getProperty(
            "simplepoint.service-router.internal-auth.oauth2.required-authority",
            "SCOPE_service-router.invoke");
    http.csrf(AbstractHttpConfigurer::disable);
    http.securityMatcher(new ServiceRouterPathRequestMatcher(serviceRouterExposePath))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().hasAuthority(requiredAuthority))
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
    return http.build();
  }

  /**
   * Defines the security filter chain for the authorization server. 定义授权服务器的安全过滤链
   *
   * @param http the HTTP security configuration HTTP 安全配置
   * @param oidcConfigurerExpansion OIDC configuration expansion OIDC 配置扩展
   * @return the security filter chain 安全过滤链
   * @throws Exception if an error occurs 如果发生错误
   */
  @Bean
  @Order(10)
  public SecurityFilterChain authorizationServerSecurityFilterChain(
      final HttpSecurity http,
      final AuthorizationServerSettings authorizationServerSettings,
      final RegisteredClientRepository registeredClientRepository,
      final ClientIdMetadataDocumentProperties clientMetadataProperties,
      @Autowired(required = false) final OidcConfigurerExpansion oidcConfigurerExpansion)
      throws Exception {
    OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
        new OAuth2AuthorizationServerConfigurer();
    http.addFilterAfter(
        new PendingMfaFilter(),
        org.springframework.security.web.context.SecurityContextHolderFilter.class);
    // Protocol endpoints authenticate clients; browser authorization/consent and logout keep CSRF.
    // SAS itself ignores its entire endpoint matcher during init. Set the final filter's
    // matcher after that initialization so browser consent/device verification remain protected.
    http.csrf(
        csrf ->
            csrf.withObjectPostProcessor(
                new org.springframework.security.config.ObjectPostProcessor<
                    org.springframework.security.web.csrf.CsrfFilter>() {
                  @Override
                  public <O extends org.springframework.security.web.csrf.CsrfFilter> O postProcess(
                      O filter) {
                    filter.setRequireCsrfProtectionMatcher(
                        request ->
                            org.springframework.security.web.csrf.CsrfFilter.DEFAULT_CSRF_MATCHER
                                    .matches(request)
                                && !java.util.Set.of(
                                        "/oauth2/token",
                                        "/oauth2/introspect",
                                        "/oauth2/revoke",
                                        "/oauth2/device_authorization")
                                    .contains(
                                        request
                                            .getRequestURI()
                                            .substring(request.getContextPath().length())));
                    return filter;
                  }
                }));
    http.securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
        .with(
            authorizationServerConfigurer,
            authorizationServer -> {
              authorizationServer
                  .authorizationServerMetadataEndpoint(
                      metadata ->
                          metadata.authorizationServerMetadataCustomizer(
                              builder ->
                                  builder.claim(
                                      "client_id_metadata_document_supported",
                                      clientMetadataProperties.isEnabled())))
                  .clientAuthentication(
                      clientAuthentication ->
                          clientAuthentication
                              .authenticationConverter(
                                  new DevicePublicClientAuthenticationConverter(
                                      authorizationServerSettings))
                              .authenticationProvider(
                                  new DevicePublicClientAuthenticationProvider(
                                      registeredClientRepository)))
                  .deviceAuthorizationEndpoint(endpoint -> endpoint.verificationUri("/activate"))
                  .deviceVerificationEndpoint(
                      endpoint ->
                          endpoint
                              .deviceVerificationResponseHandler(
                                  (request, response, authentication) ->
                                      response.sendRedirect(
                                          request.getContextPath() + "/device-activated"))
                              .errorResponseHandler(
                                  (request, response, exception) ->
                                      response.sendRedirect(
                                          request.getContextPath()
                                              + "/activate?error=invalid_code")));
              if (oidcConfigurerExpansion != null) {
                authorizationServer.oidc(oidcConfigurerExpansion);
              }
            })
        .authorizeHttpRequests(
            authorize ->
                // Require authentication for all requests 需要认证所有请求
                authorize.anyRequest().authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                    LOGIN_AUTHENTICATION_ENTRY_POINT,
                    new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));

    return http.build();
  }

  /**
   * Defines the default security filter chain for general application requests. 定义一般应用请求的默认安全过滤链
   *
   * @param http the HTTP security configuration HTTP 安全配置
   * @param loginAuthenticationSuccessHandler the login authentication success handler 登录认证成功处理器
   * @return the security filter chain 安全过滤链
   * @throws Exception if an error occurs 如果发生错误
   */
  @Bean
  @Order(20)
  public SecurityFilterChain defaultSecurityFilterChain(
      final HttpSecurity http,
      final LoginAuthenticationSuccessHandler loginAuthenticationSuccessHandler,
      final LoginAuthenticationFailureHandler loginAuthenticationFailureHandler,
      final DatabaseOauth2UserService databaseOauth2UserService,
      final DatabaseOidcUserService databaseOidcUserService,
      final org.simplepoint.plugin.rbac.authentication.LoginProtection protection,
      org.springframework.beans.factory.ObjectProvider<
              org.simplepoint.platform.composition.ModulePlan>
          modules)
      throws Exception {
    var plan = modules.getIfAvailable();
    if (plan != null) {
      http.securityMatcher(
          org.simplepoint.platform.http.servlet.ModuleHttpPaths.roots(plan, getClass()).stream()
              .map(root -> root + "/**")
              .toArray(String[]::new));
    }
    http.logout(logout -> logout.logoutUrl(LOGOUT_PATH));
    http.addFilterAfter(
        new PendingMfaFilter(),
        org.springframework.security.web.context.SecurityContextHolderFilter.class);
    http.addFilterBefore(
        new LoginAttemptFilter(protection, loginAuthenticationFailureHandler),
        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
    http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        "/actuator/**",
                        "/static/**",
                        "/webjars/**",
                        "/favicon.ico",
                        "/assets/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/error",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/**.svg",
                        "/.well-known/appspecific/**",
                        "/register",
                        "/login",
                        "/identity.css",
                        "/external-account/link",
                        "/external-account/cancel",
                        "/two-factor/verify",
                        "/two-factor.css")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(
            configurer -> {
              configurer.loginPage("/login").permitAll();
              configurer.successHandler(loginAuthenticationSuccessHandler);
              configurer.failureHandler(loginAuthenticationFailureHandler);
            })
        .oauth2Login(
            configurer -> {
              configurer.loginPage("/login");
              configurer.userInfoEndpoint(
                  userInfo ->
                      userInfo
                          .userService(databaseOauth2UserService)
                          .oidcUserService(databaseOidcUserService));
              configurer.successHandler(loginAuthenticationSuccessHandler);
              configurer.failureHandler(loginAuthenticationFailureHandler);
            })
        .exceptionHandling(
            configurer -> configurer.authenticationEntryPoint(LOGIN_AUTHENTICATION_ENTRY_POINT))
        .requestCache(configurer -> configurer.requestCache(new HttpSessionRequestCache()));

    return http.build();
  }

  /** Installs the guarded password provider for all local password authentication. */
  @Bean
  public org.simplepoint.plugin.rbac.authentication.ProtectedPasswordAuthenticationProvider
      passwordAuthenticationProvider(
          org.simplepoint.plugin.rbac.core.api.service.UserManagementService users,
          org.springframework.security.crypto.password.PasswordEncoder passwords,
          org.simplepoint.plugin.rbac.authentication.LoginProtection protection) {
    return new org.simplepoint.plugin.rbac.authentication.ProtectedPasswordAuthenticationProvider(
        users, passwords, protection);
  }

  /**
   * Provides the authentication manager bean so that it can be injected into controllers such as
   * the TwoFactorController, delegating to Spring Security's configuration.
   */
  @Bean
  public AuthenticationManager authenticationManager(
      final AuthenticationConfiguration authenticationConfiguration) throws Exception {
    return authenticationConfiguration.getAuthenticationManager();
  }

  /**
   * @ Bean.
   */
  @Bean
  public SessionRegistry sessionRegistry() {
    return new SessionRegistryImpl();
  }

  /**
   * @ Bean.
   */
  @Bean
  public HttpSessionEventPublisher httpSessionEventPublisher() {
    return new HttpSessionEventPublisher();
  }

  /**
   * Provides the authorization server settings. 提供授权服务器设置
   *
   * @return the authorization server settings 授权服务器设置
   */
  @Bean
  public AuthorizationServerSettings authorizationServerSettings(final Environment environment) {
    AuthorizationServerSettings.Builder builder = AuthorizationServerSettings.builder();
    String issuer = environment.getProperty("spring.security.oauth2.authorizationserver.issuer");
    if (StringUtils.hasText(issuer)) {
      builder.issuer(issuer);
    }
    return builder.build();
  }

  record ServiceRouterPathRequestMatcher(String exposePath) implements RequestMatcher {

    @Override
    public boolean matches(final HttpServletRequest request) {
      return HttpMethod.POST.matches(request.getMethod()) && matchesPath(request, exposePath);
    }
  }

  private static boolean matchesPath(final HttpServletRequest request, final String exposePath) {
    final String servletPath = request.getServletPath();
    final String requestUri = request.getRequestURI();
    final String contextPath = request.getContextPath();
    if (exposePath.equals(servletPath)) {
      return true;
    }
    if (exposePath.equals(requestUri)) {
      return true;
    }
    return StringUtils.hasText(contextPath)
        && requestUri.startsWith(contextPath)
        && exposePath.equals(requestUri.substring(contextPath.length()));
  }
}
