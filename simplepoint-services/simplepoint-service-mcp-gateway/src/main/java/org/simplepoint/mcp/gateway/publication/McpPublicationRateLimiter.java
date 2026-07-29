package org.simplepoint.mcp.gateway.publication;

import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Distributed fixed-window limiter shared by horizontally scaled Gateway replicas.
 */
@Component
public class McpPublicationRateLimiter {

  private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
      """
      local current = redis.call('INCR', KEYS[1])
      if current == 1 then
        redis.call('EXPIRE', KEYS[1], ARGV[2])
      end
      if current > tonumber(ARGV[1]) then
        return 0
      end
      return 1
      """,
      Long.class
  );

  private final StringRedisTemplate redisTemplate;

  /**
   * Creates the distributed rate limiter.
   */
  public McpPublicationRateLimiter(final StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  /**
   * Consumes one request from the current distributed minute window.
   */
  public boolean tryAcquire(
      final String publicationCode,
      final String identity,
      final int limit
  ) {
    long window = Instant.now().getEpochSecond() / 60;
    String safeIdentity = Integer.toHexString(
        (identity == null ? "anonymous" : identity).hashCode()
    );
    String key = "simplepoint:mcp:publication:rate:"
        + publicationCode + ":" + safeIdentity + ":" + window;
    Long allowed = redisTemplate.execute(
        SCRIPT,
        List.of(key),
        Integer.toString(Math.max(1, limit)),
        "120"
    );
    return Long.valueOf(1).equals(allowed);
  }
}
