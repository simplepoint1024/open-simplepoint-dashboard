package org.simplepoint.plugin.oidc.api.constants;

/** Dictionary codes used by external identity-provider management. */
public final class ExternalIdentityProviderDictionaryCodes {

  /** Provider presets. */
  public static final String PRESET = "oidc.external-provider.preset";

  /** Provider protocols. */
  public static final String PROTOCOL = "oidc.external-provider.protocol";

  /** OAuth2 client authentication methods. */
  public static final String CLIENT_AUTHENTICATION_METHOD =
      "oidc.external-provider.client-authentication-method";

  /** Local account matching strategies. */
  public static final String MATCH_STRATEGY = "oidc.external-provider.match-strategy";

  private ExternalIdentityProviderDictionaryCodes() {
  }
}
