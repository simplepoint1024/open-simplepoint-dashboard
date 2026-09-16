package org.simplepoint.plugin.ai.runtime.rest.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
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
 * Isolates Tool Runtime node control traffic from browser and Gateway APIs.
 */
@Configuration(proxyBeanMethods = false)
public class AiRuntimeInternalSecurityConfiguration {

  /**
   * Configures a dedicated node-identity chain before ordinary AI security.
   */
  @org.springframework.context.annotation.Bean
  @Order(-1)
  public SecurityFilterChain aiRuntimeInternalSecurityFilterChain(
      final HttpSecurity http,
      final AiRuntimeProperties properties
  ) throws Exception {
    RuntimeInternalTokenFilter filter = new RuntimeInternalTokenFilter(properties);
    return http
        .securityMatcher("/internal/runtime/**")
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests
            .anyRequest().hasRole("TOOL_RUNTIME_NODE"))
        .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
        .build();
  }

  static final class RuntimeInternalTokenFilter extends OncePerRequestFilter {

    private final AiRuntimeProperties properties;

    RuntimeInternalTokenFilter(final AiRuntimeProperties properties) {
      this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
        final HttpServletRequest request,
        final HttpServletResponse response,
        final FilterChain filterChain
    ) throws ServletException, IOException {
      String expected = properties.getControlInternalToken();
      String provided = request.getHeader(properties.getControlInternalHeader());
      String principal = Boolean.TRUE.equals(properties.getMtlsEnabled())
          ? mtlsPrincipal(request) : tokenPrincipal(expected, provided);
      if (!StringUtils.hasText(principal)) {
        response.sendError(
            HttpStatus.UNAUTHORIZED.value(),
            "Tool Runtime node identity is invalid"
        );
        return;
      }
      UsernamePasswordAuthenticationToken authentication =
          UsernamePasswordAuthenticationToken.authenticated(
              principal,
              "",
              List.of(new SimpleGrantedAuthority("ROLE_TOOL_RUNTIME_NODE"))
          );
      SecurityContextHolder.getContext().setAuthentication(authentication);
      try {
        filterChain.doFilter(request, response);
      } finally {
        SecurityContextHolder.clearContext();
      }
    }

    private String tokenPrincipal(
        final String expected,
        final String provided
    ) {
      return StringUtils.hasText(expected)
          && StringUtils.hasText(provided)
          && constantTimeEquals(provided, expected)
          ? "tool-runtime-node" : null;
    }

    private String mtlsPrincipal(final HttpServletRequest request) {
      Object value = request.getAttribute(
          "jakarta.servlet.request.X509Certificate"
      );
      if (!(value instanceof X509Certificate[] certificates)
          || certificates.length == 0) {
        return null;
      }
      X509Certificate certificate = certificates[0];
      try {
        certificate.checkValidity();
        String nodeId = requestNodeId(request.getRequestURI());
        String expectedIdentity =
            properties.getMtlsNodeIdentityPrefix() + nodeId;
        return hasUriIdentity(certificate, expectedIdentity) ? nodeId : null;
      } catch (java.security.cert.CertificateException
               | IllegalArgumentException ex) {
        return null;
      }
    }

    private String requestNodeId(final String requestUri) {
      String prefix = "/internal/runtime/nodes/";
      if (requestUri == null || !requestUri.startsWith(prefix)) {
        throw new IllegalArgumentException("Runtime node path is invalid");
      }
      String remainder = requestUri.substring(prefix.length());
      int separator = remainder.indexOf('/');
      String nodeId = separator < 0
          ? remainder : remainder.substring(0, separator);
      if (!StringUtils.hasText(nodeId)
          || !nodeId.matches("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")) {
        throw new IllegalArgumentException("Runtime node path is invalid");
      }
      return nodeId;
    }

    private boolean hasUriIdentity(
        final X509Certificate certificate,
        final String expected
    ) throws CertificateParsingException {
      Collection<List<?>> names = certificate.getSubjectAlternativeNames();
      if (names == null) {
        return false;
      }
      return names.stream().anyMatch(name ->
          name.size() >= 2
              && Objects.equals(name.get(0), 6)
              && Objects.equals(name.get(1), expected)
      );
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
