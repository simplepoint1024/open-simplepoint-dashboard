package org.simplepoint.plugin.ai.core.api.constants;

/**
 * HTTP paths exposed by the AI plugin.
 */
public final class AiPaths {

  public static final String WORKBENCH_BASE = "/workbench";

  public static final String PROVIDERS = WORKBENCH_BASE + "/providers";

  public static final String MODELS = WORKBENCH_BASE + "/models";

  public static final String BILLING = WORKBENCH_BASE + "/billing";

  public static final String INVOCATIONS = BILLING + "/invocations";

  public static final String API_KEYS = WORKBENCH_BASE + "/api-keys";

  public static final String COMPATIBLE_API = "/v1";

  private AiPaths() {
  }
}
