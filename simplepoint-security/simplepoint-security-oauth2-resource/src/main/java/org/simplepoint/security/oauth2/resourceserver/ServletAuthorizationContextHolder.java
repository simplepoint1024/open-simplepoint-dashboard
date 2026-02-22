package org.simplepoint.security.oauth2.resourceserver;

import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ServletAuthorizationContextHolder is an implementation of the AuthorizationContextHolder interface
 * that uses the ServletRequestAttributes to store and retrieve the AuthorizationContext for the current request.
 *
 * <p>ServletAuthorizationContextHolder 是 AuthorizationContextHolder 接口的一个实现，使用 ServletRequestAttributes 来存储和检索当前请求的 AuthorizationContext。</p>
 */
@Component
public class ServletAuthorizationContextHolder implements AuthorizationContextHolder {

  private static final String KEY = "AUTH_CTX";

  /**
   * Sets the AuthorizationContext for the current request.
   * 设置当前请求的 AuthorizationContext。
   *
   * <p>Sets the AuthorizationContext for the current request.
   * This method should be called at the beginning of the request
   * processing to ensure that the authorization context is available
   * for the duration of the request.</p>
   *
   * @param ctx the AuthorizationContext to set for the current request
   */
  public static void set(AuthorizationContext ctx) {
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs != null) {
      attrs.setAttribute(KEY, ctx, RequestAttributes.SCOPE_REQUEST);
    }
  }

  /**
   * Clears the AuthorizationContext for the current request.
   *
   * <p>Usually called in a finally block by a servlet filter.</p>
   */
  public static void clear() {
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs != null) {
      attrs.removeAttribute(KEY, RequestAttributes.SCOPE_REQUEST);
    }
  }

  @Override
  public AuthorizationContext getAuthorizationContext() {
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
      return null;
    }
    return (AuthorizationContext) attrs.getAttribute(KEY, RequestAttributes.SCOPE_REQUEST);
  }
}
