package org.simplepoint.data.redis.configuration;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.simplepoint.api.lock.DistributedLocking;
import org.simplepoint.data.redis.lock.RedissonDistributedLocking;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis auto-configuration for setting up distributed locking using Redisson.
 * This class configures the necessary beans to enable distributed locking functionality
 * in a Spring application.
 */
@Configuration(proxyBeanMethods = false)
public class RedisAutoConfiguration {

  /**
   * Creates a DistributedLocking bean using RedissonClient.
   * This bean is only created if no existing
   * RedissonClient bean is found in the application context.
   *
   * @param redisson the RedissonClient used to initialize distributed locking
   * @return a DistributedLocking instance based on Redisson
   */
  @Bean
  @ConditionalOnMissingBean(RedissonClient.class)
  public DistributedLocking<RLock> redissonClient(RedissonClient redisson) {
    return new RedissonDistributedLocking(redisson);
  }
}

