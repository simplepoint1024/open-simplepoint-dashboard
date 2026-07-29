package org.simplepoint.mcp.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.simplepoint.mcp.gateway.publication.McpPublicationRateLimiter;
import org.simplepoint.mcp.gateway.publication.McpPublicationRegistry;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces publication-specific audience, scopes, and distributed rate limits.
 */
public class McpPublicationAuthorizationFilter extends OncePerRequestFilter {

  private final McpPublicationRegistry registry;

  private final McpPublicationRateLimiter rateLimiter;

  /**
   * Creates the publication authorization policy filter.
   */
  public McpPublicationAuthorizationFilter(
      final McpPublicationRegistry registry,
      final McpPublicationRateLimiter rateLimiter
  ) {
    this.registry = registry;
    this.rateLimiter = rateLimiter;
  }

  @Override
  protected boolean shouldNotFilter(final HttpServletRequest request) {
    return publicationCode(request) == null;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain
  ) throws ServletException, IOException {
    String code = publicationCode(request);
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
        || !authentication.isAuthenticated()) {
      filterChain.doFilter(request, response);
      return;
    }
    McpPublicationManifest manifest;
    try {
      manifest = registry.manifest(code);
    } catch (IllegalArgumentException ex) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    } catch (RuntimeException ex) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    if (!jwtAuthentication.getToken().getAudience()
        .contains(manifest.canonicalResourceUri())) {
      unauthorized(
          response,
          manifest.canonicalResourceUri(),
          "invalid_token"
      );
      return;
    }
    Set<String> tokenScopes = tokenScopes(jwtAuthentication);
    if (!tokenScopes.containsAll(manifest.requiredScopes())) {
      response.setHeader(
          "WWW-Authenticate",
          "Bearer error=\"insufficient_scope\", scope=\""
              + String.join(" ", manifest.requiredScopes()) + "\""
      );
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    String subject = jwtAuthentication.getToken().getSubject();
    String clientId = firstNonBlank(
        jwtAuthentication.getToken().getClaimAsString("client_id"),
        jwtAuthentication.getToken().getClaimAsString("azp")
    );
    if (HttpMethod.POST.matches(request.getMethod())) {
      try {
        if (!rateLimiter.tryAcquire(
            manifest.code(),
            firstNonBlank(clientId, subject),
            manifest.rateLimitPerMinute()
        )) {
          response.setHeader("Retry-After", "60");
          response.sendError(429, "MCP publication rate limit exceeded");
          return;
        }
      } catch (RuntimeException ex) {
        response.sendError(
            HttpServletResponse.SC_SERVICE_UNAVAILABLE,
            "MCP publication rate limiter is unavailable"
        );
        return;
      }
    }
    request.setAttribute(McpPublicationRegistry.SUBJECT_CONTEXT_KEY, subject);
    request.setAttribute(McpPublicationRegistry.CLIENT_CONTEXT_KEY, clientId);
    filterChain.doFilter(request, response);
  }

  static void unauthorized(
      final HttpServletResponse response,
      final String canonicalResourceUri,
      final String error
  ) throws IOException {
    URI resource = URI.create(canonicalResourceUri);
    String metadataUri = resource.getScheme() + "://" + resource.getAuthority()
        + "/.well-known/oauth-protected-resource" + resource.getPath();
    response.setHeader(
        "WWW-Authenticate",
        "Bearer resource_metadata=\"" + metadataUri + "\", error=\"" + error + "\""
    );
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
  }

  static String publicationCode(final HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    String path = contextPath == null || contextPath.isEmpty()
        ? uri : uri.substring(contextPath.length());
    if (!path.matches("/mcp/[a-z0-9][a-z0-9_-]{0,127}")) {
      return null;
    }
    return path.substring("/mcp/".length());
  }

  private static Set<String> tokenScopes(
      final JwtAuthenticationToken authentication
  ) {
    Set<String> scopes = new LinkedHashSet<>();
    Object claim = authentication.getToken().getClaim("scope");
    if (claim instanceof String value) {
      scopes.addAll(Arrays.asList(value.split("\\s+")));
    } else if (claim instanceof List<?> values) {
      values.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .forEach(scopes::add);
    }
    authentication.getAuthorities().forEach(authority -> {
      if (authority.getAuthority().startsWith("SCOPE_")) {
        scopes.add(authority.getAuthority().substring("SCOPE_".length()));
      }
    });
    return scopes;
  }

  private static String firstNonBlank(final String first, final String second) {
    return first != null && !first.isBlank() ? first : second;
  }
}
