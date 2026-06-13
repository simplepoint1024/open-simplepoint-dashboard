package com.simplepoint.service.router.routing;

import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.util.StringUtils;

/**
 * Service discovery backed by Spring Cloud {@link DiscoveryClient}.
 */
public class DiscoveryClientServiceDiscovery implements ServiceDiscovery {

  private static final String CAPABILITIES_METADATA = "sp-router-capabilities";

  private static final String MAPPINGS_METADATA = "sp-router-mappings";

  private static final String VERSION_SEPARATOR = ":";

  private final DiscoveryClient discoveryClient;

  private final ServiceRouterProperties properties;

  /**
   * Creates discovery backed by Spring Cloud DiscoveryClient.
   *
   * @param discoveryClient Spring Cloud discovery client
   * @param properties router properties
   */
  public DiscoveryClientServiceDiscovery(
      final DiscoveryClient discoveryClient,
      final ServiceRouterProperties properties
  ) {
    this.discoveryClient = discoveryClient;
    this.properties = properties;
  }

  @Override
  public List<ServiceRoute> discover(final RoutedServiceMetadata service) {
    String serviceId = resolveServiceId(service);
    return discoveryClient.getInstances(serviceId).stream()
        .filter(instance -> supports(instance, service))
        .map(instance -> new ServiceRoute(
            instance.getServiceId(),
            instance.getInstanceId(),
            instance.getUri(),
            instance.getMetadata()
        ))
        .toList();
  }

  private String resolveServiceId(final RoutedServiceMetadata service) {
    String serviceRouteKey = routeKey(service);
    String routeByVersion = properties.getRoutes().get(serviceRouteKey);
    if (routeByVersion != null && !routeByVersion.isBlank()) {
      return routeByVersion;
    }

    String routeByService = properties.getRoutes().get(service.name());
    if (routeByService != null && !routeByService.isBlank()) {
      return routeByService;
    }

    String autoMapped = discoverServiceIdFromMetadata(serviceRouteKey);
    if (autoMapped != null) {
      return autoMapped;
    }

    String capabilityMatched = discoverServiceIdByCapability(serviceRouteKey);
    return capabilityMatched == null ? defaultServiceId(service.name()) : capabilityMatched;
  }

  private String discoverServiceIdFromMetadata(final String routeKey) {
    for (String serviceId : discoveryClient.getServices()) {
      for (ServiceInstance instance : discoveryClient.getInstances(serviceId)) {
        Map<String, String> metadata = instance.getMetadata();
        if (isMappedInstance(metadata, routeKey)) {
          return serviceId;
        }
      }
    }
    return null;
  }

  private String discoverServiceIdByCapability(final String routeKey) {
    for (String serviceId : discoveryClient.getServices()) {
      for (ServiceInstance instance : discoveryClient.getInstances(serviceId)) {
        if (isCapableInstance(instance.getMetadata(), routeKey)) {
          return serviceId;
        }
      }
    }
    return null;
  }

  private static boolean supports(final ServiceInstance instance, final RoutedServiceMetadata service) {
    Map<String, String> metadata = instance.getMetadata();
    if (metadata == null) {
      return true;
    }
    String capabilities = metadata.get(CAPABILITIES_METADATA);
    if (StringUtils.isEmpty(capabilities)) {
      return true;
    }
    String expected = service.name() + VERSION_SEPARATOR + service.version();
    for (String capability : capabilities.split(",")) {
      if (expected.equals(capability.trim())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isMappedInstance(final Map<String, String> metadata, final String routeKey) {
    if (metadata == null) {
      return false;
    }
    String mappings = metadata.get(MAPPINGS_METADATA);
    if (StringUtils.isEmpty(mappings)) {
      return false;
    }
    for (String mapping : mappings.split(",")) {
      String[] parts = mapping.split("=", 2);
      if (parts.length == 2 && routeKey.equals(parts[0].trim())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isCapableInstance(final Map<String, String> metadata, final String routeKey) {
    if (metadata == null) {
      return false;
    }
    String capabilities = metadata.get(CAPABILITIES_METADATA);
    if (StringUtils.isEmpty(capabilities)) {
      return false;
    }
    for (String capability : capabilities.split(",")) {
      if (routeKey.equals(capability.trim())) {
        return true;
      }
    }
    return false;
  }

  private static String routeKey(final RoutedServiceMetadata service) {
    return service.name() + VERSION_SEPARATOR + service.version();
  }

  private static String defaultServiceId(final String serviceName) {
    int separator = serviceName.indexOf('.');
    if (separator <= 0) {
      return serviceName;
    }
    return serviceName.substring(0, separator);
  }
}
