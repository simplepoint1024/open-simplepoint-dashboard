package org.simplepoint.gateway.server.config;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.server.csrf.WebSessionServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class BrowserSessionLifetimeTest {
  @Test void tokenPersistenceFailureDoesNotCompleteLoginOrRedirect() {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/client"));
    var repository = new WebSessionServerCsrfTokenRepository() {
      @Override public Mono<Void> saveToken(ServerWebExchange request, CsrfToken token) {
        return Mono.error(new IllegalStateException("Session storage unavailable"));
      }
    };
    assertThatThrownBy(() -> new BrowserSessionLifetime(repository)
        .authenticated(new WebFilterExchange(exchange, ignored -> Mono.empty()), null).block())
        .isInstanceOf(IllegalStateException.class).hasMessage("Session storage unavailable");
    assertThat(exchange.getSession().block().getAttributes()).doesNotContainKey(BrowserSessionLifetime.COMPLETED_AT);
    assertThat(exchange.getResponse().getHeaders().getLocation()).isNull();
  }

  @Test void absoluteExpiryAndLegacySessionsAreRejectedWhileFreshSessionIsUsable() {
    var auth = new OAuth2AuthenticationToken(new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
        Map.of("sub", "alice"), "sub"), List.of(), "client");
    for (Instant time : new Instant[]{Instant.EPOCH, Instant.now(), null}) {
      var mock = MockServerWebExchange.from(MockServerHttpRequest.get("/userinfo"));
      var exchange = mock.mutate().principal(Mono.just(auth)).build();
      if (time != null) exchange.getSession().block().getAttributes().put(BrowserSessionLifetime.COMPLETED_AT, time);
      var called = new AtomicBoolean();
      new BrowserSessionLifetime(new WebSessionServerCsrfTokenRepository()).filter(exchange, ignored -> {called.set(true); return Mono.empty();}).block();
      assertThat(called.get()).isEqualTo(time != null && !time.equals(Instant.EPOCH));
      if (!called.get()) assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }
  }
  @Test void sessionCookieUsesSecureHttpOnlyAndSameSite() {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("https://app.test/"));
    new SecurityConfig().browserSessionCookie(true).setSessionId(exchange, "session");
    var cookie = exchange.getResponse().getCookies().getFirst("SESSION");
    assertThat(cookie.isHttpOnly()).isTrue(); assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.getSameSite()).isEqualTo("Lax");
  }
}
