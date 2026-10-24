package org.simplepoint.plugin.rbac.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.auditing.logging.api.event.CredentialAuditContract;
import org.simplepoint.plugin.rbac.authentication.AccountCredentialVerifier;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException.Code;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialRequest;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.entity.User;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

/** Real Redis proofs with independently mocked factor/account/audit collaborators. */
class CredentialAuthenticationTest {
  private static Process server;
  private static LettuceConnectionFactory connections;
  private static StringRedisTemplate redis;
  private final AccountCredentialVerifier verifier = mock(AccountCredentialVerifier.class);
  private final UserManagementService users = mock(UserManagementService.class);
  private final CredentialAudit audit = mock(CredentialAudit.class);
  private CredentialTickets tickets;
  private CredentialAuthenticationServiceImpl service;
  private static final Instant NOW = Instant.parse("2026-10-20T10:20:30.123456Z");

  @BeforeAll
  static void start() throws Exception {
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    server =
        new ProcessBuilder(
                "redis-server",
                "--bind",
                "127.0.0.1",
                "--port",
                "" + port,
                "--save",
                "",
                "--appendonly",
                "no")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    connections = new LettuceConnectionFactory("127.0.0.1", port);
    connections.afterPropertiesSet();
    connections.start();
    redis = new StringRedisTemplate(connections);
    for (int i = 0; i < 50; i++) {
      try (var connection = connections.getConnection()) {
        connection.ping();
        return;
      } catch (RuntimeException unavailable) {
        Thread.sleep(100);
      }
    }
    throw new IllegalStateException("Isolated Redis did not start");
  }

  @AfterAll
  static void stop() {
    if (connections != null) {
      connections.destroy();
    }
    if (server != null) {
      server.destroy();
    }
  }

  @BeforeEach
  void setup() {
    try (var connection = connections.getConnection()) {
      connection.serverCommands().flushDb();
    }
    tickets =
        new CredentialTickets(redis, Duration.ofMinutes(20), Clock.fixed(NOW, ZoneOffset.UTC));
    service = new CredentialAuthenticationServiceImpl(verifier, tickets, audit, users);
    caller("dna");
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void successfulProofIsOpaqueCallerBoundAuditedAndRevocableAcrossInstances() {
    when(verifier.verify("alice@example.test", " secret ", "012345")).thenReturn(account());
    when(users.findByIdForAuthorization("alice")).thenReturn(Optional.of(account()));
    var proof = service.open(new CredentialRequest("alice@example.test", " secret ", "012345"));
    assertThat(proof.handle()).matches("[A-Za-z0-9_-]{43}");
    var auditRecord = ArgumentCaptor.forClass(CredentialAuditContract.Outcome.class);
    verify(audit).record(auditRecord.capture());
    assertThat(auditRecord.getValue().status()).isEqualTo("SUCCESS");
    assertThat(auditRecord.getValue().caller()).isEqualTo("dna");
    assertThat(auditRecord.getValue().toString())
        .doesNotContain("alice@example.test", " secret ", "012345", proof.handle());
    String key = redis.keys("*").iterator().next();
    assertThat(key).doesNotContain(proof.handle());
    assertThat(redis.opsForValue().get(key)).doesNotContain(proof.handle(), " secret ", "012345");
    assertThat(redis.getExpire(key)).isBetween(1190L, 1200L);
    assertThat(service.check(proof.handle())).isEqualTo(proof.identity());
    caller("other-service");
    rejected(() -> service.check(proof.handle()), Code.SESSION_INVALID);
    service.close(proof.handle()); // Another caller cannot delete the original caller's proof.
    caller("dna");
    var second =
        new CredentialAuthenticationServiceImpl(
            verifier,
            new CredentialTickets(redis, Duration.ofMinutes(20), Clock.fixed(NOW, ZoneOffset.UTC)),
            audit,
            users);
    assertThat(second.check(proof.handle())).isEqualTo(proof.identity());
    second.close(proof.handle());
    second.close(proof.handle());
    rejected(() -> service.check(proof.handle()), Code.SESSION_INVALID);
  }

  @Test
  void expiredProofCannotBeUsedEvenBeforeRedisTtlDeletion() {
    var proof = tickets.issue("dna", account());
    var later =
        new CredentialTickets(
            redis,
            Duration.ofMinutes(20),
            Clock.fixed(proof.identity().expiresAt(), ZoneOffset.UTC));
    rejected(() -> later.read("dna", proof.handle()), Code.SESSION_INVALID);
    verifyNoInteractions(users, verifier, audit);
  }

  @Test
  void changedVersionDisabledAndDeletedAccountsRevokeTheStoredProof() {
    for (int restriction = 0; restriction < 3; restriction++) {
      var proof = tickets.issue("dna", account());
      var changed = account();
      if (restriction == 0) {
        changed.setAuthorizationVersion(2L);
      } else if (restriction == 1) {
        changed.setEnabled(false);
      } else {
        changed.setDeletedAt(NOW);
      }
      when(users.findByIdForAuthorization("alice")).thenReturn(Optional.of(changed));
      rejected(() -> service.check(proof.handle()), Code.SESSION_INVALID);
      rejected(() -> tickets.read("dna", proof.handle()), Code.SESSION_INVALID);
    }
  }

  @Test
  void factorFailureUsesCanonicalErrorAndDurableFailureAuditWithoutIssuingProof() {
    when(verifier.verify("unknown@example.test", "submitted-password", "654321"))
        .thenThrow(new BadCredentialsException("diagnostic with submitted-password"));
    rejected(
        () ->
            service.open(
                new CredentialRequest("unknown@example.test", "submitted-password", "654321")),
        Code.INVALID);
    var record = ArgumentCaptor.forClass(CredentialAuditContract.Outcome.class);
    verify(audit).record(record.capture());
    assertThat(record.getValue().status()).isEqualTo("FAILURE");
    assertThat(record.getValue().reason()).isEqualTo("INVALID");
    assertThat(record.getValue().toString())
        .doesNotContain("unknown@example.test", "submitted-password", "654321");
    assertThat(redis.keys("*")).isEmpty();
  }

  @Test
  void auditFailurePreventsSuccessAndRevokesUndisclosedProof() {
    when(verifier.verify("alice", "secret", null)).thenReturn(account());
    doThrow(new IllegalStateException("database down")).when(audit).record(any());
    rejected(() -> service.open(new CredentialRequest("alice", "secret", null)), Code.UNAVAILABLE);
    assertThat(redis.keys("*")).isEmpty();
  }

  @Test
  void storageOrAccountFailureIsUnavailableAndCannotAuthenticate() {
    var proof = tickets.issue("dna", account());
    when(users.findByIdForAuthorization("alice"))
        .thenThrow(new IllegalStateException("private diagnostic"));
    rejected(() -> service.check(proof.handle()), Code.UNAVAILABLE);
    redis.opsForValue().set(redis.keys("*").iterator().next(), "not-json");
    rejected(() -> tickets.read("dna", proof.handle()), Code.UNAVAILABLE);
    var unavailableRedis = mock(StringRedisTemplate.class);
    when(unavailableRedis.opsForValue())
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
    rejected(
        () ->
            new CredentialTickets(unavailableRedis, Duration.ofMinutes(20)).issue("dna", account()),
        Code.UNAVAILABLE);
  }

  @Test
  void namedUserAndAnonymousCannotInvokeCredentialOperations() {
    for (var authentication :
        List.of(
            UsernamePasswordAuthenticationToken.authenticated(
                "dna", null, List.of(new SimpleGrantedAuthority("SCOPE_service-router.invoke"))),
            UsernamePasswordAuthenticationToken.unauthenticated("dna", null))) {
      SecurityContextHolder.getContext().setAuthentication(authentication);
      assertThatThrownBy(() -> service.open(new CredentialRequest("alice", "secret", null)))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThatThrownBy(() -> service.check("a".repeat(43)))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThatThrownBy(() -> service.close("a".repeat(43)))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    verifyNoInteractions(verifier, audit, users);
    assertThat(redis.keys("*")).isEmpty();
  }

  @Test
  void realProviderDispatchesCredentialDtosAndCanonicalFailuresThroughTheWireCodec() {
    when(verifier.verify("alice@example.test", " secret ", "012345")).thenReturn(account());
    when(users.findByIdForAuthorization("alice")).thenReturn(Optional.of(account()));
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(Router.class)
        .withBean(CredentialAuthenticationServiceImpl.class, () -> service)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var mapper = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
              var codec =
                  new com.simplepoint.service.router.invocation.ServiceRouterJsonCodec(mapper);
              var dispatcher =
                  context.getBean(
                      com.simplepoint.service.router.invocation.ServiceInvocationDispatcher.class);
              byte[] request =
                  codec.encode(
                      new com.simplepoint.service.router.invocation.RemoteRequest(
                          "security.credentials",
                          "1",
                          "open",
                          List.of(
                              new CredentialRequest("alice@example.test", " secret ", "012345")),
                          "open"));
              byte[] response = dispatcher.dispatchJson(request);
              assertThat(new String(response, java.nio.charset.StandardCharsets.UTF_8))
                  .doesNotContain(" secret ", "012345", "password");
              var opened = codec.decodeResponse(response);
              assertThat(opened.success()).isTrue();
              var proof =
                  mapper.convertValue(
                      opened.data(),
                      org.simplepoint.plugin.rbac.core.api.authentication.CredentialSession.class);
              var checked =
                  codec.decodeResponse(
                      dispatcher.dispatchJson(
                          codec.encode(
                              new com.simplepoint.service.router.invocation.RemoteRequest(
                                  "security.credentials",
                                  "1",
                                  "check",
                                  List.of(proof.handle()),
                                  "check"))));
              assertThat(checked.success()).isTrue();
              assertThat(
                      mapper.convertValue(
                          checked.data(),
                          org.simplepoint.plugin.rbac.core.api.authentication.CredentialIdentity
                              .class))
                  .isEqualTo(proof.identity());
              service.close(proof.handle());
              var rejected =
                  codec.decodeResponse(
                      dispatcher.dispatchJson(
                          codec.encode(
                              new com.simplepoint.service.router.invocation.RemoteRequest(
                                  "security.credentials",
                                  "1",
                                  "check",
                                  List.of(proof.handle()),
                                  "expired"))));
              assertThat(rejected.success()).isFalse();
              assertThat(rejected.errorCode()).isEqualTo("IDENTITY_CREDENTIAL:SESSION_INVALID");
              assertThat(rejected.message()).doesNotContain(proof.handle());
            });
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @com.simplepoint.service.router.annotation.EnableServiceRouter(
      basePackages = "org.simplepoint.plugin.rbac.core.api.service")
  static class Router {}

  private static void rejected(Runnable action, Code code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            CredentialAuthenticationException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(code);
              assertThat(failure.getMessage())
                  .doesNotContain("secret", "password", "private", "offline");
            });
  }

  private static User account() {
    var user = new User();
    user.setId("alice");
    user.setEnabled(true);
    user.setAccountNonLocked(true);
    user.setAccountNonExpired(true);
    user.setCredentialsNonExpired(true);
    user.setAuthorizationVersion(1L);
    user.setSuperAdmin(false);
    return user;
  }

  private static void caller(String id) {
    List<org.springframework.security.core.GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("SCOPE_service-router.invoke"));
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            id, Map.of("client_id", id, "grant_type", "client_credentials"), authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
  }
}
