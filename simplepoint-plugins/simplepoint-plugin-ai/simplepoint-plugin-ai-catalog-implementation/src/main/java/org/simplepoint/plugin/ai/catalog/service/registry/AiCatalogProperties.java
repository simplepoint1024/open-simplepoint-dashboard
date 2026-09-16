package org.simplepoint.plugin.ai.catalog.service.registry;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Bounded and opt-in official MCP Registry synchronization settings.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = AiCatalogProperties.PREFIX)
public class AiCatalogProperties {

  public static final String PREFIX = "simplepoint.ai.catalog";

  private String officialRegistryUrl = "https://registry.modelcontextprotocol.io";

  private Boolean officialSyncEnabled = false;

  private Boolean scheduledSyncEnabled = false;

  private String scheduledSyncCron = "0 15 * * * *";

  private Duration connectTimeout = Duration.ofSeconds(5);

  private Duration requestTimeout = Duration.ofSeconds(20);

  private Integer pageSize = 100;

  private Integer maximumPagesPerRun = 20;

  private Integer maximumResponseBytes = 2 * 1024 * 1024;

  private Boolean allowPrivateRegistry = false;
}
