package org.simplepoint.plugin.dna.runtime.configuration;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Trusted OAuth2 client identities allowed to supply APP form invocation context. */
@ConfigurationProperties(prefix = "simplepoint.dna.runtime.form-remote")
public class DnaFormRuntimeRemoteProperties {

  private Set<String> allowedCallers = new LinkedHashSet<>();

  public Set<String> getAllowedCallers() {
    return Set.copyOf(allowedCallers);
  }

  public void setAllowedCallers(final Set<String> allowedCallers) {
    this.allowedCallers = allowedCallers == null
        ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedCallers);
  }
}
