package org.simplepoint.plugin.ai.core.api.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;

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
      String baseUrl,
      @JsonIgnore String apiKey,
      String organizationId,
      String projectId,
      String apiVersion,
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
      String metadataJson
  ) {
  }

  /**
   * Provider connectivity test result.
   */
  public record ConnectionTestResult(
      String providerId,
      boolean success,
      int discoveredModelCount,
      Instant testedAt,
      String message
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
