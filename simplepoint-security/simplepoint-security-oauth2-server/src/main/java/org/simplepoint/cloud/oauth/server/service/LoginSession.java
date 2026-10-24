package org.simplepoint.cloud.oauth.server.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.simplepoint.security.entity.User;
import org.springframework.beans.BeanUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** Explicit persistence is required by Spring Security 6+ when a controller changes identity. */
public final class LoginSession {
  // Completed browser sessions include the post-authentication CSRF token before redirect.
  public static final String COMPLETED_AT = LoginSession.class.getName() + ".completedAt.v2";

  private LoginSession() {}

  /** Persists the replacement security context and records the completed login deadline. */
  public static void save(
      HttpServletRequest request, HttpServletResponse response, Authentication auth) {
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(auth);
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    if (auth != null && auth.isAuthenticated()) {
      request.getSession().setAttribute(COMPLETED_AT, java.time.Instant.now());
      request.getSession().setMaxInactiveInterval(1800);
    } else if (request.getSession(false) != null) {
      request.getSession(false).removeAttribute(COMPLETED_AT);
    }
  }

  /** Removes the identity and invalidates the current session. */
  public static void clear(HttpServletRequest request, HttpServletResponse response) {
    save(request, response, null);
    if (request.getSession(false) != null) {
      request.getSession(false).invalidate();
    }
  }

  /** Builds a session principal without password hashes or authentication seeds. */
  public static Authentication authenticated(User user) {
    User principal = new User();
    BeanUtils.copyProperties(user, principal);
    principal.setPassword(null);
    principal.setTwoFactorSecret(null);
    return UsernamePasswordAuthenticationToken.authenticated(
        principal, null, user.getAuthorities());
  }
}
