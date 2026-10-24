package org.simplepoint.platform.composition;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Non-secret module client identities shared by runtime credential binding and installation. */
public final class ModuleClientPlan {
  private ModuleClientPlan() {}

  /** Resolves selected module identities without granting privileges or creating credentials. */
  public static Map<String, String> identities(ModulePlan plan, Map<String, String> overrides) {
    return identities(plan.modules(), overrides);
  }

  /** Rejects shared identities across hosts as well as within one process. */
  public static Map<String, String> identities(
      DeploymentPlan deployment, Map<String, String> overrides) {
    return identities(
        deployment.hosts().values().stream().flatMap(plan -> plan.modules().stream()).toList(),
        overrides);
  }

  private static Map<String, String> identities(
      Collection<ModuleDescriptor> modules, Map<String, String> overrides) {
    var identities = new TreeMap<String, String>();
    var clients = new HashMap<String, String>();
    for (ModuleDescriptor module : modules) {
      String client = overrides.getOrDefault(module.id(), "simplepoint-module-" + module.id());
      if (!validClient(client)) {
        throw new ModuleCompositionException("INVALID_MODULE_CLIENT_ID", module.id());
      }
      if (identities.putIfAbsent(module.id(), client) != null) {
        throw new ModuleCompositionException("DUPLICATE_MODULE_HOST", module.id());
      }
      if (clients.putIfAbsent(client, module.id()) != null) {
        throw new ModuleCompositionException("SHARED_MODULE_CLIENT_ID", module.id());
      }
    }
    if (!identities.keySet().containsAll(overrides.keySet())) {
      throw new ModuleCompositionException(
          "UNKNOWN_MODULE_CLIENT_ID", "Unselected module override");
    }
    return Collections.unmodifiableMap(identities);
  }

  /**
   * Derives fresh, host-independent client IDs for one durable installer operation. The installer
   * must provision new secrets and retain this operation ID across retries; a new release needs a
   * new operation ID. This pure plan never creates, reuses or activates OAuth credentials.
   */
  public static Map<String, String> deploymentIdentities(
      DeploymentPlan deployment, Map<String, String> overrides, UUID operationId) {
    if (operationId == null) {
      throw new ModuleCompositionException("INVALID_DEPLOYMENT_OPERATION", "operationId");
    }
    var clients = new TreeMap<String, String>();
    identities(deployment, overrides)
        .forEach((module, base) -> clients.put(module, deploymentClientId(base, operationId)));
    return identities(deployment, clients);
  }

  /**
   * Shared non-secret identity format for planning and the authority that activates a deployment.
   */
  public static String deploymentClientId(String base, UUID operationId) {
    String client = base + deploymentSuffix(operationId);
    if (!validClient(base) || !validClient(client)) {
      throw new ModuleCompositionException("INVALID_MODULE_CLIENT_ID", "deployment client");
    }
    return client;
  }

  /** Checks the exact installer operation binding without granting any caller authority. */
  public static boolean belongsToDeployment(String client, UUID operationId) {
    if (!validClient(client) || operationId == null) {
      return false;
    }
    String suffix = deploymentSuffix(operationId);
    return client.endsWith(suffix)
        && validClient(client.substring(0, client.length() - suffix.length()));
  }

  private static String deploymentSuffix(UUID operationId) {
    if (operationId == null) {
      throw new ModuleCompositionException("INVALID_DEPLOYMENT_OPERATION", "operationId");
    }
    return "-deployment-" + operationId.toString().replace("-", "");
  }

  private static boolean validClient(String client) {
    return client != null
        && !client.isBlank()
        && client.length() <= 256
        && client
            .codePoints()
            .noneMatch(
                character ->
                    Character.isWhitespace(character)
                        || Character.getType(character) == Character.SURROGATE
                        || Character.isISOControl(character)
                        || character == '*');
  }
}
