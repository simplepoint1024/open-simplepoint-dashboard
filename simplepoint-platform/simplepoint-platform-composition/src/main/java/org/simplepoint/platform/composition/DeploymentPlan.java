package org.simplepoint.platform.composition;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Validated logical modules assigned to physical services, independent of replica count.
 *
 * @param hosts service IDs and their local assembly plans
 * @param capabilityHosts unique service ID for each published capability/version
 */
public record DeploymentPlan(Map<String, ModulePlan> hosts, Map<String, String> capabilityHosts) {

  /** Freezes the validated deployment. */
  public DeploymentPlan {
    hosts = Collections.unmodifiableMap(new TreeMap<>(hosts));
    capabilityHosts = Collections.unmodifiableMap(new TreeMap<>(capabilityHosts));
  }

  /** Returns unique resource-owner hosts; partial snapshots from two hosts are forbidden. */
  public Map<String, String> resourceHosts() {
    var all =
        new ModulePlan(
            hosts.values().stream().flatMap(plan -> plan.modules().stream()).toList(),
            Map.of(),
            Map.of());
    ModuleResourcePlan.owners(all);
    var result = new TreeMap<String, String>();
    hosts.forEach(
        (host, plan) ->
            ModuleResourcePlan.owners(plan).values().forEach(owner -> result.put(owner, host)));
    return Collections.unmodifiableMap(result);
  }

  /** Returns public subtree-to-host routes without coupling browser URLs to service names. */
  public Map<String, String> httpRoutes() {
    var all =
        new ModulePlan(
            hosts.values().stream().flatMap(plan -> plan.modules().stream()).toList(),
            Map.of(),
            Map.of());
    ModuleHttpPlan.from(all);
    var routes = new TreeMap<String, String>();
    hosts.forEach(
        (host, plan) ->
            ModuleHttpPlan.from(plan)
                .routes()
                .values()
                .forEach(paths -> paths.forEach(path -> routes.put(path, host))));
    return Collections.unmodifiableMap(routes);
  }

  /**
   * Derives only the remote routes needed by this service; local capabilities stay local.
   *
   * @param host physical service ID
   * @return capability/version to physical service ID
   */
  public Map<String, String> routesFor(final String host) {
    ModulePlan plan = hosts.get(host);
    if (plan == null) {
      throw new ModuleCompositionException("UNKNOWN_HOST", host);
    }
    var routes = new TreeMap<String, String>();
    plan.remoteCapabilities().keySet().forEach(key -> routes.put(key, capabilityHosts.get(key)));
    return Collections.unmodifiableMap(routes);
  }
}
