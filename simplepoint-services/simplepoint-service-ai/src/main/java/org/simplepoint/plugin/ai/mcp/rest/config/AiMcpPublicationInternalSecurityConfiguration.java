package org.simplepoint.plugin.ai.mcp.rest.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.simplepoint.plugin.ai.mcp.api.properties.AiMcpProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Isolates the Gateway-to-control-plane publication API from browser APIs.
 */
@Configuration(proxyBeanMethods = false)
public class AiMcpPublicationInternalSecurityConfiguration {

  /**
   * Configures a dedicated shared-token chain before the ordinary AI resource server.
   */
  @org.springframework.context.annotation.Bean
  @Order(0)
  public SecurityFilterChain aiMcpPublicationInternalSecurityFilterChain(
      final HttpSecurity http,
      final AiMcpProperties properties
  ) throws Exception {
    PublicationInternalTokenFilter filter = new PublicationInternalTokenFilter(properties);
    return http
        .securityMatcher("/internal/mcp/publications/**")
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests
            .anyRequest().hasRole("MCP_GATEWAY_SERVICE"))
        .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
        .build();
  }

  static final class PublicationInternalTokenFilter extends OncePerRequestFilter {

    private final AiMcpProperties properties;

    PublicationInternalTokenFilter(final AiMcpProperties properties) {
      this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
        final HttpServletRequest request,
        final HttpServletResponse response,
        final FilterChain filterChain
    ) throws ServletException, IOException {
      String expected = properties.getGatewayInternalToken();
      String provided = request.getHeader(properties.getGatewayInternalHeader());
      if (!StringUtils.hasText(expected)
          || !StringUtils.hasText(provided)
          || !constantTimeEquals(provided, expected)) {
        response.sendError(
            HttpStatus.UNAUTHORIZED.value(),
            "MCP Gateway internal token is invalid"
        );
        return;
      }
      UsernamePasswordAuthenticationToken authentication =
          UsernamePasswordAuthenticationToken.authenticated(
              "mcp-gateway",
              provided,
              List.of(new SimpleGrantedAuthority("ROLE_MCP_GATEWAY_SERVICE"))
          );
      SecurityContextHolder.getContext().setAuthentication(authentication);
      try {
        filterChain.doFilter(request, response);
      } finally {
        SecurityContextHolder.clearContext();
      }
    }

    private static boolean constantTimeEquals(
        final String left,
        final String right
    ) {
      return MessageDigest.isEqual(
          left.getBytes(StandardCharsets.UTF_8),
          right.getBytes(StandardCharsets.UTF_8)
      );
    }
  }
}
