package org.simplepoint.plugin.ai.core.api.constants;

/**
 * HTTP paths exposed by the AI plugin.
 */
public final class AiPaths {

  public static final String PLATFORM_BASE = "/platform/ai";

  public static final String TENANT_BASE = "/tenant/ai";

  public static final String PLATFORM_PROVIDERS = PLATFORM_BASE + "/providers";

  public static final String TENANT_PROVIDERS = TENANT_BASE + "/providers";

  public static final String PLATFORM_MODELS = PLATFORM_BASE + "/models";

  public static final String TENANT_MODELS = TENANT_BASE + "/models";

  public static final String PLATFORM_INFERENCE = PLATFORM_BASE + "/inference";

  public static final String TENANT_INFERENCE = TENANT_BASE + "/inference";

  public static final String PLATFORM_INVOCATIONS = PLATFORM_BASE + "/invocations";

  public static final String TENANT_INVOCATIONS = TENANT_BASE + "/invocations";

  public static final String PROVIDERS = PLATFORM_PROVIDERS;

  public static final String MODELS = PLATFORM_MODELS;

  private AiPaths() {
  }
}
