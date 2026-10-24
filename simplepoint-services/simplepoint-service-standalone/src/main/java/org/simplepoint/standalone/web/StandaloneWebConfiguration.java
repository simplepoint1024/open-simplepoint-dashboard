package org.simplepoint.standalone.web;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import org.simplepoint.cloud.oauth.server.browser.BrowserSessionIdentityFilter;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/** Host-owned frontend infrastructure; module controllers and protocol paths retain their owners. */
@Configuration(proxyBeanMethods = false)
public class StandaloneWebConfiguration {
  /** Public immutable UI assets do not authenticate with the browser cookie. */
  @Bean
  @Order(30)
  public SecurityFilterChain standaloneAssets(HttpSecurity http) throws Exception {
    var paths = new ArrayList<String>(java.util.List.of("/assets/**", "/favicon.ico", "/svg.svg"));
    var indexes = java.util.Collections.list(getClass().getClassLoader().getResources(
        "META-INF/simplepoint/frontend.imports"));
    if (indexes.size() != 1) {
      throw new IllegalStateException("Exactly one packaged frontend index is required");
    }
    try (var input = indexes.getFirst().openStream()) {
      new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().filter(path -> !path.isBlank()).forEach(path -> {
        if (!path.matches("/[a-z][a-z0-9-]*/mf/")) {
          throw new IllegalStateException("Invalid packaged frontend mount");
        }
        paths.add(path + "**");
      });
    }
    String[] matchers = paths.toArray(String[]::new);
    return http.securityMatcher(matchers).csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.GET, matchers).permitAll()
            .requestMatchers(HttpMethod.HEAD, matchers).permitAll().anyRequest().denyAll()).build();
  }

  /** The shell uses the same completed and revalidated identity as business requests. */
  @Bean
  @Order(31)
  public SecurityFilterChain standaloneDocument(HttpSecurity http, UserManagementService users) throws Exception {
    var entry = new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login");
    return http.securityMatcher("/", "/index.html")
        .addFilterBefore(new BrowserSessionIdentityFilter(users, Clock.systemUTC(), entry), AuthorizationFilter.class)
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
        .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entry)).build();
  }
}
