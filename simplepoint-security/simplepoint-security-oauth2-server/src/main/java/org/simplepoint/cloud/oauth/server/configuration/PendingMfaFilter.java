package org.simplepoint.cloud.oauth.server.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.simplepoint.cloud.oauth.server.provider.PendingMfaAuthentication;
import org.simplepoint.cloud.oauth.server.service.LoginSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs before authorization-server protocol filters as well as ordinary application filters. */
public final class PendingMfaFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated()
        && (auth instanceof org.springframework.security.authentication.UsernamePasswordAuthenticationToken
            || auth instanceof org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken)) {
      Object completed = request.getSession(false) == null ? null
          : request.getSession(false).getAttribute(LoginSession.COMPLETED_AT);
      // Old sessions predate the MFA boundary; active use cannot extend login beyond eight hours.
      if (!(completed instanceof java.time.Instant instant)
          || !java.time.Instant.now().isBefore(instant.plusSeconds(28800))) {
        LoginSession.clear(request, response);
        response.sendRedirect(request.getContextPath() + "/login");
        return;
      }
    }
    if (!(auth instanceof PendingMfaAuthentication pending)) {
      chain.doFilter(request, response);
      return;
    }
    if (pending.expired()) {
      LoginSession.clear(request, response);
      response.sendRedirect(request.getContextPath() + "/login?error=invalid_credentials");
      return;
    }
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (("/two-factor/verify".equals(path) && ("GET".equals(request.getMethod()) || "POST".equals(request.getMethod())))
        || ("/logout".equals(path) && "POST".equals(request.getMethod()))
        || ("/two-factor.css".equals(path) && "GET".equals(request.getMethod()))) {
      chain.doFilter(request, response);
    } else if ("GET".equals(request.getMethod())) {
      response.sendRedirect(request.getContextPath() + "/two-factor/verify");
    } else {
      response.sendError(403);
    }
  }
}
