package org.simplepoint.plugin.rbac.resource.service.properties;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Explicit installation policy: each resource owner has one authenticated registration client. */
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "simplepoint.rbac.resource-registration")
public class ResourceRegistrationProperties {
  private Map<String, String> ownerClients = new LinkedHashMap<>();

  public Map<String, String> getOwnerClients() {
    return new LinkedHashMap<>(ownerClients);
  }

  public void setOwnerClients(Map<String, String> ownerClients) {
    this.ownerClients =
        ownerClients == null ? new LinkedHashMap<>() : new LinkedHashMap<>(ownerClients);
  }
}
