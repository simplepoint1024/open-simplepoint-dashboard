package org.simplepoint.security.decorator;

import java.util.Map;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Interface for decorating user login processes.
 * 用户登录装饰器接口
 */
public interface UserLoginDecorator {

  /**
   * Resolves the username based on tenant-specific logic.
   *
   * @param tenantUsername the username provided by the tenant
   * @param ext            additional context information
   * @return the resolved username
   */
  String resolveUsername(String tenantUsername, Map<String, String> ext);

  /**
   * Decorates the UserDetails after a successful login.
   *
   * @param userDetails the original UserDetails
   * @param ext         additional context information
   * @return the decorated UserDetails
   */
  UserDetails afterLogin(UserDetails userDetails, Map<String, String> ext);

  /**
   * Resolves the cache key for the given user.
   *
   * @param cacheKey the original cache key
   * @param user     the user for whom the cache key is being resolved
   * @param <T>      the type of the user
   * @return the resolved cache key
   */
  <T extends BaseUser> String resolveCacheKey(String cacheKey, T user);

  /**
   * Resolves the cache key for general use without user context.
   *
   * @param cacheKey the original cache key
   * @return the resolved cache key
   */
  String resolveCacheKey(String cacheKey);
}
