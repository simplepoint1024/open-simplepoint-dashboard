package org.simplepoint.gateway.server.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRepository;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Idle and absolute limits are enforced independently; sessions without completed initialization
 * must log in again.
 */
public final class BrowserSessionLifetime implements WebFilter {
  // v2 sessions have their CSRF token initialized before any parallel page requests begin.
  static final String COMPLETED_AT = BrowserSessionLifetime.class.getName() + ".completedAt.v2";
  private final ServerCsrfTokenRepository csrfTokens;
  private final RedirectServerAuthenticationSuccessHandler delegate =
      new RedirectServerAuthenticationSuccessHandler();

  public BrowserSessionLifetime(ServerCsrfTokenRepository csrfTokens) {
    this.csrfTokens = Objects.requireNonNull(csrfTokens);
  }

  /** Completes session fixation protection before redirecting to the concurrent browser workload. */
  public Mono<Void> authenticated(WebFilterExchange exchange, Authentication authentication) {
    var webExchange = exchange.getExchange();
    // The WebSession CSRF repository rotates the session ID on save. Deferring this until the
    // first /csrf request deletes the Redis session still used by parallel schema/data requests.
    // Always replace a pre-login token and retain the framework's session rotation and masking.
    return csrfTokens.generateToken(webExchange)
        .flatMap(token -> csrfTokens.saveToken(webExchange, token))
        .then(
            Mono.defer(
                () -> webExchange.getSession()
                    .doOnNext(session -> {
                      session.getAttributes().put(COMPLETED_AT, Instant.now());
                      session.setMaxIdleTime(Duration.ofMinutes(30));
                    })
                    .then()))
        .then(Mono.defer(() -> delegate.onAuthenticationSuccess(exchange, authentication)));
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    return exchange
        .getPrincipal()
        .map(value -> value instanceof OAuth2AuthenticationToken)
        .defaultIfEmpty(false)
        .flatMap(
            browser -> {
              if (!browser) {
                return chain.filter(exchange);
              }
              return exchange
                  .getSession()
                  .flatMap(
                      session -> {
                        Object value = session.getAttribute(COMPLETED_AT);
                        if (value instanceof Instant instant
                            && Instant.now().isBefore(instant.plusSeconds(28800))) {
                          return chain.filter(exchange);
                        }
                        return session
                            .invalidate()
                            .then(
                                Mono.defer(
                                    () -> {
                                      exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                                      return exchange.getResponse().setComplete();
                                    }));
                      });
            });
  }
}
