package org.simplepoint.security.pojo.dto;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.security.entity.TreeMenu;

/**
 * Represents the result of a service menu retrieval operation.
 *
 * <p>This class encapsulates the set of services and their associated routes
 * in a structured format.</p>
 *
 * @param services The set of services available.
 * @param routes   The set of routes associated with the services.
 * @author JinxuLiu
 * @since 1.0
 */
public record ServiceMenuResult(Set<ServiceEntry> services, Collection<TreeMenu> routes, String entryPoint) {
  public static final ServiceMenuResult EMPTY =
      new ServiceMenuResult(Set.of(), Set.of(), null);

  /**
   * Creates a new instance of {@link ServiceMenuResult} with the specified services and routes.
   *
   * @param services the set of services
   * @param routes   the collection of routes
   * @return a new {@link ServiceMenuResult} instance
   */
  public static ServiceMenuResult of(Set<ServiceEntry> services, Collection<TreeMenu> routes) {
    return new ServiceMenuResult(services, routes, "/mf/mf-manifest.json");
  }

  /**
   * Creates a new instance of {@link ServiceMenuResult} with the specified services, routes, and entry point.
   *
   * @param services   the set of services
   * @param routes     the collection of routes
   * @param entryPoint the entry point of the service menu
   * @return a new {@link ServiceMenuResult} instance
   */
  public static ServiceMenuResult of(Set<ServiceEntry> services, Collection<TreeMenu> routes, String entryPoint) {
    return new ServiceMenuResult(services, routes, entryPoint);
  }

  /**
   * Represents a service with its name and entry point.
   *
   * @param name  the name of the service
   * @param entry the entry point of the service
   */
  public record ServiceEntry(String name, String entry) {
    /**
     * Creates a new instance of {@link ServiceEntry} with the specified name and entry point.
     *
     * @param name  the name of the service
     * @param entry the entry point of the service
     * @return a new {@link ServiceEntry} instance
     */
    public static ServiceEntry of(String name, String entry) {
      return new ServiceEntry(name, entry);
    }

    /**
     * Creates a new instance of {@link ServiceEntry} with the specified name and no entry point.
     *
     * @param name the name of the service
     * @return a new {@link ServiceEntry} instance
     */
    public static ServiceEntry of(String name) {
      return ServiceEntry.of(name, null);
    }
  }
}
