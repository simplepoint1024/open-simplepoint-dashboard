package org.simplepoint.plugin.dna.federation.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
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

  private static final String VERSION_KEY_PREFIX = "dna:jdbc:meta-version:";

  private final @Nullable StringRedisTemplate redisTemplate;

  private final ObjectMapper objectMapper;

  private final Map<String, AtomicLong> localVersions = new ConcurrentHashMap<>();

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
   * Scopes one metadata key by its datasource set and current distributed versions.
   *
   * @param dataSourceIds managed datasource ids participating in the result
   * @param cacheKey operation-specific metadata key
   * @return versioned cache key
   */
  public String scopedKey(
      final Collection<String> dataSourceIds,
      final String cacheKey
  ) {
    String scope = normalizedIds(dataSourceIds).stream()
        .map(id -> id + '@' + version(id))
        .collect(java.util.stream.Collectors.joining(","));
    return scope + ':' + cacheKey;
  }

  /**
   * Returns the current version token used to clear session L1 metadata caches.
   *
   * @param dataSourceIds managed datasource ids participating in the session
   * @return stable token until one datasource is invalidated
   */
  public String versionToken(final Collection<String> dataSourceIds) {
    return normalizedIds(dataSourceIds).stream()
        .map(id -> id + '@' + version(id))
        .collect(java.util.stream.Collectors.joining(","));
  }

  /**
   * Invalidates metadata entries for one datasource by advancing its namespace version.
   *
   * <p>Old Redis values remain unreachable and expire by TTL. A configured Redis failure is
   * propagated because publication cannot safely activate with inconsistent cluster metadata.</p>
   *
   * @param dataSourceId managed datasource id
   * @return new version
   */
  public long invalidateDataSource(final String dataSourceId) {
    String normalized = requireId(dataSourceId);
    long local = localVersions.computeIfAbsent(normalized, ignored -> new AtomicLong())
        .incrementAndGet();
    if (redisTemplate == null) {
      return local;
    }
    try {
      Long distributed = redisTemplate.opsForValue().increment(VERSION_KEY_PREFIX + normalized);
      if (distributed == null) {
        throw new IllegalStateException("Redis returned no metadata version");
      }
      return distributed;
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "DNA metadata cache invalidation failed for the managed datasource",
          exception
      );
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
      ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build();
      long total = 0;
      try (Cursor<String> cursor = redisTemplate.scan(options)) {
        Set<String> batch = new HashSet<>();
        while (cursor.hasNext()) {
          batch.add(cursor.next());
          if (batch.size() >= 200) {
            Long deleted = redisTemplate.delete(batch);
            total += deleted == null ? 0 : deleted;
            batch.clear();
          }
        }
        if (!batch.isEmpty()) {
          Long deleted = redisTemplate.delete(batch);
          total += deleted == null ? 0 : deleted;
        }
      }
      LOGGER.info("Flushed {} DNA JDBC metadata cache entries", total);
      return total;
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

  private long version(final String dataSourceId) {
    if (redisTemplate == null) {
      return localVersions.computeIfAbsent(dataSourceId, ignored -> new AtomicLong()).get();
    }
    try {
      String value = redisTemplate.opsForValue().get(VERSION_KEY_PREFIX + dataSourceId);
      return value == null ? 0L : Long.parseLong(value);
    } catch (RuntimeException exception) {
      LOGGER.debug(
          "Redis metadata version read failed for datasource {}; forcing a cache miss",
          dataSourceId,
          exception
      );
      return localVersions.computeIfAbsent(dataSourceId, ignored -> new AtomicLong())
          .incrementAndGet();
    }
  }

  private static java.util.List<String> normalizedIds(
      final Collection<String> dataSourceIds
  ) {
    if (dataSourceIds == null) {
      return java.util.List.of();
    }
    return dataSourceIds.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .distinct()
        .sorted(Comparator.naturalOrder())
        .toList();
  }

  private static String requireId(final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("dataSourceId is required");
    }
    return value.trim();
  }
}
