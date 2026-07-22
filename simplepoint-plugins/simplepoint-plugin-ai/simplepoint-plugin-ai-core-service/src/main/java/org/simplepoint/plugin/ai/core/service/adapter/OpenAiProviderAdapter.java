package org.simplepoint.plugin.ai.core.service.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.spi.AiProviderAdapter;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredModel;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ProviderConnection;
import org.simplepoint.plugin.ai.core.service.support.AiModelTypeDetector;
import org.springframework.stereotype.Component;

/**
 * Model catalog adapter for OpenAI and OpenAI-compatible APIs.
 */
@Component
public class OpenAiProviderAdapter implements AiProviderAdapter {

  private final ObjectMapper objectMapper;

  private final AiModelTypeDetector typeDetector;

  private final ProviderHttpSupport http;

  /**
   * Creates the adapter.
   *
   * @param objectMapper JSON mapper
   * @param typeDetector model type detector
   * @param properties   integration properties
   */
  public OpenAiProviderAdapter(
      final ObjectMapper objectMapper,
      final AiModelTypeDetector typeDetector,
      final AiProperties properties
  ) {
    this.objectMapper = objectMapper;
    this.typeDetector = typeDetector;
    this.http = new ProviderHttpSupport(properties);
  }

  /** {@inheritDoc} */
  @Override
  public boolean supports(final AiProviderType providerType) {
    return providerType == AiProviderType.OPENAI
        || providerType == AiProviderType.OPENAI_COMPATIBLE;
  }

  /** {@inheritDoc} */
  @Override
  public List<DiscoveredModel> discoverModels(final ProviderConnection connection) {
    if (connection.providerType() == AiProviderType.OPENAI) {
      requireApiKey(connection.apiKey());
    }
    HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(ProviderHttpSupport.endpoint(connection.baseUrl(), "/models"))
        .timeout(ProviderHttpSupport.timeout(connection.requestTimeoutSeconds()))
        .header("Accept", "application/json")
        .GET();
    if (connection.apiKey() != null && !connection.apiKey().isBlank()) {
      request.header("Authorization", "Bearer " + connection.apiKey());
    }
    addHeader(request, "OpenAI-Organization", connection.organizationId());
    addHeader(request, "OpenAI-Project", connection.projectId());
    String responseBody = http.send(request.build(), connection.allowPrivateNetwork());
    try {
      JsonNode root = objectMapper.readTree(responseBody);
      JsonNode data = root.path("data");
      if (!data.isArray()) {
        throw new IllegalStateException("供应商模型列表响应缺少 data 数组");
      }
      List<DiscoveredModel> models = new ArrayList<>();
      for (JsonNode item : data) {
        String modelId = item.path("id").asText(null);
        if (modelId == null || modelId.isBlank()) {
          continue;
        }
        long created = item.path("created").asLong(0);
        models.add(new DiscoveredModel(
            modelId,
            modelId,
            typeDetector.detect(modelId),
            item.path("owned_by").asText(null),
            created > 0 ? Instant.ofEpochSecond(created) : null,
            objectMapper.writeValueAsString(item)
        ));
      }
      return models;
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("无法解析供应商模型列表响应", ex);
    }
  }

  private static void requireApiKey(final String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("供应商未配置 API Key");
    }
  }

  private static void addHeader(
      final HttpRequest.Builder request,
      final String name,
      final String value
  ) {
    if (value != null && !value.isBlank()) {
      request.header(name, value.trim());
    }
  }
}
