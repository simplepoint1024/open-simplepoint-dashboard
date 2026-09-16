package org.simplepoint.plugin.ai.runtime.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis implementation that never persists or logs the raw external MCP session ID.
 */
@Component
public class RedisManagedMcpSessionDirectory
    implements ManagedMcpSessionDirectory {

  private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9:_-]{1,128}");

  private static final DefaultRedisScript<Long> TOUCH_SCRIPT =
      new DefaultRedisScript<>(
          """
          if redis.call('GET', KEYS[1]) == ARGV[1] then
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            return 1
          end
          return 0
          """,
          Long.class
      );

  private static final DefaultRedisScript<Long> DELETE_SCRIPT =
      new DefaultRedisScript<>(
          """
          if redis.call('GET', KEYS[1]) == ARGV[1] then
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], KEYS[1])
            if redis.call('SCARD', KEYS[2]) == 0 then
              redis.call('DEL', KEYS[2])
            end
            return 1
          end
          return 0
          """,
          Long.class
      );

  private static final DefaultRedisScript<String> CAPACITY_CLAIM_SCRIPT =
      new DefaultRedisScript<>(
          """
          local existing = redis.call('GET', KEYS[1])
          if existing then
            return existing
          end
          local members = redis.call('SMEMBERS', KEYS[2])
          for _, member in ipairs(members) do
            if redis.call('EXISTS', member) == 0 then
              redis.call('SREM', KEYS[2], member)
            end
          end
          if redis.call('SCARD', KEYS[2]) >= tonumber(ARGV[3]) then
            return ''
          end
          local claimed = redis.call(
            'SET', KEYS[1], ARGV[1], 'PX', ARGV[2], 'NX'
          )
          if claimed then
            redis.call('SADD', KEYS[2], KEYS[1])
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            return ARGV[1]
          end
          return redis.call('GET', KEYS[1]) or ''
          """,
          String.class
      );

  private static final DefaultRedisScript<Long> DELETE_WORKLOAD_SCRIPT =
      new DefaultRedisScript<>(
          """
          local members = redis.call('SMEMBERS', KEYS[1])
          local removed = 0
          for _, member in ipairs(members) do
            if redis.call('GET', member) == ARGV[1] then
              redis.call('DEL', member)
              removed = removed + 1
            end
          end
          redis.call('DEL', KEYS[1])
          return removed
          """,
          Long.class
      );

  private final StringRedisTemplate redisTemplate;

  private final AiRuntimeProperties properties;

  /**
   * Creates the fail-closed managed session directory.
   */
  public RedisManagedMcpSessionDirectory(
      final StringRedisTemplate redisTemplate,
      final AiRuntimeProperties properties
  ) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
  }

  @Override
  public String sessionKey(
      final String serverId,
      final String scope,
      final String tenantId,
      final String sessionId
  ) {
    String normalizedSessionId = required(sessionId, "MCP session ID");
    Integer maximumLength = properties.getMcpSessionIdMaxLength();
    if (maximumLength == null || maximumLength < 32 || maximumLength > 4096) {
      throw new IllegalStateException(
          "Runtime MCP session ID maximum length is invalid"
      );
    }
    if (normalizedSessionId.length() > maximumLength) {
      throw new IllegalArgumentException("MCP session ID is too long");
    }
    String material = framed(required(serverId, "MCP server ID"))
        + framed(required(scope, "MCP scope"))
        + framed(tenantId == null ? "" : tenantId)
        + framed(normalizedSessionId);
    return keyPrefix() + "assignments:" + sha256(material);
  }

  @Override
  public Optional<Assignment> find(final String sessionKey) {
    try {
      return Optional.ofNullable(redisTemplate.opsForValue().get(sessionKey))
          .map(RedisManagedMcpSessionDirectory::decode);
    } catch (DataAccessException ex) {
      throw unavailable(ex);
    }
  }

  @Override
  public Assignment claim(
      final String sessionKey,
      final Assignment candidate
  ) {
    String encoded = encode(candidate);
    try {
      for (int attempt = 0; attempt < 3; attempt++) {
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
            sessionKey,
            encoded,
            assignmentTtl()
        );
        if (Boolean.TRUE.equals(claimed)) {
          return candidate;
        }
        String existing = redisTemplate.opsForValue().get(sessionKey);
        if (existing != null) {
          return decode(existing);
        }
      }
      throw new IllegalStateException(
          "Runtime MCP session assignment changed concurrently"
      );
    } catch (DataAccessException ex) {
      throw unavailable(ex);
    }
  }

  @Override
  public Optional<Assignment> claimAvailable(
      final String sessionKey,
      final Assignment candidate,
      final int maximumSessions
  ) {
    if (maximumSessions < 1 || maximumSessions > 256) {
      throw new IllegalArgumentException("MCP session capacity is invalid");
    }
    String encoded = encode(candidate);
    try {
      String result = redisTemplate.execute(
          CAPACITY_CLAIM_SCRIPT,
          List.of(sessionKey, capacityKey(candidate)),
          encoded,
          Long.toString(assignmentTtl().toMillis()),
          Integer.toString(maximumSessions)
      );
      return result == null || result.isEmpty()
          ? Optional.empty() : Optional.of(decode(result));
    } catch (DataAccessException ex) {
      throw unavailable(ex);
    }
  }

  @Override
  public void touch(
      final String sessionKey,
      final Assignment assignment
  ) {
    execute(TouchOperation.TOUCH, sessionKey, assignment);
  }

  @Override
  public void invalidate(
      final String sessionKey,
      final Assignment assignment
  ) {
    execute(TouchOperation.DELETE, sessionKey, assignment);
  }

  @Override
  public void invalidateWorkload(final Assignment assignment) {
    String encoded = encode(assignment);
    try {
      redisTemplate.execute(
          DELETE_WORKLOAD_SCRIPT,
          List.of(capacityKey(assignment)),
          encoded
      );
    } catch (DataAccessException ex) {
      throw unavailable(ex);
    }
  }

  @Override
  public void quarantine(
      final String serverId,
      final Assignment assignment
  ) {
    Duration duration = failureQuarantine();
    try {
      redisTemplate.opsForValue().set(
          quarantineKey(serverId, assignment),
          "1",
          duration
      );
    } catch (DataAccessException ex) {
      throw unavailable(ex);
    }
  }

  @Override
  public boolean isQuarantined(
      final String serverId,
      final Assignment assignment
  ) {
    try {
      return Boolean.TRUE.equals(
          redisTemplate.hasKey(quarantineKey(serverId, assignment))
      );
    } catch (DataAccessException ex) {
      throw unavailable(ex);
    }
  }

  @Override
  public Duration assignmentTtl() {
    return validDuration(
        properties.getMcpSessionDirectoryTtl(),
        Duration.ofMinutes(1),
        Duration.ofHours(24),
        "Runtime MCP session directory TTL"
    );
  }

  private void execute(
      final TouchOperation operation,
      final String sessionKey,
      final Assignment assignment
  ) {
    DefaultRedisScript<Long> script = operation == TouchOperation.TOUCH
        ? TOUCH_SCRIPT : DELETE_SCRIPT;
    List<String> arguments = operation == TouchOperation.TOUCH
        ? List.of(encode(assignment), Long.toString(assignmentTtl().toMillis()))
        : List.of(encode(assignment));
    try {
      redisTemplate.execute(
          script,
          List.of(sessionKey, capacityKey(assignment)),
          arguments.toArray()
      );
    } catch (DataAccessException ex) {
      throw unavailable(ex);
    }
  }

  private String quarantineKey(
      final String serverId,
      final Assignment assignment
  ) {
    String material = framed(required(serverId, "MCP server ID"))
        + framed(encode(assignment));
    return keyPrefix() + "failures:" + sha256(material);
  }

  private String capacityKey(final Assignment assignment) {
    return keyPrefix() + "capacity:" + sha256(encode(assignment));
  }

  private Duration failureQuarantine() {
    return validDuration(
        properties.getMcpFailureQuarantine(),
        Duration.ofSeconds(1),
        Duration.ofMinutes(5),
        "Runtime MCP failure quarantine"
    );
  }

  private String keyPrefix() {
    String value = properties.getMcpSessionDirectoryKeyPrefix();
    if (value == null || !SAFE_PREFIX.matcher(value).matches()) {
      throw new IllegalStateException(
          "Runtime MCP session directory key prefix is invalid"
      );
    }
    return value;
  }

  private static String encode(final Assignment assignment) {
    if (assignment == null
        || assignment.workloadId() == null
        || assignment.workloadId().isBlank()
        || assignment.leaseId() == null
        || assignment.leaseId().isBlank()
        || assignment.fencingToken() <= 0) {
      throw new IllegalArgumentException("Runtime MCP assignment is invalid");
    }
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    return "v1."
        + encoder.encodeToString(
            assignment.workloadId().getBytes(StandardCharsets.UTF_8)
        )
        + "."
        + encoder.encodeToString(
            assignment.leaseId().getBytes(StandardCharsets.UTF_8)
        )
        + "."
        + assignment.fencingToken();
  }

  private static Assignment decode(final String encoded) {
    try {
      String[] parts = encoded.split("\\.", -1);
      if (parts.length != 4 || !"v1".equals(parts[0])) {
        throw new IllegalArgumentException("unsupported assignment encoding");
      }
      Base64.Decoder decoder = Base64.getUrlDecoder();
      return new Assignment(
          new String(decoder.decode(parts[1]), StandardCharsets.UTF_8),
          new String(decoder.decode(parts[2]), StandardCharsets.UTF_8),
          Long.parseLong(parts[3])
      );
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Runtime MCP session directory entry is invalid",
          ex
      );
    }
  }

  private static Duration validDuration(
      final Duration value,
      final Duration minimum,
      final Duration maximum,
      final String name
  ) {
    if (value == null || value.compareTo(minimum) < 0
        || value.compareTo(maximum) > 0) {
      throw new IllegalStateException(name + " is invalid");
    }
    return value;
  }

  private static String framed(final String value) {
    return value.length() + ":" + value;
  }

  private static String required(final String value, final String name) {
    String normalized = value == null ? null : value.trim();
    if (normalized == null || normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }

  private static String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8))
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static IllegalStateException unavailable(
      final DataAccessException exception
  ) {
    return new IllegalStateException(
        "Runtime MCP session directory is unavailable",
        exception
    );
  }

  private enum TouchOperation {
    TOUCH,
    DELETE
  }
}
