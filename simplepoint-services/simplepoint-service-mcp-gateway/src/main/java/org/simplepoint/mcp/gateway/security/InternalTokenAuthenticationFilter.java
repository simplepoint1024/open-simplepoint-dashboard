package org.simplepoint.mcp.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the control plane on the private MCP Gateway API.
 */
public class InternalTokenAuthenticationFilter extends OncePerRequestFilter {

  public static final String ROLE = "ROLE_MCP_GATEWAY_SERVICE";

  private final McpGatewayProperties properties;

  /**
   * Creates the filter.
   *
   * @param properties gateway properties
   */
  public InternalTokenAuthenticationFilter(final McpGatewayProperties properties) {
    this.properties = properties;
  }

  @Override
  protected boolean shouldNotFilter(final HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/internal/mcp/");
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain
  ) throws ServletException, IOException {
    String expected = properties.getInternalToken();
    String provided = request.getHeader(properties.getInternalHeaderName());
    if (!StringUtils.hasText(expected)
        || !StringUtils.hasText(provided)
        || !constantTimeEquals(provided, expected)) {
      response.sendError(HttpStatus.UNAUTHORIZED.value(), "MCP Gateway internal token is invalid");
      return;
    }
    UsernamePasswordAuthenticationToken authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            "ai-control-plane",
            provided,
            List.of(new SimpleGrantedAuthority(ROLE))
        );
    SecurityContextHolder.getContext().setAuthentication(authentication);
    try {
      filterChain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static boolean constantTimeEquals(final String left, final String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8),
        right.getBytes(StandardCharsets.UTF_8)
    );
  }
}
