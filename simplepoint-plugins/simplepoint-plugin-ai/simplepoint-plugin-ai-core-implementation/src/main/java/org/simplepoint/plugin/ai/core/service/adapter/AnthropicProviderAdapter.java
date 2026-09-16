package org.simplepoint.plugin.ai.core.service.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.spi.AiProviderAdapter;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredModel;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ProviderConnection;
import org.simplepoint.plugin.ai.core.service.support.AiDiscoveredPricingParser;
import org.simplepoint.plugin.ai.core.service.support.AiModelTypeDetector;
import org.springframework.stereotype.Component;

/**
 * Model catalog adapter for the Anthropic Models API.
 */
@Component
public class AnthropicProviderAdapter implements AiProviderAdapter {

  private static final String DEFAULT_API_VERSION = "2023-06-01";

  private final ObjectMapper objectMapper;

  private final ProviderHttpSupport http;

  private final AiModelTypeDetector typeDetector;

  private final AiDiscoveredPricingParser pricingParser;

  private final int pageLimit;

  /**
   * Creates the adapter.
   *
   * @param objectMapper JSON mapper
   * @param properties   integration properties
   */
  public AnthropicProviderAdapter(
      final ObjectMapper objectMapper,
      final AiModelTypeDetector typeDetector,
      final AiDiscoveredPricingParser pricingParser,
      final AiProperties properties
  ) {
    this.objectMapper = objectMapper;
    this.typeDetector = typeDetector;
    this.pricingParser = pricingParser;
    this.http = new ProviderHttpSupport(properties);
    this.pageLimit = Math.min(1000, ProviderHttpSupport.positive(
        properties.getModelSyncPageLimit(),
        1000
    ));
  }

  /** {@inheritDoc} */
  @Override
  public boolean supports(final AiProviderType providerType) {
    return providerType == AiProviderType.ANTHROPIC;
  }

  /** {@inheritDoc} */
  @Override
  public List<DiscoveredModel> discoverModels(final ProviderConnection connection) {
    if (connection.apiKey() == null || connection.apiKey().isBlank()) {
      throw new IllegalStateException("供应商未配置 API Key");
    }
    List<DiscoveredModel> result = new ArrayList<>();
    String afterId = null;
    boolean hasMore;
    do {
      String query = "/models?limit=" + pageLimit;
      if (afterId != null) {
        query += "&after_id=" + URLEncoder.encode(afterId, StandardCharsets.UTF_8);
      }
      java.net.URI discoveryUri = connection.modelDiscoveryUrl() == null
          || connection.modelDiscoveryUrl().isBlank()
          ? ProviderHttpSupport.endpoint(connection.baseUrl(), query)
          : ProviderHttpSupport.discoveryEndpoint(
              connection.modelDiscoveryUrl(), connection.baseUrl(), query
          );
      if (afterId != null && connection.modelDiscoveryUrl() != null
          && !connection.modelDiscoveryUrl().isBlank()) {
        discoveryUri = ProviderHttpSupport.withQueryParameter(discoveryUri, "after_id", afterId);
      }
      HttpRequest request = HttpRequest.newBuilder()
          .uri(discoveryUri)
          .timeout(ProviderHttpSupport.timeout(connection.requestTimeoutSeconds()))
          .header("x-api-key", connection.apiKey())
          .header("anthropic-version", DEFAULT_API_VERSION)
          .header("Accept", "application/json")
          .GET()
          .build();
      String responseBody = http.send(request, connection.allowPrivateNetwork());
      try {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
          throw new IllegalStateException("Anthropic 模型列表响应缺少 data 数组");
        }
        for (JsonNode item : data) {
          String modelId = item.path("id").asText(null);
          if (modelId == null || modelId.isBlank()) {
            continue;
          }
          result.add(new DiscoveredModel(
              modelId,
              item.path("display_name").asText(modelId),
              typeDetector.detect(item, discoveryUri),
              "anthropic",
              parseInstant(item.path("created_at").asText(null)),
              objectMapper.writeValueAsString(item),
              pricingParser.parse(item)
          ));
        }
        hasMore = root.path("has_more").asBoolean(false);
        afterId = root.path("last_id").asText(null);
        if (hasMore && (afterId == null || afterId.isBlank())) {
          throw new IllegalStateException("Anthropic 分页响应缺少 last_id");
        }
      } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
        throw new IllegalStateException("无法解析 Anthropic 模型列表响应", ex);
      }
    } while (hasMore);
    return result;
  }

  private static Instant parseInstant(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }
}
