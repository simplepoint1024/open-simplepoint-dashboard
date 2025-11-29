package org.simplepoint.security.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.security.base.BaseUser;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Implementation of AuthorizationContextCacheable using Redis as the caching mechanism.
 *
 * <p>使用 Redis 作为缓存机制的 AuthorizationContextCacheable 实现</p>
 */
@Slf4j
public class RedisAuthorizationContextCacheable implements AuthorizationContextCacheable {
  private final StringRedisTemplate stringRedisTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Constructs a RedisAuthorizationContextCacheable with the specified StringRedisTemplate.
   *
   * <p>使用指定的 StringRedisTemplate 构造 RedisAuthorizationContextCacheable</p>
   *
   * @param stringRedisTemplate the StringRedisTemplate for Redis operations 用于 Redis 操作的 StringRedisTemplate
   */
  public RedisAuthorizationContextCacheable(StringRedisTemplate stringRedisTemplate) {
    this.stringRedisTemplate = stringRedisTemplate;
  }

  @Override
  public <T extends BaseUser> void cacheUserContext(String username, T user) {
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
      stringRedisTemplate.opsForValue().set(USER_CONTEXT_CACHE_NAME + username, json);
      log.debug("Cached user [{}] context: {}", username, json);
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize user [{}] context", username, e);
    }
  }

  @Override
  public <T extends BaseUser> T getUserContext(String username, Class<T> clazz) {
    String json = stringRedisTemplate.opsForValue().get(USER_CONTEXT_CACHE_NAME + username);
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, clazz);
    } catch (Exception e) {
      log.error("Failed to deserialize user [{}] context", username, e);
      return null;
    }
  }

  @Override
  public void cacheUserPermission(String username, Collection<String> permissions) {
    try {
      String json = objectMapper.writeValueAsString(permissions);
      stringRedisTemplate.opsForValue().set(USER_PERMISSION_CACHE_NAME + username, json);
      log.debug("Cached user [{}] permissions: {}", username, json);
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize user [{}] permissions", username, e);
    }
  }

  @Override
  public Collection<String> getUserPermission(String username) {
    String json = stringRedisTemplate.opsForValue().get(USER_PERMISSION_CACHE_NAME + username);
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {
      });
    } catch (Exception e) {
      log.error("Failed to deserialize user [{}] permissions", username, e);
      return null;
    }
  }

}
