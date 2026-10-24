package org.simplepoint.plugin.rbac.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException.Code;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialIdentity;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialSession;
import org.simplepoint.security.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Opaque, expiring proofs bound to a service caller; Redis stores only a hash of the handle. */
@Component
public class CredentialTickets {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final StringRedisTemplate redis;
  private final Duration lifetime;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  /** Uses the shared authentication Redis without introducing another credential store. */
  @org.springframework.beans.factory.annotation.Autowired
  public CredentialTickets(
      StringRedisTemplate redis,
      @Value("${simplepoint.security.authentication.credential-session-lifetime:20m}")
          Duration lifetime) {
    this(redis, lifetime, Clock.systemUTC());
  }

  CredentialTickets(StringRedisTemplate redis, Duration lifetime, Clock clock) {
    if (lifetime.compareTo(Duration.ofMinutes(1)) < 0
        || lifetime.compareTo(Duration.ofHours(8)) > 0) {
      throw new IllegalArgumentException("Invalid credential session lifetime");
    }
    this.redis = redis;
    this.lifetime = lifetime;
    this.clock = clock;
  }

  CredentialSession issue(String caller, User user) {
    var expires = Instant.ofEpochMilli(clock.instant().plus(lifetime).toEpochMilli());
    var ticket = new Ticket(user.getId(), user.getAuthorizationVersion(), expires.toEpochMilli());
    try {
      String value = JSON.writeValueAsString(ticket);
      for (int attempt = 0; attempt < 3; attempt++) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String handle = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (Boolean.TRUE.equals(
            redis.opsForValue().setIfAbsent(key(caller, handle), value, lifetime))) {
          return new CredentialSession(
              handle,
              new CredentialIdentity(
                  user.getId(),
                  user.getAuthorizationVersion(),
                  Boolean.TRUE.equals(user.superAdmin()),
                  expires));
        }
      }
    } catch (Exception failure) {
      throw unavailable();
    }
    throw unavailable();
  }

  Ticket read(String caller, String handle) {
    validateHandle(handle);
    try {
      String value = redis.opsForValue().get(key(caller, handle));
      if (value == null) {
        throw new CredentialAuthenticationException(Code.SESSION_INVALID);
      }
      if (value.length() > 2048) {
        throw unavailable();
      }
      var ticket = JSON.readValue(value, Ticket.class);
      if (ticket.userId() == null
          || ticket.userId().isBlank()
          || ticket.userId().length() > 64
          || ticket.version() < 0
          || !clock.instant().isBefore(Instant.ofEpochMilli(ticket.expiresAt()))) {
        throw new CredentialAuthenticationException(Code.SESSION_INVALID);
      }
      return ticket;
    } catch (CredentialAuthenticationException failure) {
      throw failure;
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  void revoke(String caller, String handle) {
    validateHandle(handle);
    try {
      redis.delete(key(caller, handle));
    } catch (org.springframework.dao.DataAccessException failure) {
      throw unavailable();
    }
  }

  private static void validateHandle(String handle) {
    if (handle == null || !handle.matches("[A-Za-z0-9_-]{43}")) {
      throw new CredentialAuthenticationException(Code.SESSION_INVALID);
    }
  }

  private static String key(String caller, String handle) {
    try {
      return "simplepoint:authentication:credential-session:v1:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest((caller + "\0" + handle).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static CredentialAuthenticationException unavailable() {
    return new CredentialAuthenticationException(Code.UNAVAILABLE);
  }

  record Ticket(String userId, long version, long expiresAt) {}
}
