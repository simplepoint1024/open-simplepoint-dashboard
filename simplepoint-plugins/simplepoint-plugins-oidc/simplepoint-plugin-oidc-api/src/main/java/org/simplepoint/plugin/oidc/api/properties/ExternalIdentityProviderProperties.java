package org.simplepoint.plugin.oidc.api.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Security properties for external identity-provider credentials and network access. */
@Data
@Configuration
@ConfigurationProperties(prefix = ExternalIdentityProviderProperties.PREFIX)
public class ExternalIdentityProviderProperties {

  /** Configuration prefix. */
  public static final String PREFIX = "simplepoint.security.oauth2.external-provider";

  /** AES-GCM master key used to encrypt provider client secrets. */
  private String credentialEncryptionKey;
}
