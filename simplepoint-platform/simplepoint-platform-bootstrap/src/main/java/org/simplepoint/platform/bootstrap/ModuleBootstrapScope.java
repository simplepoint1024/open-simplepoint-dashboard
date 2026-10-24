package org.simplepoint.platform.bootstrap;

/** Stable registry keys shared by the modular executor and persistence provider. */
public final class ModuleBootstrapScope {
  /** Reserved namespace which cannot be a physical service discovery ID. */
  public static final String PREFIX = "module/";

  private ModuleBootstrapScope() {}

  /** Returns a bounded stable scope, independent of deployment host names. */
  public static String of(String moduleId) {
    if (moduleId == null
        || moduleId.length() > 121
        || !moduleId.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) {
      throw new IllegalArgumentException("Invalid bootstrap module identity");
    }
    return PREFIX + moduleId;
  }

  /** Validates and identifies the reserved module namespace; legacy host scopes stay unchanged. */
  public static boolean isModule(String scope) {
    if (scope != null && scope.startsWith(PREFIX)) {
      of(scope.substring(PREFIX.length()));
      return true;
    }
    return false;
  }
}
