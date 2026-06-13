package com.simplepoint.service.router.loadbalance;

import com.simplepoint.service.router.routing.ServiceRoute;
import java.util.List;
import java.util.Optional;

/**
 * Selects one route from discovery candidates.
 */
public interface LoadBalancer {

  /**
   * Selects a route.
   *
   * @param routes candidate routes
   * @return selected route
   */
  Optional<ServiceRoute> choose(List<ServiceRoute> routes);
}
