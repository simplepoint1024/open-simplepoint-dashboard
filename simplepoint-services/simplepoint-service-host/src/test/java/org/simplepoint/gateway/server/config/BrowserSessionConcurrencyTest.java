package org.simplepoint.gateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.csrf.WebSessionServerCsrfTokenRepository;
import org.springframework.session.data.redis.ReactiveRedisSessionRepository;
import org.springframework.session.web.server.session.SpringSessionWebSessionStore;
import org.springframework.web.server.session.DefaultWebSessionManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Uses isolated real Redis, independent requests and the production Spring Session/CSRF stack. */
class BrowserSessionConcurrencyTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final OAuth2AuthenticationToken AUTH = new OAuth2AuthenticationToken(
      new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("sub", "alice"), "sub"),
      List.of(), "client");
  private static Process redis;
  private static LettuceConnectionFactory connections;
  private final WebSessionServerCsrfTokenRepository tokens = new WebSessionServerCsrfTokenRepository();
  private final WebSessionServerSecurityContextRepository security = new WebSessionServerSecurityContextRepository();
  private final BrowserSessionLifetime lifetime = new BrowserSessionLifetime(tokens);
  private DefaultWebSessionManager sessions;
  private CsrfWebFilter csrf;

  @BeforeAll
  static void startRedis() throws Exception {
    int port;
    try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
    redis = new ProcessBuilder("redis-server", "--bind", "127.0.0.1", "--port", "" + port,
        "--save", "", "--appendonly", "no")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    connections = new LettuceConnectionFactory("127.0.0.1", port);
    connections.afterPropertiesSet();
    connections.start();
    for (int i = 0; i < 50; i++) {
      try (var connection = connections.getConnection()) { connection.ping(); return; }
      catch (RuntimeException unavailable) { Thread.sleep(100); }
    }
    throw new IllegalStateException("Isolated Redis did not start");
  }

  @AfterAll
  static void stopRedis() {
    if (connections != null) connections.destroy();
    if (redis != null) redis.destroy();
  }

  @BeforeEach
  void setup() {
    var serialization = RedisSerializationContext.<String, Object>newSerializationContext(new StringRedisSerializer())
        .value(new JdkSerializationRedisSerializer()).hashKey(new StringRedisSerializer())
        .hashValue(new JdkSerializationRedisSerializer()).build();
    var repository = new ReactiveRedisSessionRepository(new ReactiveRedisTemplate<>(connections, serialization));
    repository.setRedisKeyNamespace("test:browser-session:" + UUID.randomUUID());
    sessions = new DefaultWebSessionManager();
    sessions.setSessionStore(new SpringSessionWebSessionStore<>(repository));
    csrf = new CsrfWebFilter();
    csrf.setCsrfTokenRepository(tokens);
    csrf.setRequireCsrfProtectionMatcher(new BrowserCsrfMatcher());
  }

  @Test
  void deferredFirstTokenExplainsBothLostAuthenticationAndInvalidatedSessionSave() {
    // Reproduce the old ordering without changing framework session-fixation protection.
    var login = exchange(MockServerHttpRequest.get("/login/oauth2/code/client"), null);
    security.save(login, new SecurityContextImpl(AUTH)).block(TIMEOUT);
    login.getResponse().setComplete().block(TIMEOUT);
    var cookie = login.getResponse().getCookies().getFirst("SESSION").getValue();
    var inFlight = exchange(MockServerHttpRequest.get("/app/runtime/records/schema"), cookie);
    var stale = inFlight.getSession().block(TIMEOUT);
    assertThat(security.load(inFlight).block(TIMEOUT)).isNotNull();
    var firstCsrf = exchange(MockServerHttpRequest.get("/csrf"), cookie);
    maskedToken(firstCsrf);
    assertThat(firstCsrf.getSession().block(TIMEOUT).getId()).isNotEqualTo(cookie);
    assertThat(security.load(exchange(MockServerHttpRequest.get("/userinfo"), cookie)).block(TIMEOUT)).isNull();
    assertThatThrownBy(() -> stale.save().block(TIMEOUT))
        .isInstanceOf(IllegalStateException.class).hasMessage("Session was invalidated");
  }

  @Test
  void loginReplacesPreLoginTokenBeforeRedirectAndParallelRequestsKeepAuthentication() {
    var anonymous = exchange(MockServerHttpRequest.get("/login"), null);
    maskedToken(anonymous);
    var beforeToken = tokens.loadToken(anonymous).block(TIMEOUT).getToken();
    anonymous.getResponse().setComplete().block(TIMEOUT);
    var beforeCookie = anonymous.getResponse().getCookies().getFirst("SESSION").getValue();
    var login = exchange(MockServerHttpRequest.get("/login/oauth2/code/client"), beforeCookie);
    security.save(login, new SecurityContextImpl(AUTH)).block(TIMEOUT);
    lifetime.authenticated(new WebFilterExchange(login, ignored -> Mono.empty()), AUTH).block(TIMEOUT);
    login.getResponse().setComplete().block(TIMEOUT);
    assertThat(login.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
    var cookie = login.getResponse().getCookies().getFirst("SESSION").getValue();
    assertThat(cookie).isNotEqualTo(beforeCookie);
    assertThat(tokens.loadToken(login).block(TIMEOUT).getToken()).isNotEqualTo(beforeToken);
    assertThat(login.getSession().block(TIMEOUT).getMaxIdleTime()).isEqualTo(Duration.ofMinutes(30));
    assertThat(login.getSession().block(TIMEOUT).<Instant>getAttribute(BrowserSessionLifetime.COMPLETED_AT)).isNotNull();

    Flux.range(0, 48).flatMap(i -> Mono.defer(() -> {
      var request = exchange(MockServerHttpRequest.get(i % 2 == 0 ? "/csrf" : "/app/runtime/records/schema"), cookie);
      return security.load(request).switchIfEmpty(Mono.error(new AssertionError("Lost authentication")))
          .flatMap(context -> csrf.filter(request, current -> {
            var authenticated = current.mutate().principal(Mono.just(context.getAuthentication())).build();
            return lifetime.filter(authenticated, next -> {
              Mono<CsrfToken> token = next.getAttribute(CsrfToken.class.getName());
              return token.then(Mono.defer(() -> {
                next.getResponse().setStatusCode(HttpStatus.OK);
                return next.getResponse().setComplete();
              }));
            });
          })).then(Mono.fromRunnable(() -> {
            assertThat(request.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(request.getResponse().getCookies()).doesNotContainKey("SESSION");
          }));
    }), 12).then().block(TIMEOUT);
    assertThat(security.load(exchange(MockServerHttpRequest.get("/userinfo"), cookie)).block(TIMEOUT)).isNotNull();

    var probe = exchange(MockServerHttpRequest.get("/csrf"), cookie);
    var masked = maskedToken(probe);
    assertThat(masked.getToken()).isNotEqualTo(tokens.loadToken(probe).block(TIMEOUT).getToken());
    assertWrite(cookie, masked.getToken(), HttpStatus.OK);
    assertWrite(cookie, null, HttpStatus.FORBIDDEN);
    assertWrite(cookie, "invalid-token", HttpStatus.FORBIDDEN);
    // No global or cross-session token acceptance.
    var other = exchange(MockServerHttpRequest.get("/login/oauth2/code/client"), null);
    security.save(other, new SecurityContextImpl(AUTH)).block(TIMEOUT);
    lifetime.authenticated(new WebFilterExchange(other, ignored -> Mono.empty()), AUTH).block(TIMEOUT);
    other.getResponse().setComplete().block(TIMEOUT);
    assertWrite(other.getResponse().getCookies().getFirst("SESSION").getValue(), masked.getToken(), HttpStatus.FORBIDDEN);
  }

  @Test
  void preFixSessionsMustReauthenticateBeforeAnyPageRequest() {
    var login = exchange(MockServerHttpRequest.get("/login/oauth2/code/client"), null);
    security.save(login, new SecurityContextImpl(AUTH)).block(TIMEOUT);
    login.getSession().block(TIMEOUT).getAttributes().put(BrowserSessionLifetime.class.getName() + ".completedAt.v1", Instant.now());
    login.getResponse().setComplete().block(TIMEOUT);
    var cookie = login.getResponse().getCookies().getFirst("SESSION").getValue();
    var request = exchange(MockServerHttpRequest.get("/app/runtime/records/schema"), cookie);
    lifetime.filter(request.mutate().principal(Mono.just(AUTH)).build(),
        ignored -> Mono.error(new AssertionError("Legacy session reached business code"))).block(TIMEOUT);
    assertThat(request.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(security.load(exchange(MockServerHttpRequest.get("/userinfo"), cookie)).block(TIMEOUT)).isNull();
  }

  private void assertWrite(String cookie, String token, HttpStatus expected) {
    var builder = MockServerHttpRequest.post("/app/runtime/records/query");
    if (token != null) builder.header("X-CSRF-TOKEN", token);
    var request = exchange(builder, cookie);
    csrf.filter(request, current -> {
      current.getResponse().setStatusCode(HttpStatus.OK);
      return current.getResponse().setComplete();
    }).block(TIMEOUT);
    assertThat(request.getResponse().getStatusCode()).isEqualTo(expected);
  }

  private CsrfToken maskedToken(MockServerWebExchange request) {
    csrf.filter(request, ignored -> Mono.empty()).block(TIMEOUT);
    Mono<CsrfToken> token = request.getAttribute(CsrfToken.class.getName());
    return token.block(TIMEOUT);
  }

  private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> builder, String cookie) {
    if (cookie != null) builder.cookie(new HttpCookie("SESSION", cookie));
    return MockServerWebExchange.builder(builder.build()).sessionManager(sessions).build();
  }
}
