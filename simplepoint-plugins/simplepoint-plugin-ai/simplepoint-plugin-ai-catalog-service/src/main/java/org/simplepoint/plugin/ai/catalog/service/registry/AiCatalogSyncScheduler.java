package org.simplepoint.plugin.ai.catalog.service.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional background synchronization. Manual synchronization remains available.
 */
@Slf4j
@Component
public class AiCatalogSyncScheduler {

  private final AiCatalogProperties properties;

  private final OfficialMcpRegistrySync registrySync;

  /**
   * Creates the scheduler.
   */
  public AiCatalogSyncScheduler(
      final AiCatalogProperties properties,
      final OfficialMcpRegistrySync registrySync
  ) {
    this.properties = properties;
    this.registrySync = registrySync;
  }

  /**
   * Synchronizes only when explicitly enabled by an operator.
   */
  @Scheduled(cron = "${simplepoint.ai.catalog.scheduled-sync-cron:0 15 * * * *}")
  public void synchronize() {
    if (!Boolean.TRUE.equals(properties.getOfficialSyncEnabled())
        || !Boolean.TRUE.equals(properties.getScheduledSyncEnabled())) {
      return;
    }
    try {
      var result = registrySync.synchronize();
      if (result.error() != null) {
        log.warn("Official MCP Registry synchronization: {}", result.error());
      }
    } catch (RuntimeException ex) {
      log.error("Official MCP Registry synchronization failed", ex);
    }
  }
}
