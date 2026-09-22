package org.simplepoint.plugin.ai.core.api.exception;

/** A client-safe protocol error, distinct from internal validation/provider failures. */
public class AiOpenAiRequestException extends IllegalArgumentException {
  private final int status;
  private final String code;
  private final String param;

  /** Creates an error whose message is safe to return to the API caller. */
  public AiOpenAiRequestException(final int status, final String code,
      final String param, final String message) {
    super(message);
    this.status = status;
    this.code = code;
    this.param = param;
  }

  /** HTTP status code. */
  public int status() {
    return status;
  }

  /** OpenAI-compatible error code. */
  public String code() {
    return code;
  }

  /** Invalid parameter, when known. */
  public String param() {
    return param;
  }
}
