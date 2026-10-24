package org.simplepoint.plugin.rbac.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.entity.User;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class LoginProtectionTest {
  static Process server;
  static LettuceConnectionFactory connections;
  static StringRedisTemplate redis;
  LoginProtection protection;

  @BeforeAll
  static void startIsolatedRedis() throws Exception {
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
      } catch (RuntimeException ex) {
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
    protection = new LoginProtection(redis, 5, Duration.ofMinutes(15));
  }

  @Test
  void concurrentRequestsCannotExceedBudgetAndSuccessRestoresIt() throws Exception {
    var anotherInstance = new LoginProtection(redis, 5, Duration.ofMinutes(15));
    try (var executor = Executors.newFixedThreadPool(12)) {
      var tasks = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 40; i++) {
        final var instance = i % 2 == 0 ? protection : anotherInstance;
        tasks.add(
            executor.submit(
                () -> {
                  try {
                    instance.reserve("password-account", "alice");
                    return true;
                  } catch (LockedException ex) {
                    return false;
                  }
                }));
      }
      int accepted = 0;
      for (var task : tasks) {
        if (task.get()) {
          accepted++;
        }
      }
      assertThat(accepted).isEqualTo(5);
    }
    String key = redis.keys("*").iterator().next();
    assertThat(redis.getExpire(key)).isBetween(890L, 900L);
    protection.success("password-account", "alice");
    assertThatCode(() -> anotherInstance.reserve("password-account", "alice"))
        .doesNotThrowAnyException();
  }

  @Test
  void expirationUnlocksAccountAndTotpConsumptionIsAtomic() throws Exception {
    for (int i = 0; i < 5; i++) {
      protection.reserve("totp", "alice");
    }
    assertThatThrownBy(() -> protection.reserve("totp", "alice"))
        .isInstanceOf(LockedException.class);
    redis.expire(redis.keys("*").iterator().next(), Duration.ofMillis(1));
    Thread.sleep(10);
    protection.reserve("totp", "alice");
    try (var executor = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 16; i++) {
        tasks.add(executor.submit(() -> protection.consumeTotp("alice", "encrypted", 100)));
      }
      int accepted = 0;
      for (var task : tasks) {
        if (task.get()) {
          accepted++;
        }
      }
      assertThat(accepted).isEqualTo(1);
    }
    assertThat(protection.consumeTotp("alice", "encrypted", 99)).isFalse();
    assertThat(protection.consumeTotp("alice", "encrypted", 101)).isTrue();
  }

  @Test
  void completedChallengeCannotBeReusedAcrossInstances() {
    assertThat(protection.completeChallenge("challenge")).isTrue();
    assertThat(new LoginProtection(redis, 5, Duration.ofMinutes(15)).completeChallenge("challenge"))
        .isFalse();
  }

  @Test
  void unavailableStorageNeverPermitsAuthentication() {
    var unavailable = org.mockito.Mockito.mock(StringRedisTemplate.class);
    org.mockito.Mockito.when(
            unavailable.execute(
                org.mockito.ArgumentMatchers.any(
                    org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(Object[].class)))
        .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"));
    var guard = new LoginProtection(unavailable, 5, Duration.ofMinutes(15));
    assertThatThrownBy(() -> guard.reserve("totp", "alice"))
        .isInstanceOf(
            org.springframework.security.authentication.AuthenticationServiceException.class);
  }

  @Test
  void browserAndNonBrowserAliasesShareOneCanonicalAccountBudget() {
    var users = org.mockito.Mockito.mock(UserManagementService.class);
    var encoder = new BCryptPasswordEncoder(4);
    var user = new User();
    user.setId("alice");
    user.setPassword(encoder.encode("correct"));
    org.mockito.Mockito.when(users.loadUserByUsername("alice@example.test")).thenReturn(user);
    org.mockito.Mockito.when(users.loadUserByUsername("13800000000")).thenReturn(user);
    var browser = new ProtectedPasswordAuthenticationProvider(users, encoder, protection);
    var otherInstance = new LoginProtection(redis, 5, Duration.ofMinutes(15));
    var verifier =
        new AccountCredentialVerifier(
            users, encoder, otherInstance, org.mockito.Mockito.mock(MfaVerificationService.class));
    for (int i = 0; i < 3; i++) {
      protection.reservePasswordInput("alice@example.test");
      assertThatThrownBy(
              () ->
                  browser.authenticate(
                      UsernamePasswordAuthenticationToken.unauthenticated(
                          "alice@example.test", "wrong")))
          .isInstanceOf(BadCredentialsException.class);
    }
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> verifier.verify("13800000000", "wrong", null))
          .isInstanceOf(BadCredentialsException.class);
    }
    assertThatThrownBy(() -> verifier.verify("13800000000", "correct", null))
        .isInstanceOf(LockedException.class);
    assertThatThrownBy(
            () ->
                browser.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                        "alice@example.test", "correct")))
        .isInstanceOf(LockedException.class);
    org.mockito.Mockito.verify(users, org.mockito.Mockito.never())
        .findByIdForAuthorization(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void normalizedInputCannotBypassBudgetAndInvalidInputCannotCreateUnboundedKeys() {
    for (int i = 0; i < 5; i++) {
      protection.reservePasswordInput(i % 2 == 0 ? " Alice@Example.test " : "alice@example.test");
    }
    assertThatThrownBy(() -> protection.reservePasswordInput("ALICE@EXAMPLE.TEST"))
        .isInstanceOf(LockedException.class);
    assertThat(redis.keys("*")).hasSize(1);
    for (String input : java.util.Arrays.asList(null, "", " ", "a".repeat(255))) {
      assertThatThrownBy(() -> protection.reservePasswordInput(input))
          .isInstanceOf(BadCredentialsException.class);
    }
    assertThat(redis.keys("*")).hasSize(1);
    protection.passwordInputSucceeded("ALICE@example.test");
    assertThatCode(() -> protection.reservePasswordInput("alice@example.test"))
        .doesNotThrowAnyException();
  }
}
