package com.simplepoint.service.router.invocation;

/**
 * Service router remote invocation response.
 *
 * @param success whether invocation succeeded
 * @param data response payload
 * @param errorCode stable error code
 * @param message error message
 */
public record RemoteResponse(
    boolean success,
    Object data,
    String errorCode,
    String message
) {

  /**
   * Creates a successful response.
   *
   * @param data response payload
   * @return response
   */
  public static RemoteResponse success(final Object data) {
    return new RemoteResponse(true, data, null, null);
  }

  /**
   * Creates a failed response.
   *
   * @param errorCode stable error code
   * @param message error message
   * @return response
   */
  public static RemoteResponse failure(final String errorCode, final String message) {
    return new RemoteResponse(false, null, errorCode, message);
  }
}
