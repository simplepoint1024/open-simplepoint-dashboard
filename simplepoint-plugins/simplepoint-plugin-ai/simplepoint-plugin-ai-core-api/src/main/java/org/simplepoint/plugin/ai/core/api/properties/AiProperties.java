package org.simplepoint.plugin.ai.core.api.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI provider integration properties.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = AiProperties.PREFIX)
public class AiProperties {

  public static final String PREFIX = "simplepoint.ai";

  private String credentialEncryptionKey;

  private Integer connectTimeoutSeconds = 10;

  private Integer requestTimeoutSeconds = 30;

  private Integer modelSyncPageLimit = 1000;

  private Long modelSyncFixedDelayMs = 21_600_000L;

  private Long modelSyncInitialDelayMs = 60_000L;

  /**
   * Whether organization tenants may maintain their own provider credentials.
   */
  private Boolean tenantProviderManagementEnabled = Boolean.TRUE;
}
