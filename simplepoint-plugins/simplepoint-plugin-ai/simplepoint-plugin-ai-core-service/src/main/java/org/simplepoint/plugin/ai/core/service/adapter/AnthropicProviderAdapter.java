package org.simplepoint.plugin.ai.core.service.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.spi.AiProviderAdapter;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredModel;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ProviderConnection;
import org.springframework.stereotype.Component;

/**
 * Model catalog adapter for the Anthropic Models API.
 */
@Component
public class AnthropicProviderAdapter implements AiProviderAdapter {

  private static final String DEFAULT_API_VERSION = "2023-06-01";

  private final ObjectMapper objectMapper;

  private final ProviderHttpSupport http;

  private final int pageLimit;

  /**
   * Creates the adapter.
   *
   * @param objectMapper JSON mapper
   * @param properties   integration properties
   */
  public AnthropicProviderAdapter(
      final ObjectMapper objectMapper,
      final AiProperties properties
  ) {
    this.objectMapper = objectMapper;
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
      HttpRequest request = HttpRequest.newBuilder()
          .uri(ProviderHttpSupport.endpoint(connection.baseUrl(), query))
          .timeout(ProviderHttpSupport.timeout(connection.requestTimeoutSeconds()))
          .header("x-api-key", connection.apiKey())
          .header("anthropic-version", version(connection.apiVersion()))
          .header("Accept", "application/json")
          .GET()
          .build();
      HttpResponse<String> response = http.send(request);
      try {
        JsonNode root = objectMapper.readTree(response.body());
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
              AiModelType.LLM,
              "anthropic",
              parseInstant(item.path("created_at").asText(null)),
              objectMapper.writeValueAsString(item)
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

  private static String version(final String configured) {
    return configured == null || configured.isBlank() ? DEFAULT_API_VERSION : configured.trim();
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
