package org.simplepoint.gateway.server.handler;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import reactor.core.publisher.Mono;

/**
 * Handler invoked upon successful authentication.
 * Stores the authenticated user's username in the session attributes.
 */
public class SessionServerAuthenticationSuccessHandler implements ServerAuthenticationSuccessHandler {
  @Override
  public Mono<Void> onAuthenticationSuccess(WebFilterExchange webFilterExchange, Authentication authentication) {
    return webFilterExchange.getExchange().getSession()
        .flatMap(session -> {
          session.getAttributes().put("username", authentication.getName());
          return session.save();
        });
  }
}
