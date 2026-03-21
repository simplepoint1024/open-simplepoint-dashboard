package org.simplepoint.cache.redis.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.simplepoint.cache.CacheService;
import org.simplepoint.cache.redis.RedisJsonCacheService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * RedisJsonCacheAutoConfiguration is a Spring configuration class that defines beans for setting up a Redis-based cache service
 * that uses JSON serialization and deserialization for storing and retrieving cache entries.
 * This class is responsible for creating and configuring the CacheService bean that interacts with Redis using JSON.
 */
@AutoConfiguration
public class RedisJsonCacheAutoConfiguration {

  /**
   * Creates a bean for CacheService that uses Redis for caching, with JSON serialization and deserialization.
   *
   * @param objectMapper  the ObjectMapper for converting objects to and from JSON when storing and retrieving cache entries
   * @param redisTemplate the RedisTemplate for interacting with Redis
   * @return an instance of CacheService configured to use Redis for caching with JSON serialization
   */
  @Bean
  public CacheService redisJsonCacheService(
      ObjectMapper objectMapper,
      RedisTemplate<String, String> redisTemplate
  ) {
    return new RedisJsonCacheService(objectMapper, redisTemplate);
  }
}
