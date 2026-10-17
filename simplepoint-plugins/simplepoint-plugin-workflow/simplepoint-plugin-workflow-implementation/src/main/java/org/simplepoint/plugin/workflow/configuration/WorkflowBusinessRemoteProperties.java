package org.simplepoint.plugin.workflow.configuration;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit OAuth2 service clients permitted to supply effective end-user authority. */
@ConfigurationProperties(prefix = "simplepoint.workflow.business-remote")
public class WorkflowBusinessRemoteProperties {
  private Set<String> allowedCallers = Set.of();

  public Set<String> getAllowedCallers() {
    return allowedCallers;
  }

  public void setAllowedCallers(Set<String> values) {
    allowedCallers =
        values == null
            ? Set.of()
            : values.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
