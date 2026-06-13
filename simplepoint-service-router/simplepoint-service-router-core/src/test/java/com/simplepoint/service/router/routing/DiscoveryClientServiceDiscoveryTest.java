package com.simplepoint.service.router.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

/**
 * Tests for metadata-aware service discovery.
 */
class DiscoveryClientServiceDiscoveryTest {

  @Test
  void resolvesByExplicitServiceAndVersionRoute() {
    ServiceRouterProperties properties = new ServiceRouterProperties();
    properties.getRoutes().put("sample.GreetingService:1.0", "manual-mapped-service");
    FakeDiscoveryClient client = new FakeDiscoveryClient(
        Map.of(
            "manual-mapped-service", List.of(fakeInstance("manual-mapped-service", Map.of())),
            "other-service", List.of(fakeInstance("other-service", Map.of()))
        )
    );

    ServiceRoute route = new DiscoveryClientServiceDiscovery(client, properties).discover(
        new RoutedServiceMetadata(
            "org.sample.GreetingService",
            "sample.GreetingService",
            "1.0",
            3000L,
            0,
            "",
            List.of()
        )
    ).get(0);

    assertThat(route.serviceId()).isEqualTo("manual-mapped-service");
  }

  @Test
  void resolvesByServiceNameRouteFallback() {
    ServiceRouterProperties properties = new ServiceRouterProperties();
    properties.getRoutes().put("sample.GreetingService", "service-by-name");
    FakeDiscoveryClient client = new FakeDiscoveryClient(
        Map.of(
            "service-by-name", List.of(fakeInstance("service-by-name",
                Map.of("sp-router-capabilities", "sample.GreetingService:1.0"))),
            "other", List.of(fakeInstance("other", Map.of("sp-router-capabilities", "sample.GreetingService:1.0")))
        )
    );

    ServiceRoute route = new DiscoveryClientServiceDiscovery(client, properties).discover(
        new RoutedServiceMetadata(
            "org.sample.GreetingService",
            "sample.GreetingService",
            "1.0",
            3000L,
            0,
            "",
            List.of()
        )
    ).get(0);

    assertThat(route.serviceId()).isEqualTo("service-by-name");
  }

  @Test
  void resolvesByConsulMetadataMapping() {
    ServiceRouterProperties properties = new ServiceRouterProperties();
    FakeDiscoveryClient client = new FakeDiscoveryClient(
        Map.of(
            "sample-service", List.of(fakeInstance("sample-service",
                Map.of("sp-router-mappings", "sample.GreetingService:1.0=sample-service"))),
            "other-service", List.of(fakeInstance("other-service",
                Map.of("sp-router-mappings", "other.Service:1.0=other-service")))
        )
    );

    ServiceRoute route = new DiscoveryClientServiceDiscovery(client, properties).discover(
        new RoutedServiceMetadata(
            "org.sample.GreetingService",
            "sample.GreetingService",
            "1.0",
            3000L,
            0,
            "",
            List.of()
        )
    ).get(0);

    assertThat(route.serviceId()).isEqualTo("sample-service");
  }

  @Test
  void resolvesByCapabilityScanWhenNoMetadataMapping() {
    ServiceRouterProperties properties = new ServiceRouterProperties();
    FakeDiscoveryClient client = new FakeDiscoveryClient(
        Map.of(
            "provider", List.of(fakeInstance("provider",
                Map.of("sp-router-capabilities", "sample.GreetingService:1.0")))
        )
    );

    ServiceRoute route = new DiscoveryClientServiceDiscovery(client, properties).discover(
        new RoutedServiceMetadata(
            "org.sample.GreetingService",
            "sample.GreetingService",
            "1.0",
            3000L,
            0,
            "",
            List.of()
        )
    ).get(0);

    assertThat(route.serviceId()).isEqualTo("provider");
  }

  @Test
  void fallsBackToDefaultServiceId() {
    ServiceRouterProperties properties = new ServiceRouterProperties();
    FakeDiscoveryClient client = new FakeDiscoveryClient(
        Map.of(
            "sample", List.of(fakeInstance("sample", Map.of()))
        )
    );

    ServiceRoute route = new DiscoveryClientServiceDiscovery(client, properties).discover(
        new RoutedServiceMetadata(
            "org.sample.GreetingService",
            "sample.GreetingService",
            "1.0",
            3000L,
            0,
            "",
            List.of()
        )
    ).get(0);

    assertThat(route.serviceId()).isEqualTo("sample");
  }

  private static ServiceInstance fakeInstance(final String serviceId, final Map<String, String> metadata) {
    return new FakeServiceInstance(
        serviceId,
        "instance-" + serviceId,
        "127.0.0.1",
        8080,
        false,
        URI.create("http://127.0.0.1:8080"),
        metadata
    );
  }

  private static final class FakeDiscoveryClient implements DiscoveryClient {

    private final Map<String, List<ServiceInstance>> services;

    private FakeDiscoveryClient(final Map<String, List<ServiceInstance>> services) {
      this.services = services;
    }

    @Override
    public String description() {
      return "fake";
    }

    @Override
    public List<ServiceInstance> getInstances(final String serviceId) {
      return services.getOrDefault(serviceId, List.of());
    }

    @Override
    public List<String> getServices() {
      return List.copyOf(services.keySet());
    }
  }

  private static final class FakeServiceInstance implements ServiceInstance {

    private final String serviceId;

    private final String instanceId;

    private final String host;

    private final int port;

    private final boolean secure;

    private final URI uri;

    private final Map<String, String> metadata;

    private FakeServiceInstance(
        final String serviceId,
        final String instanceId,
        final String host,
        final int port,
        final boolean secure,
        final URI uri,
        final Map<String, String> metadata
    ) {
      this.serviceId = serviceId;
      this.instanceId = instanceId;
      this.host = host;
      this.port = port;
      this.secure = secure;
      this.uri = uri;
      this.metadata = metadata;
    }

    @Override
    public String getServiceId() {
      return serviceId;
    }

    @Override
    public String getInstanceId() {
      return instanceId;
    }

    @Override
    public String getHost() {
      return host;
    }

    @Override
    public int getPort() {
      return port;
    }

    @Override
    public boolean isSecure() {
      return secure;
    }

    @Override
    public URI getUri() {
      return uri;
    }

    @Override
    public Map<String, String> getMetadata() {
      return metadata;
    }
  }
}
