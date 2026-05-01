package org.simplepoint.security.token;

import java.time.Duration;
import java.time.Instant;
import org.simplepoint.cache.CacheService;
import org.springframework.util.StringUtils;

/**
 * Stores revoked JWT ids until their natural expiration time.
 */
public class TokenRevocationService {

  private static final String DEFAULT_KEY_PREFIX = "simplepoint:security:revoked-token:";

  private final CacheService cacheService;

  private final String keyPrefix;

  public TokenRevocationService(final CacheService cacheService) {
    this(cacheService, DEFAULT_KEY_PREFIX);
  }

  public TokenRevocationService(final CacheService cacheService, final String keyPrefix) {
    this.cacheService = cacheService;
    this.keyPrefix = keyPrefix;
  }

  public void revoke(final String tokenId, final Instant expiresAt) {
    if (!StringUtils.hasText(tokenId) || expiresAt == null) {
      return;
    }
    long ttlSeconds = Duration.between(Instant.now(), expiresAt).getSeconds();
    if (ttlSeconds > 0) {
      cacheService.put(keyPrefix + tokenId, Boolean.TRUE, ttlSeconds);
    }
  }

  public boolean isRevoked(final String tokenId) {
    if (!StringUtils.hasText(tokenId)) {
      return false;
    }
    return Boolean.TRUE.equals(cacheService.get(keyPrefix + tokenId, Boolean.class));
  }
}
