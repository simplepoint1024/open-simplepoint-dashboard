package org.simplepoint.security.cache;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.api.security.base.BaseUser;

/**
 * Marker interface indicating that an implementing class
 * can be cached within an authorization context.
 *
 * <p>标记接口，表示实现该接口的类可以在授权上下文中被缓存</p>
 */
public interface AuthorizationContextCacheable {
  /**
   * The name of the cache used for storing user contexts.
   *
   * <p>用于存储用户上下文的缓存名称</p>
   */
  String USER_CONTEXT_CACHE_NAME = "simplepoint:authorization:user_context:";

  /**
   * The name of the cache used for storing user permissions.
   *
   * <p>用于存储用户权限的缓存名称</p>
   */
  String USER_PERMISSION_CACHE_NAME = "simplepoint:authorization:user_permission:";

  /**
   * Caches the user context for the specified username.
   *
   * <p>缓存指定用户名的用户上下文</p>
   *
   * @param username the username associated with the user context 关联用户上下文的用户名
   * @param user     the user context to be cached 要缓存的用户上下文
   * @param <T>      the type of the user context 用户上下文的类型
   */
  <T extends BaseUser> void cacheUserContext(String username, T user);

  /**
   * Retrieves the cached user context for the specified username.
   *
   * <p>检索指定用户名的缓存用户上下文</p>
   *
   * @param username the username associated with the user context 关联用户上下文的用户名
   * @param clazz    the class type of the user context 用户上下文的类类型
   * @param <T>      the type of the user context 用户上下文的类型
   * @return the cached user context, or null if not found 缓存的用户上下文，如果未找到则返回 null
   */
  <T extends BaseUser> T getUserContext(String username, Class<T> clazz);

  /**
   * Caches the permissions for the specified username.
   *
   * <p>缓存指定用户名的权限</p>
   *
   * @param username    the username associated with the permissions 关联权限的用户名
   * @param permissions the set of permissions to be cached 要缓存的权限集合
   */
  void cacheUserPermission(String username, Collection<String> permissions);

  /**
   * Retrieves the cached permissions for the specified username.
   *
   * <p>检索指定用户名的缓存权限</p>
   *
   * @param username the username associated with the permissions 关联权限的用户名
   * @return the cached set of permissions, or null if not found 缓存的权限集合，如果未找到则返回 null
   */
  Collection<String> getUserPermission(String username);
}
