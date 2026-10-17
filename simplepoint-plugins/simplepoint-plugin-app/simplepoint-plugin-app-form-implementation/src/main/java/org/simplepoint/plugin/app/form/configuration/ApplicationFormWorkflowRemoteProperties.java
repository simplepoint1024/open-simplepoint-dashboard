package org.simplepoint.plugin.app.form.configuration;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Explicit Workflow OAuth clients allowed to validate server-owned form bindings and submissions.
 */
@ConfigurationProperties(prefix = "simplepoint.app.form.workflow-remote")
public class ApplicationFormWorkflowRemoteProperties {
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
