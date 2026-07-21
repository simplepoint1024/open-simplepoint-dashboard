package org.simplepoint.plugin.ai.core.api.service;

import java.util.List;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ConnectionTestResult;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredModel;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ModelSyncResult;

/**
 * Discovers and synchronizes remote provider model catalogs.
 */
public interface AiModelCatalogService {

  /**
   * Tests provider connectivity by loading its model catalog.
   *
   * @param providerId provider id
   * @return connection result
   */
  ConnectionTestResult testConnection(String providerId);

  /**
   * Discovers remote models without persisting them.
   *
   * @param providerId provider id
   * @return remote models
   */
  List<DiscoveredModel> discoverModels(String providerId);

  /**
   * Synchronizes remote models into the local catalog.
   *
   * @param providerId provider id
   * @return synchronization summary
   */
  ModelSyncResult syncModels(String providerId);

  /**
   * Synchronizes every enabled provider that opted into automatic synchronization.
   */
  void syncAutoEnabledProviders();
}
