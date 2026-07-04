package org.simplepoint.security.pojo.dto;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.simplepoint.security.entity.ResourceNode;

/**
 * Result returned by the resource route endpoint.
 *
 * @param services remote services referenced by route resources
 * @param routes route resource tree
 * @param entryPoint module federation manifest entry
 * @param authorizationContext lightweight runtime scope context
 */
public record ServiceResourceRouteResult(
    Set<ServiceEntry> services,
    Collection<ResourceNode> routes,
    String entryPoint,
    Map<String, String> authorizationContext
) {
  public static final ServiceResourceRouteResult EMPTY =
      new ServiceResourceRouteResult(Set.of(), Set.of(), null, Map.of());

  /**
   * Creates a route result with the default module federation manifest.
   */
  public static ServiceResourceRouteResult of(Set<ServiceEntry> services, Collection<ResourceNode> routes) {
    return new ServiceResourceRouteResult(services, routes, "/mf/mf-manifest.json", Map.of());
  }

  /**
   * Creates a route result with a custom entry point.
   */
  public static ServiceResourceRouteResult of(
      Set<ServiceEntry> services,
      Collection<ResourceNode> routes,
      String entryPoint
  ) {
    return new ServiceResourceRouteResult(services, routes, entryPoint, Map.of());
  }

  /**
   * Adds authorization-context metadata.
   */
  public ServiceResourceRouteResult withAuthorizationContext(Map<String, String> authorizationContext) {
    return new ServiceResourceRouteResult(
        services,
        routes,
        entryPoint,
        authorizationContext == null ? Map.of() : Map.copyOf(authorizationContext)
    );
  }

  /**
   * Remote service entry.
   */
  public record ServiceEntry(String name, String entry) {
    public static ServiceEntry of(String name, String entry) {
      return new ServiceEntry(name, entry);
    }

    public static ServiceEntry of(String name) {
      return ServiceEntry.of(name, null);
    }
  }
}
