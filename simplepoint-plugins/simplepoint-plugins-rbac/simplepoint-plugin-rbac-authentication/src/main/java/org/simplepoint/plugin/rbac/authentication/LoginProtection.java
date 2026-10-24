package org.simplepoint.plugin.rbac.authentication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Service;

/**
 * Shared, atomic attempt budgets and TOTP replay protection. An attempt is reserved before
 * credential verification, so concurrent requests cannot all pass a stale failure count.
 */
@Service
public class LoginProtection {
  private static final DefaultRedisScript<Long> RESERVE =
      new DefaultRedisScript<>(
          """
          local count = tonumber(redis.call('get', KEYS[1]) or '0')
          if count >= tonumber(ARGV[1]) then return 0 end
          count = redis.call('incr', KEYS[1])
          if count == 1 or count == tonumber(ARGV[1]) then
            redis.call('pexpire', KEYS[1], ARGV[2])
          end
          return 1
          """,
          Long.class);
  private static final DefaultRedisScript<Long> CONSUME =
      new DefaultRedisScript<>(
          """
          local previous = tonumber(redis.call('get', KEYS[1]) or '-1')
          if tonumber(ARGV[1]) <= previous then return 0 end
          redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2])
          return 1
          """,
          Long.class);
  private final StringRedisTemplate redis;
  private final int attempts;
  private final Duration lockDuration;

  /** Creates the shared guard with bounded, configurable failure limits. */
  public LoginProtection(
      StringRedisTemplate redis,
      @Value("${simplepoint.security.authentication.max-attempts:5}") int attempts,
      @Value("${simplepoint.security.authentication.lock-duration:15m}") Duration lockDuration) {
    if (attempts < 3
        || attempts > 20
        || lockDuration.compareTo(Duration.ofMinutes(1)) < 0
        || lockDuration.compareTo(Duration.ofHours(1)) > 0) {
      throw new IllegalArgumentException("Invalid authentication attempt limits");
    }
    this.redis = redis;
    this.attempts = attempts;
    this.lockDuration = lockDuration;
  }

  /** Reserves one attempt atomically before expensive credential verification. */
  public void reserve(String purpose, String identity) {
    try {
      Long result =
          redis.execute(
              RESERVE,
              List.of(key(purpose, identity)),
              Integer.toString(attempts),
              Long.toString(lockDuration.toMillis()));
      if (result == null) {
        throw new AuthenticationServiceException("Authentication protection unavailable");
      }
      if (result != 1) {
        throw new LockedException("Authentication temporarily restricted");
      }
    } catch (org.springframework.dao.DataAccessException ex) {
      throw new AuthenticationServiceException("Authentication protection unavailable");
    }
  }

  /** Applies the same bounded input budget to browser and non-browser login attempts. */
  public void reservePasswordInput(String username) {
    reserve("password-input", passwordInputIdentity(username));
  }

  /** Clears the input budget only after the calling flow has accepted its verified factors. */
  public void passwordInputSucceeded(String username) {
    success("password-input", passwordInputIdentity(username));
  }

  private String passwordInputIdentity(String username) {
    if (username == null || username.isBlank() || username.length() > 254) {
      throw new BadCredentialsException("Invalid credentials");
    }
    return username.trim().toLowerCase(Locale.ROOT);
  }

  /** Resets the attempt budget after successful verification. */
  public void success(String purpose, String identity) {
    try {
      redis.delete(key(purpose, identity));
    } catch (org.springframework.dao.DataAccessException ex) {
      throw new AuthenticationServiceException("Authentication protection unavailable");
    }
  }

  /** Consumes a TOTP step once across instances and concurrent requests. */
  public boolean consumeTotp(String userId, String encryptedSecret, long step) {
    try {
      Long result =
          redis.execute(
              CONSUME,
              List.of(key("totp-replay", userId + ":" + encryptedSecret)),
              Long.toString(step),
              Long.toString(Duration.ofMinutes(5).toMillis()));
      if (result == null) {
        throw new AuthenticationServiceException("Authentication protection unavailable");
      }
      return result == 1;
    } catch (org.springframework.dao.DataAccessException ex) {
      throw new AuthenticationServiceException("Authentication protection unavailable");
    }
  }

  /** A successful challenge can complete only once, even with two different valid time steps. */
  public boolean completeChallenge(String challengeId) {
    try {
      return Boolean.TRUE.equals(
          redis
              .opsForValue()
              .setIfAbsent(key("mfa-completed", challengeId), "1", Duration.ofMinutes(6)));
    } catch (org.springframework.dao.DataAccessException ex) {
      throw new AuthenticationServiceException("Authentication protection unavailable");
    }
  }

  private String key(String purpose, String identity) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
      return "simplepoint:authentication:v1:" + purpose + ":" + HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
