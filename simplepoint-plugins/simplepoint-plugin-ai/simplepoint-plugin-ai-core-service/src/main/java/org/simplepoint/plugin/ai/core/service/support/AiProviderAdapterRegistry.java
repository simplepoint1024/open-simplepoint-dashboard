package org.simplepoint.plugin.ai.core.service.support;

import java.util.List;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.spi.AiProviderAdapter;
import org.springframework.stereotype.Component;

/**
 * Resolves the provider adapter for a configured protocol.
 */
@Component
public class AiProviderAdapterRegistry {

  private final List<AiProviderAdapter> adapters;

  /**
   * Creates the registry.
   *
   * @param adapters provider adapters discovered by Spring
   */
  public AiProviderAdapterRegistry(final List<AiProviderAdapter> adapters) {
    this.adapters = List.copyOf(adapters);
  }

  /**
   * Resolves a provider adapter.
   *
   * @param providerType provider protocol
   * @return matching adapter
   */
  public AiProviderAdapter require(final AiProviderType providerType) {
    return adapters.stream()
        .filter(adapter -> adapter.supports(providerType))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("不支持的 AI 供应商协议: " + providerType));
  }
}
