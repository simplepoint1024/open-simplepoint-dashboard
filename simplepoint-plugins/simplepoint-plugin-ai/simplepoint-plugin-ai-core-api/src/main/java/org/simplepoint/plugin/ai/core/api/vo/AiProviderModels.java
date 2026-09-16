package org.simplepoint.plugin.ai.core.api.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.Instant;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderMessageCode;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderVendor;

/**
 * Value objects used by provider discovery and synchronization.
 */
public final class AiProviderModels {

  private AiProviderModels() {
  }

  /**
   * Runtime-only provider connection details.
   */
  public record ProviderConnection(
      String providerId,
      AiProviderType providerType,
      AiProviderVendor vendor,
      String baseUrl,
      String modelDiscoveryUrl,
      @JsonIgnore String apiKey,
      boolean allowPrivateNetwork,
      int requestTimeoutSeconds
  ) {
  }

  /**
   * Model metadata returned by a provider.
   */
  public record DiscoveredModel(
      String modelId,
      String displayName,
      AiModelType modelType,
      String ownedBy,
      Instant releasedAt,
      String metadataJson,
      DiscoveredPricing pricing
  ) {

    /** Backward-compatible constructor for adapters without pricing metadata. */
    public DiscoveredModel(
        final String modelId,
        final String displayName,
        final AiModelType modelType,
        final String ownedBy,
        final Instant releasedAt,
        final String metadataJson
    ) {
      this(modelId, displayName, modelType, ownedBy, releasedAt, metadataJson, null);
    }
  }

  /** Pricing normalized to per-million-token and per-request units. */
  public record DiscoveredPricing(
      String currency,
      BigDecimal inputTokenPrice,
      BigDecimal cachedInputTokenPrice,
      BigDecimal outputTokenPrice,
      BigDecimal requestPrice
  ) {

    /** Returns whether at least one usable price was discovered. */
    public boolean hasAnyPrice() {
      return inputTokenPrice != null || cachedInputTokenPrice != null
          || outputTokenPrice != null || requestPrice != null;
    }
  }

  /**
   * Provider connectivity test result.
   */
  public record ConnectionTestResult(
      String providerId,
      boolean success,
      int discoveredModelCount,
      Instant testedAt,
      AiProviderMessageCode messageCode
  ) {
  }

  /**
   * Persisted model synchronization summary.
   */
  public record ModelSyncResult(
      String providerId,
      int discovered,
      int created,
      int updated,
      int unavailable,
      Instant syncedAt
  ) {
  }
}
