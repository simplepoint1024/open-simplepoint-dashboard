package org.simplepoint.security.oauth2.resourceserver;

import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.core.context.UserContext;

/**
 * ResourceServerUserContext 是一个实现 UserContext 接口的类，
 * 用于提供 OAuth 2.0 资源服务器中的用户信息上下文
 *
 * <p>This class implements the UserContext interface,
 * providing user information context in an OAuth 2.0 resource server.
 */
public interface ResourceServerUserContext<T extends BaseUser> extends UserContext<T> {
}
