package com.simplepoint.service.router.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service router configuration properties.
 */
@ConfigurationProperties(prefix = "simplepoint.service-router")
public class ServiceRouterProperties {

  private boolean enabled = true;

  private Provider provider = new Provider();

  private Consumer consumer = new Consumer();

  private Discovery discovery = new Discovery();

  private Http http = new Http();

  /**
   * Explicit routed service to discovery service id mappings.
   *
   * <p>Key can be either {@code serviceName} (legacy) or {@code serviceName:version}.
   */
  private Map<String, String> routes = new LinkedHashMap<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public Provider getProvider() {
    return provider;
  }

  public void setProvider(final Provider provider) {
    this.provider = provider;
  }

  public Consumer getConsumer() {
    return consumer;
  }

  public void setConsumer(final Consumer consumer) {
    this.consumer = consumer;
  }

  public Discovery getDiscovery() {
    return discovery;
  }

  public void setDiscovery(final Discovery discovery) {
    this.discovery = discovery;
  }

  public Http getHttp() {
    return http;
  }

  public void setHttp(final Http http) {
    this.http = http;
  }

  public Map<String, String> getRoutes() {
    return routes;
  }

  public void setRoutes(final Map<String, String> routes) {
    this.routes = routes;
  }

  /**
   * Provider settings.
   */
  public static class Provider {

    private boolean enabled = true;

    private String exposePath = "/_simplepoint/service-router/invoke";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(final boolean enabled) {
      this.enabled = enabled;
    }

    public String getExposePath() {
      return exposePath;
    }

    public void setExposePath(final String exposePath) {
      this.exposePath = exposePath;
    }
  }

  /**
   * Consumer settings.
   */
  public static class Consumer {

    private boolean enabled = true;

    private boolean localFirst = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(final boolean enabled) {
      this.enabled = enabled;
    }

    public boolean isLocalFirst() {
      return localFirst;
    }

    public void setLocalFirst(final boolean localFirst) {
      this.localFirst = localFirst;
    }
  }

  /**
   * Discovery settings.
   */
  public static class Discovery {

    private String type = "consul";

    private Duration cacheTtl = Duration.ofSeconds(10);

    public String getType() {
      return type;
    }

    public void setType(final String type) {
      this.type = type;
    }

    public Duration getCacheTtl() {
      return cacheTtl;
    }

    public void setCacheTtl(final Duration cacheTtl) {
      this.cacheTtl = cacheTtl;
    }
  }

  /**
   * HTTP settings.
   */
  public static class Http {

    private String client = "rest-client";

    private Duration connectTimeout = Duration.ofSeconds(1);

    private Duration readTimeout = Duration.ofSeconds(3);

    public String getClient() {
      return client;
    }

    public void setClient(final String client) {
      this.client = client;
    }

    public Duration getConnectTimeout() {
      return connectTimeout;
    }

    public void setConnectTimeout(final Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
      return readTimeout;
    }

    public void setReadTimeout(final Duration readTimeout) {
      this.readTimeout = readTimeout;
    }
  }
}
