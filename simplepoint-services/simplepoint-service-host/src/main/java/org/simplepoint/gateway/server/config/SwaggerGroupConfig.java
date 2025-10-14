package org.simplepoint.gateway.server.config;

import java.util.Set;
import java.util.stream.Collectors;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * SwaggerGroupConfig is a configuration class that extends SwaggerUiConfigProperties to
 * dynamically generate Swagger UI URLs based on services registered in the DiscoveryClient.
 * This allows for automatic aggregation of API documentation from multiple services in a microservices
 * architecture.
 */
@Primary
@Configuration
public class SwaggerGroupConfig extends SwaggerUiConfigProperties {

  /**
   * The DiscoveryClient used to retrieve registered services.
   */
  private final DiscoveryClient discoveryClient;

  /**
   * Constructs a SwaggerGroupConfig with the specified DiscoveryClient.
   *
   * @param discoveryClient the DiscoveryClient used to retrieve registered services
   */
  public SwaggerGroupConfig(DiscoveryClient discoveryClient) {
    this.discoveryClient = discoveryClient;
  }

  /**
   * Retrieves a set of SwaggerUrl objects representing the API documentation URLs
   * for each service registered in the DiscoveryClient. It combines these dynamically
   * generated URLs with any statically configured URLs from the superclass.
   *
   * @return a set of SwaggerUrl objects for the Swagger UI
   */
  @Override
  public Set<SwaggerUrl> getUrls() {
    Set<SwaggerUrl> urls = discoveryClient.getServices().stream()
        .map(service -> {
          SwaggerUrl url = new SwaggerUrl();
          url.setName(service);
          url.setUrl("/" + service + "/v3/api-docs"); // 网关代理路径
          return url;
        }).collect(Collectors.toSet());
    Set<SwaggerUrl> propsUrls = super.getUrls();
    if (propsUrls != null && !propsUrls.isEmpty()) {
      urls.addAll(propsUrls);
    }
    this.setUrls(urls);
    return urls;
  }
}
