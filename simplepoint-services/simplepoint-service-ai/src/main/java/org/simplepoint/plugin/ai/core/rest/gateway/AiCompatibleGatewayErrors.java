package org.simplepoint.plugin.ai.core.rest.gateway;

/** Stable, non-sensitive messages exposed by the public compatibility gateway. */
public final class AiCompatibleGatewayErrors {

  public static final String AUTHENTICATION_MESSAGE =
      "模型 API Key 无效或缺失，请检查认证信息";

  public static final String PERMISSION_MESSAGE =
      "当前 API Key 无权访问所选模型";

  public static final String RATE_LIMIT_MESSAGE =
      "请求过于频繁，请稍后重试";

  public static final String INVALID_REQUEST_MESSAGE =
      "请求参数无效，请检查请求格式和必填字段";

  public static final String UPSTREAM_FAILURE_MESSAGE =
      "上游模型服务调用失败，请稍后重试";

  public static final String SERVICE_BUSY_MESSAGE =
      "模型服务繁忙，请稍后重试";

  public static final String INTERNAL_FAILURE_MESSAGE =
      "模型服务调用失败，请稍后重试";

  public static final String GENERATION_FAILURE_MESSAGE =
      "模型生成失败，请稍后重试";

  public static final String GENERATION_FAILURE_CODE = "generation_failed";

  private AiCompatibleGatewayErrors() {
  }
}
