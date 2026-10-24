package com.simplepoint.service.router.invocation;

/** Provides access tokens for service-router outbound calls. */
public interface ServiceRouterAccessTokenProvider {

  /**
   * Resolves an access token for the next outbound invocation.
   *
   * @return bearer token value
   */
  String getAccessToken();

  /**
   * Invalidates only the rejected cached credential; a concurrent refresh must be preserved.
   * Providers without a refreshable cache retain their normal behavior.
   *
   * @param rejectedToken credential rejected by the provider's authentication boundary
   */
  default void invalidate(final String rejectedToken) {
    // Static and externally managed credentials do not have a local cache to invalidate.
  }
}
