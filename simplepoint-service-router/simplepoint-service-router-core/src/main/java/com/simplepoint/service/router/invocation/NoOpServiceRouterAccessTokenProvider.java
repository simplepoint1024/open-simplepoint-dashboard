package com.simplepoint.service.router.invocation;

/**
 * Token provider used when OAuth2 service authentication is disabled.
 */
public class NoOpServiceRouterAccessTokenProvider implements ServiceRouterAccessTokenProvider {

  @Override
  public String getAccessToken() {
    return null;
  }
}
