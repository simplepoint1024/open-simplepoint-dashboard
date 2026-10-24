package org.simplepoint.cloud.oauth.server.browser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.simplepoint.cloud.oauth.server.service.LoginSession;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Revalidates the completed local identity before a browser request reaches any business module.
 */
public final class BrowserSessionIdentityFilter extends OncePerRequestFilter {
  private final UserManagementService users;
  private final Clock clock;
  private final org.springframework.security.web.AuthenticationEntryPoint rejected;

  /** Uses identity-owned management; never reads identity persistence from this HTTP adapter. */
  public BrowserSessionIdentityFilter(UserManagementService users, Clock clock) {
    this(users, clock, (request, response, failure) -> {
      response.setStatus(401);
      response.setContentType("application/json");
      response.getWriter().write("{\"code\":\"SECURITY_AUTHENTICATION_REQUIRED\","
          + "\"messageKey\":\"common.error.authenticationRequired\"}");
    });
  }

  /** Allows the document boundary to redirect to login while APIs retain their JSON 401. */
  public BrowserSessionIdentityFilter(UserManagementService users, Clock clock,
      org.springframework.security.web.AuthenticationEntryPoint rejected) {
    this.users = users;
    this.clock = clock;
    this.rejected = java.util.Objects.requireNonNull(rejected);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return BrowserSessionController.AUTHORIZE.equals(
        request.getRequestURI().substring(request.getContextPath().length()));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var previous = SecurityContextHolder.getContext();
    var auth = previous.getAuthentication();
    var session = request.getSession(false);
    Object completed = session == null ? null : session.getAttribute(LoginSession.COMPLETED_AT);
    User original =
        auth instanceof UsernamePasswordAuthenticationToken
                && auth.isAuthenticated()
                && auth.getPrincipal() instanceof User user
            ? user
            : null;
    if (original == null
        || !(completed instanceof Instant instant)
        || instant.isAfter(clock.instant())
        || !clock.instant().isBefore(instant.plusSeconds(28800))) {
      reject(request, response);
      return;
    }
    var current = users.findByIdForAuthorization(original.getId()).orElse(null);
    if (current == null
        || current.getDeletedAt() != null
        || !current.isEnabled()
        || !current.isAccountNonLocked()
        || !current.isAccountNonExpired()
        || !current.isCredentialsNonExpired()
        || !Objects.equals(original.getAuthorizationVersion(), current.getAuthorizationVersion())) {
      reject(request, response);
      return;
    }
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(LoginSession.authenticated(current));
    SecurityContextHolder.setContext(context);
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  /** Only the preceding revalidation filter may establish this completed session principal. */
  public static String subject(Authentication authentication) {
    if (authentication instanceof UsernamePasswordAuthenticationToken
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof User user) {
      return user.getId();
    }
    throw new org.springframework.security.authentication.BadCredentialsException(
        "Completed login required");
  }

  private void reject(HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {
    LoginSession.clear(request, response);
    rejected.commence(request, response,
        new org.springframework.security.authentication.InsufficientAuthenticationException("Completed login required"));
  }
}
