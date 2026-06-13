package com.simplepoint.service.router.routing;

import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import java.util.List;

/**
 * Discovers remote providers for routed services.
 */
public interface ServiceDiscovery {

  /**
   * Discovers candidate routes.
   *
   * @param service service metadata
   * @return candidate routes
   */
  List<ServiceRoute> discover(RoutedServiceMetadata service);
}
