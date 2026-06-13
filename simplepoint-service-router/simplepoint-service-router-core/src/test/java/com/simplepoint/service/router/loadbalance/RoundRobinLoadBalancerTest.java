package com.simplepoint.service.router.loadbalance;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplepoint.service.router.routing.ServiceRoute;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoundRobinLoadBalancerTest {

  @Test
  void choosesRoutesByRoundRobin() {
    RoundRobinLoadBalancer loadBalancer = new RoundRobinLoadBalancer();
    ServiceRoute first = new ServiceRoute("sample", "one", URI.create("http://one"), Map.of());
    ServiceRoute second = new ServiceRoute("sample", "two", URI.create("http://two"), Map.of());

    assertThat(loadBalancer.choose(List.of(first, second))).contains(first);
    assertThat(loadBalancer.choose(List.of(first, second))).contains(second);
    assertThat(loadBalancer.choose(List.of(first, second))).contains(first);
  }
}
