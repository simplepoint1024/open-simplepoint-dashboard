package org.simplepoint.security.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.security.decorator.UserLoginDecorator;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Implementation of AuthorizationContextCacheable using Redis as the caching mechanism.
 *
 * <p>使用 Redis 作为缓存机制的 AuthorizationContextCacheable 实现</p>
 */
@Slf4j
public class RedisAuthorizationContextCacheable implements AuthorizationContextCacheable {
  private final StringRedisTemplate stringRedisTemplate;
  private final Set<UserLoginDecorator> userLoginDecorators;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Constructs a RedisAuthorizationContextCacheable with the specified dependencies.
   *
   * <p>使用指定的依赖项构造 RedisAuthorizationContextCacheable</p>
   *
   * @param stringRedisTemplate the StringRedisTemplate for Redis operations 用于 Redis 操作的 StringRedisTemplate
   */
  public RedisAuthorizationContextCacheable(StringRedisTemplate stringRedisTemplate,
                                            Set<UserLoginDecorator> userLoginDecorators) {
    this.stringRedisTemplate = stringRedisTemplate;
    this.userLoginDecorators = userLoginDecorators;
  }

  @Override
  public <T extends BaseUser> void cacheUserContext(String username, T user) {
    String cacheKey = this.resolveCacheKey(username, user);
    try {
      // Clear permissions and authorities before caching the user context
      // 在缓存用户上下文之前清除权限和权限列表
      if (user.getPermissions() != null) {
        user.getPermissions().clear();
      }
      if (user.getPermissions() != null) {
        user.getAuthorities().clear();
      }
      String json = objectMapper.writeValueAsString(user);
      stringRedisTemplate.opsForValue().set(USER_CONTEXT_CACHE_NAME + cacheKey, json);
      log.debug("Cached user [{}] context: {}", cacheKey, json);
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize user [{}] context", cacheKey, e);
    }
  }

  @Override
  public <T extends BaseUser> T getUserContext(String username, Class<T> clazz) {
    String cacheKey = this.resolveCacheKey(username);
    String json = stringRedisTemplate.opsForValue().get(USER_CONTEXT_CACHE_NAME + cacheKey);
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, clazz);
    } catch (Exception e) {
      log.error("Failed to deserialize user [{}] context", cacheKey, e);
      return null;
    }
  }

  @Override
  public void cacheUserPermission(String username, Collection<String> permissions) {
    String cacheKey = resolveCacheKey(username);
    try {
      String json = objectMapper.writeValueAsString(permissions);
      stringRedisTemplate.opsForValue().set(USER_PERMISSION_CACHE_NAME + cacheKey, json);
      log.debug("Cached user [{}] permissions: {}", cacheKey, json);
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize user [{}] permissions", cacheKey, e);
    }
  }

  @Override
  public Collection<String> getUserPermission(String username) {
    String cacheKey = resolveCacheKey(username);
    String json = stringRedisTemplate.opsForValue().get(USER_PERMISSION_CACHE_NAME + cacheKey);
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {
      });
    } catch (Exception e) {
      log.error("Failed to deserialize user [{}] permissions", cacheKey, e);
      return null;
    }
  }

  /**
   * Resolves the cache key using the registered UserLoginDecorators.
   *
   * @param cacheKey the original cache key
   * @return the resolved cache key
   */
  private String resolveCacheKey(String cacheKey) {
    String resolvedCacheKey = cacheKey;
    for (UserLoginDecorator decorator : userLoginDecorators) {
      resolvedCacheKey = decorator.resolveCacheKey(resolvedCacheKey);
    }
    return resolvedCacheKey;
  }

  /**
   * Resolves the cache key for the given user using the registered UserLoginDecorators.
   *
   * @param cacheKey the original cache key
   * @param user     the user for whom the cache key is being resolved
   * @param <T>      the type of the user
   * @return the resolved cache key
   */
  private <T extends BaseUser> String resolveCacheKey(String cacheKey, T user) {
    String resolvedCacheKey = cacheKey;
    for (UserLoginDecorator decorator : userLoginDecorators) {
      resolvedCacheKey = decorator.resolveCacheKey(resolvedCacheKey, user);
    }
    return resolvedCacheKey;
  }
}
