package org.simplepoint.core;

import java.util.HashMap;
import java.util.Map;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * RequestContextHolder stores context information in the current HTTP request
 * or, for durable background work, in the current worker thread.
 *
 * <p>Request-scoped storage always takes precedence. Background callers must
 * clear values in a finally block before the worker thread is reused.</p>
 */
public class RequestContextHolder {

  public static final String AUTHORIZATION_CONTEXT_KEY = "AUTH_CTX";
  public static final String TENANT_CONTEXT_KEY = "TENANT_CTX";
  public static final String USER_CONTEXT_KEY = "USER_CTX";

  private static final ThreadLocal<Map<String, Object>> BACKGROUND_CONTEXT =
      new ThreadLocal<>();

  /**
   * Sets a context value for the current HTTP request or background worker.
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
      return;
    }
    Map<String, Object> context = BACKGROUND_CONTEXT.get();
    if (context == null) {
      context = new HashMap<>();
      BACKGROUND_CONTEXT.set(context);
    }
    context.put(name, value);
  }

  /**
   * Retrieves a context value for the current HTTP request or background worker.
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
    Object value = attrs == null
        ? backgroundValue(name)
        : attrs.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
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
      return;
    }
    Map<String, Object> context = BACKGROUND_CONTEXT.get();
    if (context == null) {
      return;
    }
    context.remove(name);
    if (context.isEmpty()) {
      BACKGROUND_CONTEXT.remove();
    }
  }

  private static Object backgroundValue(final String name) {
    Map<String, Object> context = BACKGROUND_CONTEXT.get();
    return context == null ? null : context.get(name);
  }
}
