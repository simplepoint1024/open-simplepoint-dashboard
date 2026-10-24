package org.simplepoint.cloud.oauth.server.browser;

import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.oauth2.resourceserver.composition.ModuleAuthorizationContexts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/** Explicit opt-in browser session transport; stateless JWT APIs remain a separate boundary. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "simplepoint.security.browser.enabled", havingValue = "true")
public class BrowserSessionSecurityConfiguration {
  /** Runs before native OIDC UserInfo, but only without an Authorization header. */
  @Bean
  @Order(5)
  public SecurityFilterChain browserSessionSecurityFilterChain(
      HttpSecurity http, ModuleAuthorizationContexts contexts, UserManagementService users)
      throws Exception {
    java.util.Set<String> endpoints =
        java.util.Set.of(
            BrowserSessionController.USERINFO,
            BrowserSessionController.CSRF,
            BrowserSessionController.LINKS,
            BrowserSessionController.AUTHORIZE);
    http.securityMatcher(
        request ->
            request.getHeader("Authorization") == null
                && (endpoints.contains(
                        request.getRequestURI().substring(request.getContextPath().length()))
                    || contexts.matchesBrowserRequest(request)));
    // Keep the default masked CSRF token and session repository for every unsafe browser method.
    http.authorizeHttpRequests(
        authorize ->
            authorize
                .requestMatchers(BrowserSessionController.AUTHORIZE)
                .permitAll()
                .anyRequest()
                .authenticated());
    http.exceptionHandling(
        exceptions ->
            exceptions.authenticationEntryPoint(
                (request, response, failure) -> {
                  response.setStatus(401);
                  response.setContentType("application/json");
                  response
                      .getWriter()
                      .write(
                          "{\"code\":\"SECURITY_AUTHENTICATION_REQUIRED\","
                              + "\"messageKey\":\"common.error.authenticationRequired\"}");
                }));
    // The public login alias does not need an existing session.
    var identity = new BrowserSessionIdentityFilter(users, java.time.Clock.systemUTC());
    http.addFilterBefore(identity, AuthorizationFilter.class);
    http.addFilterAfter(
        contexts.browserFilter(BrowserSessionIdentityFilter::subject),
        BrowserSessionIdentityFilter.class);
    return http.build();
  }
}
