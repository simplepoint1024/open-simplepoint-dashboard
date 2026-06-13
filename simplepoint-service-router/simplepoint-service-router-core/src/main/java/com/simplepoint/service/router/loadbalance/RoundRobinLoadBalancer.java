package com.simplepoint.service.router.loadbalance;

import com.simplepoint.service.router.routing.ServiceRoute;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin load balancer.
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

  private final AtomicInteger cursor = new AtomicInteger();

  @Override
  public Optional<ServiceRoute> choose(final List<ServiceRoute> routes) {
    if (routes == null || routes.isEmpty()) {
      return Optional.empty();
    }
    int index = Math.floorMod(cursor.getAndIncrement(), routes.size());
    return Optional.of(routes.get(index));
  }
}
