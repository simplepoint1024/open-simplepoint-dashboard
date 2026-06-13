package com.simplepoint.service.router.exception;

/**
 * Thrown when no local or remote provider can satisfy a routed service.
 */
public class ServiceRouteNotFoundException extends ServiceRouterException {

  /**
   * Creates an exception for a missing routed provider.
   *
   * @param service routed service name
   * @param version routed service version
   */
  public ServiceRouteNotFoundException(final String service, final String version) {
    super("No provider found for routed service " + service + ":" + version);
  }
}
