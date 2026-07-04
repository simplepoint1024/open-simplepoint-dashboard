package com.simplepoint.service.router.invocation;

/**
 * Provides access tokens for service-router outbound calls.
 */
public interface ServiceRouterAccessTokenProvider {

  /**
   * Resolves an access token for the next outbound invocation.
   *
   * @return bearer token value
   */
  String getAccessToken();
}
