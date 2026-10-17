package org.simplepoint.plugin.rbac.tenant.service.properties;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Maps authenticated service clients to the resource namespace they may own. */
@Configuration
@ConfigurationProperties(prefix = "simplepoint.rbac.managed-resource")
public class ManagedTenantResourceProperties {

  private Map<String, String> callerOwners = new LinkedHashMap<>();

  public Map<String, String> getCallerOwners() {
    return Map.copyOf(callerOwners);
  }

  public void setCallerOwners(final Map<String, String> callerOwners) {
    this.callerOwners = callerOwners == null
        ? new LinkedHashMap<>() : new LinkedHashMap<>(callerOwners);
  }
}
