package org.simplepoint.plugin.dna.federation.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Global Redis-backed L2 metadata cache for federation JDBC metadata results.
 *
 * <p>Falls back to a no-op when Redis is not available. The session-level
 * {@code ConcurrentHashMap} in {@code JdbcConnectionSession} acts as L1,
 * and this service acts as L2 shared across all sessions.</p>
 */
@Component
public class FederationMetadataCacheService {

  private static final Logger LOGGER = LoggerFactory.getLogger(FederationMetadataCacheService.class);

  private static final String KEY_PREFIX = "dna:jdbc:meta:";

  private final @Nullable StringRedisTemplate redisTemplate;

  private final ObjectMapper objectMapper;

  @Value("${simplepoint.dna.jdbc.metadata.cache.ttl-seconds:300}")
  private long ttlSeconds;

  /**
   * Creates the cache service with an optional Redis template.
   *
   * @param redisTemplate Redis template, null if Redis is not configured
   */
  public FederationMetadataCacheService(
      @Autowired(required = false) @Nullable final StringRedisTemplate redisTemplate
  ) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = new ObjectMapper()
        .findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  /**
   * Creates a no-op cache service without Redis backing.
   */
  public FederationMetadataCacheService() {
    this(null);
  }

  /**
   * Retrieves a cached metadata result.
   *
   * @param cacheKey cache key (without prefix)
   * @return cached result, or null if not found or Redis unavailable
   */
  public FederationJdbcDriverModels.TabularResult get(final String cacheKey) {
    if (redisTemplate == null || cacheKey == null) {
      return null;
    }
    try {
      String json = redisTemplate.opsForValue().get(KEY_PREFIX + cacheKey);
      if (json == null) {
        return null;
      }
      return objectMapper.readValue(json, FederationJdbcDriverModels.TabularResult.class);
    } catch (RuntimeException | JsonProcessingException ex) {
      LOGGER.debug("Redis metadata cache read failed for key {}: {}", cacheKey, ex.getMessage());
      return null;
    }
  }

  /**
   * Stores a metadata result into the cache.
   *
   * @param cacheKey cache key (without prefix)
   * @param result metadata result to cache
   */
  public void put(final String cacheKey, final FederationJdbcDriverModels.TabularResult result) {
    if (redisTemplate == null || cacheKey == null || result == null) {
      return;
    }
    try {
      String json = objectMapper.writeValueAsString(result);
      redisTemplate.opsForValue().set(KEY_PREFIX + cacheKey, json, ttlSeconds, TimeUnit.SECONDS);
    } catch (RuntimeException | JsonProcessingException ex) {
      LOGGER.debug("Redis metadata cache write failed for key {}: {}", cacheKey, ex.getMessage());
    }
  }

  /**
   * Flushes all cached metadata entries.
   *
   * @return the number of entries flushed, or -1 if Redis is unavailable
   */
  public long flushAll() {
    if (redisTemplate == null) {
      return -1;
    }
    try {
      Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
      if (keys != null && !keys.isEmpty()) {
        Long deleted = redisTemplate.delete(keys);
        LOGGER.info("Flushed {} DNA JDBC metadata cache entries", deleted);
        return deleted == null ? 0 : deleted;
      }
      return 0;
    } catch (RuntimeException ex) {
      LOGGER.warn("Redis metadata cache flush failed: {}", ex.getMessage());
      return -1;
    }
  }

  /**
   * Returns whether Redis caching is available.
   *
   * @return true if Redis is configured
   */
  public boolean isAvailable() {
    return redisTemplate != null;
  }
}
