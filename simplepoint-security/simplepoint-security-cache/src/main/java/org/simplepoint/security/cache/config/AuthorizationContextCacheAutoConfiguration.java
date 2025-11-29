package org.simplepoint.security.cache.config;

import lombok.extern.slf4j.Slf4j;
import org.simplepoint.security.cache.AuthorizationContextCacheable;
import org.simplepoint.security.cache.RedisAuthorizationContextCacheable;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Auto-configuration class for authorization context caching.
 */
@Slf4j
@AutoConfiguration
public class AuthorizationContextCacheAutoConfiguration {

  /**
   * Creates a Redis-based implementation of AuthorizationContextCacheable.
   *
   * @param stringRedisTemplate the StringRedisTemplate for Redis operations
   * @return an instance of RedisAuthorizationContextCacheable
   */
  @Bean
  @ConditionalOnClass(StringRedisTemplate.class)
  @ConditionalOnMissingBean(AuthorizationContextCacheable.class)
  public AuthorizationContextCacheable authorizationContextCacheable(StringRedisTemplate stringRedisTemplate) {
    log.info("init authorizationContextCacheable by RedisAuthorizationContextCacheable");
    return new RedisAuthorizationContextCacheable(stringRedisTemplate);
  }
}
