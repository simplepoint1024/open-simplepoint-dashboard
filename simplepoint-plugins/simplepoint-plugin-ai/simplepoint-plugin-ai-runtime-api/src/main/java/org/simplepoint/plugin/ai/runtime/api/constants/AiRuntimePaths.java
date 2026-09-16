package org.simplepoint.plugin.ai.runtime.api.constants;

/**
 * HTTP paths owned by the AI OCI runtime control-plane domain.
 */
public final class AiRuntimePaths {

  public static final String NODES = "/workbench/runtime/nodes";

  public static final String WORKLOADS = "/workbench/runtime/workloads";

  public static final String POOLS = "/workbench/runtime/pools";

  public static final String PROFILES = "/workbench/runtime/profiles";

  public static final String SECRETS = "/workbench/runtime/secrets";

  public static final String INTERNAL = "/internal/runtime";

  public static final String INTERNAL_NODES = INTERNAL + "/nodes";

  private AiRuntimePaths() {
  }
}
