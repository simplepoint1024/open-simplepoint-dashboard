package org.simplepoint.gateway.server.handler;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * SessionServerAuthenticationSuccessHandler handles successful authentication by
 * saving user information to the session and redirecting to a default path.
 */
public class SessionServerAuthenticationSuccessHandler implements ServerAuthenticationSuccessHandler {

  private final ServerAuthenticationSuccessHandler delegate;

  /**
   * Constructs a SessionServerAuthenticationSuccessHandler that saves user info to the session
   * and then redirects to the default path ("/") upon successful authentication.
   */
  public SessionServerAuthenticationSuccessHandler() {
    this.delegate = new RedirectServerAuthenticationSuccessHandler("/");
  }

  /**
   * Handles successful authentication by saving user information to the session
   * and then delegating to the default redirect handler.
   *
   * @param webFilterExchange the web filter exchange
   * @param authentication    the authentication object
   * @return a Mono that completes when the handling is done
   */
  @Override
  public Mono<Void> onAuthenticationSuccess(WebFilterExchange webFilterExchange,
                                            Authentication authentication) {

    return webFilterExchange.getExchange().getSession()
        .flatMap(session -> saveUserInfoToSession(session, authentication))
        // ✅ session 保存完成后，再执行默认的 redirect 行为
        .then(delegate.onAuthenticationSuccess(webFilterExchange, authentication));
  }

  /**
   * Saves user information to the web session.
   *
   * @param session        the web session
   * @param authentication the authentication object
   * @return a Mono that completes when the session is saved
   */
  private Mono<Void> saveUserInfoToSession(WebSession session, Authentication authentication) {
    session.getAttributes().put("username", authentication.getName());
    return session.save();
  }
}
