package org.simplepoint.plugin.ai.core.api.spi;

import java.util.List;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredModel;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ProviderConnection;

/**
 * SPI for vendor-specific model catalog discovery.
 */
public interface AiProviderAdapter {

  /**
   * Returns whether this adapter supports the provider protocol.
   *
   * @param providerType provider protocol
   * @return true when supported
   */
  boolean supports(AiProviderType providerType);

  /**
   * Fetches all models currently visible to the configured credential.
   *
   * @param connection runtime provider connection
   * @return discovered model catalog
   */
  List<DiscoveredModel> discoverModels(ProviderConnection connection);
}
