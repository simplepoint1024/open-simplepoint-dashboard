package org.simplepoint.platform.composition;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Ensures complete resource snapshots have one declaring module in the selected deployment. */
public final class ModuleResourcePlan {
  private ModuleResourcePlan() {}

  /**
   * Derives the registration provider's exact owner-to-client policy from the full deployment. The
   * installer must publish this map, never broaden it using host names or classpath discovery.
   */
  public static Map<String, String> registrationClients(
      DeploymentPlan deployment, Map<String, String> clientOverrides) {
    var clients = ModuleClientPlan.identities(deployment, clientOverrides);
    // Also rejects multiple complete-snapshot owners in directly constructed deployment plans.
    deployment.resourceHosts();
    var policy = new TreeMap<String, String>();
    deployment
        .hosts()
        .values()
        .forEach(
            plan ->
                owners(plan).forEach((module, owner) -> policy.put(owner, clients.get(module))));
    return Collections.unmodifiableMap(policy);
  }

  /** Derives the exact registration policy for a fresh deployment's generated module clients. */
  public static Map<String, String> deploymentRegistrationClients(
      DeploymentPlan deployment, Map<String, String> clientOverrides, UUID operationId) {
    return registrationClients(
        deployment,
        ModuleClientPlan.deploymentIdentities(deployment, clientOverrides, operationId));
  }

  /** Returns declaring module to stable resource owner; an empty plan owns no declarations. */
  public static Map<String, String> owners(ModulePlan plan) {
    var owners = new TreeMap<String, String>();
    var modulesByOwner = new TreeMap<String, String>();
    for (var module : plan.modules()) {
      long registrars =
          module.initializers().stream()
              .filter(
                  type ->
                      type.getDeclaredAnnotation(ModuleInitialization.class).registersResources())
              .count();
      if (registrars > 1 || (module.resourceOwner().isPresent() && registrars != 1)) {
        throw new ModuleCompositionException("RESOURCE_INITIALIZER_REQUIRED", module.id());
      }
      if (registrars == 1 && module.resourceOwner().isEmpty()) {
        throw new ModuleCompositionException("MODULE_RESOURCE_OWNER_REQUIRED", module.id());
      }
      module
          .resourceOwner()
          .ifPresent(
              owner -> {
                if (owner.length() > 128 || !owner.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) {
                  throw new ModuleCompositionException("INVALID_RESOURCE_OWNER", module.id());
                }
                var previous = modulesByOwner.putIfAbsent(owner, module.id());
                if (previous != null) {
                  throw new ModuleCompositionException(
                      "DUPLICATE_RESOURCE_OWNER", owner + ": " + previous + ", " + module.id());
                }
                owners.put(module.id(), owner);
              });
    }
    return Collections.unmodifiableMap(owners);
  }
}
