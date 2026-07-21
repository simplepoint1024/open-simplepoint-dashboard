package org.simplepoint.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.security.entity.ResourceScopeType;

/** Central compatibility rules between declared resource scopes and a request context. */
public final class ResourceScopePolicy {

  private static final Set<ResourceScopeType> DEFAULT_SCOPES =
      Collections.unmodifiableSet(EnumSet.of(ResourceScopeType.SYSTEM));

  private ResourceScopePolicy() {
  }

  /**
   * Resolves an empty legacy declaration to the fail-safe system scope.
   *
   * @param scopes declared scopes
   * @return a non-empty immutable scope set
   */
  public static Set<ResourceScopeType> effectiveScopes(final Set<ResourceScopeType> scopes) {
    if (scopes == null || scopes.isEmpty()) {
      return DEFAULT_SCOPES;
    }
    return Collections.unmodifiableSet(EnumSet.copyOf(scopes));
  }

  /** Returns whether the resource may be used in the supplied authorization context. */
  public static boolean isAccessible(
      final Set<ResourceScopeType> scopes,
      final AuthorizationContext context
  ) {
    if (context == null || context.getScopeType() == null) {
      return false;
    }
    Set<ResourceScopeType> effectiveScopes = effectiveScopes(scopes);
    if (context.getScopeType() == AuthorizationScopeType.PLATFORM) {
      if (effectiveScopes.contains(ResourceScopeType.PLATFORM)) {
        return true;
      }
      return effectiveScopes.contains(ResourceScopeType.SYSTEM)
          && Boolean.TRUE.equals(context.getIsAdministrator());
    }
    if (context.getScopeType() == AuthorizationScopeType.TENANT) {
      return effectiveScopes.contains(ResourceScopeType.TENANT);
    }
    return context.getScopeType() == AuthorizationScopeType.PERSONAL
        && effectiveScopes.contains(ResourceScopeType.PERSONAL);
  }

  /** Returns whether all child scopes are contained by the parent resource boundary. */
  public static boolean isValidChild(
      final Set<ResourceScopeType> parentScopes,
      final Set<ResourceScopeType> childScopes
  ) {
    return effectiveScopes(parentScopes).containsAll(effectiveScopes(childScopes));
  }
}
