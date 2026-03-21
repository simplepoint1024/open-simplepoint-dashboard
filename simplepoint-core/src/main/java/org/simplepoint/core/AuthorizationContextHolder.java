package org.simplepoint.core;

/**
 * AuthorizationContextHolder is an interface that provides a method to retrieve the current AuthorizationContext.
 * It serves as a holder for the authorization context, allowing other components to access the current user's authorization information.
 *
 * <p>AuthorizationContextHolder 是一个接口，提供了一个方法来检索当前的 AuthorizationContext。它作为授权上下文的持有者，允许其他组件访问当前用户的授权信息。</p>
 */
public class AuthorizationContextHolder {

  public static AuthorizationContext getContext() {
    return org.simplepoint.core.RequestContextHolder.getContext(org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
        AuthorizationContext.class);
  }
}
