package org.simplepoint.plugin.ai.core.service.schedule;

import org.simplepoint.plugin.ai.core.api.service.AiModelCatalogService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically synchronizes providers that opted into automatic discovery.
 */
@Component
public class AiModelCatalogSyncScheduler {

  private final AiModelCatalogService modelCatalogService;

  /**
   * Creates the scheduler.
   *
   * @param modelCatalogService model catalog service
   */
  public AiModelCatalogSyncScheduler(final AiModelCatalogService modelCatalogService) {
    this.modelCatalogService = modelCatalogService;
  }

  /**
   * Runs automatic model catalog synchronization.
   */
  @Scheduled(
      fixedDelayString = "${simplepoint.ai.model-sync-fixed-delay-ms:21600000}",
      initialDelayString = "${simplepoint.ai.model-sync-initial-delay-ms:60000}"
  )
  public void synchronize() {
    modelCatalogService.syncAutoEnabledProviders();
  }
}
