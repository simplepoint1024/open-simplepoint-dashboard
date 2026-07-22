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

  /** Server-side pepper used to authenticate platform-issued model API keys. */
  private String apiKeyHashPepper;

  /** Default per-key request limit for the local compatibility gateway. */
  private Integer apiKeyDefaultRateLimitPerMinute = 60;

  private Integer connectTimeoutSeconds = 10;

  private Integer requestTimeoutSeconds = 30;

  private Integer modelSyncPageLimit = 1000;

  private Long modelSyncFixedDelayMs = 21_600_000L;

  private Long modelSyncInitialDelayMs = 60_000L;

  private Integer generationMaxInputCharacters = 1_000_000;

  private Integer generationMaxMessages = 200;

  private Integer generationMaxTools = 64;

  private Integer generationMaxOutputTokens = 32_768;

  private Long providerMaxResponseBytes = 10L * 1024L * 1024L;

  private Long providerMaxStreamBytes = 20L * 1024L * 1024L;

  private Integer providerMaxStreamLineCharacters = 1_048_576;

  private Integer inferenceCorePoolSize = 4;

  private Integer inferenceMaxPoolSize = 32;

  private Integer inferenceQueueCapacity = 200;

  private Long streamingTimeoutMs = 300_000L;

  /**
   * Whether organization tenants may maintain their own provider credentials.
   */
  private Boolean tenantProviderManagementEnabled = Boolean.TRUE;
}
