package org.simplepoint.security.decorator;

import java.util.Map;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.Authentication;

/**
 * Interface for decorating access tokens.
 * 访问令牌装饰器接口
 */
public interface TokenDecorator {

  /**
   * Resolves token claims based on the provided authentication and token type.
   *
   * <p>根据提供的认证和令牌类型解析令牌声明</p>
   *
   * @param authentication the authentication object
   *                       认证对象
   * @param tokenType      the type of the token (e.g., access token, refresh token)
   *                       令牌类型（例如，访问令牌，刷新令牌）
   * @return a map of resolved token claims
   *         已解析的令牌声明映射
   */
  Map<String, Object> resolveTokenClaims(Authentication authentication, String tokenType);

}
