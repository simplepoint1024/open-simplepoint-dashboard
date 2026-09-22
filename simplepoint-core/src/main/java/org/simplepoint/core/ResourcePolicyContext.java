package org.simplepoint.core;

/** Server-selected page resource for the current HTTP request. Never populated from a client header. */
public final class ResourcePolicyContext {
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private ResourcePolicyContext() {}

  public static String get() {
    return CURRENT.get();
  }

  public static void set(String resourceCode) {
    if (resourceCode == null || resourceCode.isBlank()) CURRENT.remove();
    else CURRENT.set(resourceCode);
  }

  public static void clear() {
    CURRENT.remove();
  }
}
