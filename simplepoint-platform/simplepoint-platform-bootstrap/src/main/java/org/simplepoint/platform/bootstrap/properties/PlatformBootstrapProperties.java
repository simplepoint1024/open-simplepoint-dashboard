package org.simplepoint.platform.bootstrap.properties;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform bootstrap configuration properties.
 */
@Data
@ConfigurationProperties(prefix = "simplepoint.platform.bootstrap")
public class PlatformBootstrapProperties {

  private boolean enabled = true;

  private boolean failFast = true;

  private Set<String> services = new HashSet<>(Set.of("common"));

  private Map<String, BootstrapContributionSettings> contributions = new HashMap<>();

  /**
   * Checks whether bootstrap execution is enabled for a service.
   *
   * @param serviceName the current service name
   * @return true when enabled
   */
  public boolean isServiceEnabled(String serviceName) {
    return services == null || services.isEmpty() || services.contains(serviceName);
  }

  /**
   * Checks whether a contribution is enabled.
   *
   * @param contribution the bootstrap contribution
   * @return true when enabled
   */
  public boolean isContributionEnabled(BootstrapContribution contribution) {
    if (contribution == null) {
      return false;
    }
    BootstrapContributionSettings settings = findSettings(contribution);
    return settings == null || settings.isEnabled();
  }

  private BootstrapContributionSettings findSettings(BootstrapContribution contribution) {
    BootstrapContributionSettings settings = contributions.get(contribution.contributionKey());
    if (settings != null) {
      return settings;
    }
    return contributions.get(
        contribution.moduleCode() + ":" + contribution.contributionType() + ":" + contribution.contributionKey()
    );
  }
}
