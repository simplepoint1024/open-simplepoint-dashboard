package org.simplepoint.security;

import java.util.Set;
import org.simplepoint.security.cache.AuthorityRecord;

/**
 * Provider is an interface that defines a contract for providing instances of AuthorizationContextService based on a unique key.
 *
 * <p>Provider 是一个接口，定义了一个基于唯一键提供 AuthorizationContextService 实例的契约</p>
 */
public interface Provider {
  /**
   * Retrieves the unique key associated with this provider.
   *
   * <p>获取与此提供程序关联的唯一键</p>
   *
   * @return the unique key for this provider 此提供程序的唯一键
   */
  String getKey();

  /**
   * Retrieves a set of AuthorityRecord objects associated with the specified user ID.
   *
   * <p>根据指定的用户 ID 获取关联的 AuthorityRecord 对象集合</p>
   *
   * @param userId the ID of the user for whom to retrieve authority records 要检索权限记录的用户 ID
   * @return a set of AuthorityRecord objects representing the user's authorities 返回一个 AuthorityRecord 对象集合，表示用户的权限
   */
  Set<AuthorityRecord> get(String userId);

  /**
   * Refreshes the provider for a specific user ID, which may involve clearing cached data or reloading
   * the user's authority records from the underlying data source to ensure that the latest information
   * is available for authorization decisions.
   *
   * <p>刷新特定用户 ID 的提供程序，这可能涉及清除缓存数据或从底层数据源重新加载用户的权限记录，以确保为授权决策提供最新的信息</p>
   *
   * @param userId the ID of the user for whom to refresh the provider 要刷新提供程序的用户 ID
   */
  void refresh(String userId);
}
