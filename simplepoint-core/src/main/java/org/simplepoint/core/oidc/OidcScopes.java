package org.simplepoint.core.oidc;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Defines constants for OpenID Connect (OIDC) scopes.
 * 定义 OpenID Connect (OIDC) 范围的常量。
 *
 * <p>These scopes are used to specify the level of access requested by the client
 * during the OIDC authentication process.</p>
 *
 * <p>这些范围用于在 OIDC 认证过程中指定客户端请求的访问级别。</p>
 *
 * @author simplepoint
 * @since 2025
 */
public class OidcScopes {
  /**
   * Prefix for all scope constants.
   * 所有范围常量的前缀。
   */
  public static final String SCOPE_PREFIX = "SCOPE_";

  /**
   * Scope for OpenID authentication.
   * OpenID 认证的范围。
   */
  public static final String OPENID = "openid";

  /**
   * Scope for accessing the user's profile information.
   * 访问用户个人信息的范围。
   */
  public static final String PROFILE = "profile";

  /**
   * Scope for accessing the user's email address.
   * 访问用户邮箱地址的范围。
   */
  public static final String EMAIL = "email";

  /**
   * Scope for accessing the user's phone number.
   * 访问用户电话号码的范围。
   */
  public static final String PHONE = "phone";

  /**
   * Scope for accessing the user's address.
   * 访问用户地址的范围。
   */
  public static final String ADDRESS = "address";

  /**
   * Scope for accessing the user's roles.
   * 访问用户角色的范围。
   */
  public static final String ROLES = "roles";

  /**
   * Scope for accessing the user's groups.
   * 访问用户组的范围。
   */
  public static final String GROUPS = "groups";

  /**
   * Scope for accessing the user's permissions.
   * 访问用户权限的范围。
   */
  public static final String PERMISSIONS = "permissions";

  /**
   * Scope for accessing the tenant information.
   * 访问租户信息的范围。
   */
  public static final String TENANT = "tenant";

  /**
   * Scope for accessing the user's organs information.
   * 访问用户机构信息的范围。
   */
  public static final String ORGANS = "organs";

  /**
   * Creates a SimpleGrantedAuthority object for the given scope.
   * 为指定的范围创建一个 SimpleGrantedAuthority 对象。
   *
   * <p>This method prefixes the provided scope with {@link #SCOPE_PREFIX}
   * to generate a fully qualified authority string.</p>
   *
   * <p>该方法会将提供的范围加上 {@link #SCOPE_PREFIX} 前缀，
   * 以生成完整的权限字符串。</p>
   *
   * @param scope the scope for which the authority is created
   *              要创建权限的范围
   * @return a SimpleGrantedAuthority object representing the scope authority
   *         表示范围权限的 SimpleGrantedAuthority 对象
   */
  public static SimpleGrantedAuthority getScopeAuthority(String scope) {
    return new SimpleGrantedAuthority(SCOPE_PREFIX + scope);
  }
}