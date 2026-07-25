package org.simplepoint.plugin.oidc.api.model;

/** Strategy used when an external identity is linked for the first time. */
public enum ExternalIdentityMatchStrategy {
  VERIFIED_EMAIL,
  USER_ID
}
