package org.simplepoint.core;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * RequestContextHolder is a utility class that provides methods to set, get, and clear context information
 * in the current HTTP request using Spring's RequestAttributes.
 *
 * <p>RequestContextHolder 是一个工具类，提供了在当前 HTTP 请求中使用 Spring 的 RequestAttributes 设置、获取和清除上下文信息的方法。</p>
 */
public class RequestContextHolder {

  public static final String AUTHORIZATION_CONTEXT_KEY = "AUTH_CTX";
  public static final String TENANT_CONTEXT_KEY = "TENANT_CTX";
  public static final String USER_CONTEXT_KEY = "USER_CTX";

  /**
   * Sets a context value for the current HTTP request.
   *
   * @param name  the name of the context attribute
   * @param value the value to set for the context attribute
   * @param <T>   the type of the context value
   */
  public static <T> void setContext(String name, T value) {
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (attrs != null) {
      attrs.setAttribute(name, value, RequestAttributes.SCOPE_REQUEST);
    }
  }

  /**
   * Retrieves a context value for the current HTTP request.
   *
   * @param name  the name of the context attribute to retrieve
   * @param clazz the class type of the context value to retrieve
   * @param <T>   the type of the context value
   * @return the context value associated with the specified name, or null if not found or if the request attributes are not available
   * @throws IllegalStateException if the context value is found but is not of the expected type
   */
  public static <T> T getContext(String name, Class<T> clazz) {
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
      return null;
    }
    Object value = attrs.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
    if (value == null) {
      return null;
    }
    if (!clazz.isInstance(value)) {
      throw new IllegalStateException("Context value is not of type " + clazz.getName());
    }
    return clazz.cast(value);
  }

  /**
   * Clears a context value for the current HTTP request.
   *
   * @param name the name of the context attribute to clear
   */
  public static void clearContext(String name) {
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    if (attrs != null) {
      attrs.removeAttribute(name, RequestAttributes.SCOPE_REQUEST);
    }
  }
}
