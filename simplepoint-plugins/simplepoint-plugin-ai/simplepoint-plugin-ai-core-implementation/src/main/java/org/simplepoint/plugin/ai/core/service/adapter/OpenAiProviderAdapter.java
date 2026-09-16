package org.simplepoint.plugin.ai.core.service.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.ai.core.api.exception.AiProviderRequestException;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderVendor;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.spi.AiProviderAdapter;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredModel;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ProviderConnection;
import org.simplepoint.plugin.ai.core.service.support.AiDiscoveredPricingParser;
import org.simplepoint.plugin.ai.core.service.support.AiModelTypeDetector;
import org.springframework.stereotype.Component;

/** Catalog adapter for OpenAI-style and other common JSON model-list APIs. */
@Component
public class OpenAiProviderAdapter implements AiProviderAdapter {

  private static final int MAX_PAGES = 100;

  private static final int DASH_SCOPE_PAGE_SIZE = 100;

  private static final int MAX_RATE_LIMIT_RETRIES = 3;

  private final ObjectMapper objectMapper;

  private final AiModelTypeDetector typeDetector;

  private final AiDiscoveredPricingParser pricingParser;

  private final ProviderHttpSupport http;

  /** Creates the adapter. */
  public OpenAiProviderAdapter(
      final ObjectMapper objectMapper,
      final AiModelTypeDetector typeDetector,
      final AiDiscoveredPricingParser pricingParser,
      final AiProperties properties
  ) {
    this.objectMapper = objectMapper;
    this.typeDetector = typeDetector;
    this.pricingParser = pricingParser;
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
    URI endpoint = ProviderHttpSupport.discoveryEndpoint(
        connection.modelDiscoveryUrl(), connection.baseUrl(), "/models"
    );
    boolean dashScopeCatalog = isDashScopeCatalog(connection, endpoint);
    List<DiscoveredModel> result = new ArrayList<>();
    Set<String> pageTokens = new HashSet<>();
    String pageToken = null;
    int pageNumber = 1;
    for (int page = 0; page < MAX_PAGES; page++) {
      URI pageEndpoint = pageToken == null ? endpoint : ProviderHttpSupport.withQueryParameter(
              endpoint,
              paginationParameter(connection.vendor()),
              pageToken
          );
      if (dashScopeCatalog) {
        pageEndpoint = ProviderHttpSupport.withQueryParameter(
            pageEndpoint,
            "page_no",
            Integer.toString(pageNumber)
        );
        pageEndpoint = ProviderHttpSupport.withQueryParameter(
            pageEndpoint,
            "page_size",
            Integer.toString(DASH_SCOPE_PAGE_SIZE)
        );
      }
      JsonNode root = requestCatalog(connection, pageEndpoint);
      JsonNode models = modelArray(root);
      for (JsonNode item : models) {
        DiscoveredModel model = toModel(item, pageEndpoint, connection.vendor());
        if (model != null) {
          result.add(model);
        }
      }
      if (dashScopeCatalog) {
        JsonNode output = root.path("output");
        int currentPage = output.path("page_no").asInt(pageNumber);
        int pageSize = output.path("page_size").asInt(models.size());
        int total = output.path("total").asInt(result.size());
        if (models.isEmpty() || pageSize <= 0 || currentPage * pageSize >= total) {
          break;
        }
        pageNumber = currentPage + 1;
        pause(250L);
        continue;
      }
      pageToken = text(root, "nextPageToken", "next_page_token");
      if (pageToken == null || !pageTokens.add(pageToken)) {
        break;
      }
    }
    return result;
  }

  private static String paginationParameter(final AiProviderVendor vendor) {
    return vendor == AiProviderVendor.GOOGLE_GEMINI ? "pageToken" : "page_token";
  }

  private static boolean isDashScopeCatalog(
      final ProviderConnection connection,
      final URI endpoint
  ) {
    return connection.vendor() == AiProviderVendor.ALIBABA_QWEN
        && endpoint.getPath() != null
        && endpoint.getPath().endsWith("/api/v1/models");
  }

  private JsonNode requestCatalog(
      final ProviderConnection connection,
      final URI endpoint
  ) {
    HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(endpoint)
        .timeout(ProviderHttpSupport.timeout(connection.requestTimeoutSeconds()))
        .header("Accept", "application/json")
        .GET();
    addAuthentication(request, connection);
    String responseBody = sendCatalogRequest(connection, request.build());
    try {
      return objectMapper.readTree(responseBody);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("无法解析供应商模型列表响应", ex);
    }
  }

  private String sendCatalogRequest(
      final ProviderConnection connection,
      final HttpRequest request
  ) {
    for (int attempt = 0; ; attempt++) {
      try {
        return http.send(request, connection.allowPrivateNetwork());
      } catch (AiProviderRequestException ex) {
        if (ex.getProviderStatus() != 429 || attempt >= MAX_RATE_LIMIT_RETRIES) {
          throw ex;
        }
        pause(1000L << attempt);
      }
    }
  }

  private static void pause(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待供应商限流恢复时线程被中断", ex);
    }
  }

  private DiscoveredModel toModel(
      final JsonNode item,
      final URI discoveryUri,
      final AiProviderVendor vendor
  ) {
    String modelId = text(item, "id", "model", "modelId", "model_id", "baseModelId", "name");
    if (modelId == null) {
      return null;
    }
    modelId = stripModelsPrefix(modelId);
    String displayName = text(item, "display_name", "displayName", "title");
    if (displayName == null && item.has("model")) {
      displayName = text(item, "name");
    }
    String ownedBy = text(
        item,
        "owned_by",
        "ownedBy",
        "provider",
        "providerName",
        "inference_provider"
    );
    try {
      return new DiscoveredModel(
          modelId,
          displayName == null ? modelId : displayName,
          typeDetector.detect(item, discoveryUri),
          ownedBy,
          releasedAt(item),
          objectMapper.writeValueAsString(item),
          pricingParser.parse(item, vendor)
      );
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("无法序列化供应商模型元数据", ex);
    }
  }

  private static JsonNode modelArray(final JsonNode root) {
    for (String field : new String[] {"data", "models", "value", "modelSummaries"}) {
      JsonNode candidate = root.path(field);
      if (candidate.isArray()) {
        return candidate;
      }
    }
    JsonNode nestedModels = root.path("output").path("models");
    if (nestedModels.isArray()) {
      return nestedModels;
    }
    if (root.isArray()) {
      return root;
    }
    throw new IllegalStateException(
        "供应商模型列表响应缺少 data、models、value、modelSummaries 或 output.models 数组"
    );
  }

  private static void addAuthentication(
      final HttpRequest.Builder request,
      final ProviderConnection connection
  ) {
    String apiKey = connection.apiKey();
    if (apiKey == null || apiKey.isBlank()) {
      return;
    }
    AiProviderVendor vendor = connection.vendor();
    if (vendor == AiProviderVendor.GOOGLE_GEMINI) {
      request.header("x-goog-api-key", apiKey);
    } else if (vendor == AiProviderVendor.AZURE_OPENAI) {
      request.header("api-key", apiKey);
    } else {
      request.header("Authorization", "Bearer " + apiKey);
    }
  }

  private static Instant releasedAt(final JsonNode item) {
    JsonNode created = item.get("created");
    if (created != null && created.canConvertToLong() && created.asLong() > 0) {
      return Instant.ofEpochSecond(created.asLong());
    }
    String value = text(item, "created_at", "createdAt", "released_at", "releaseDate");
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private static String text(final JsonNode node, final String... fields) {
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (value != null && value.isValueNode() && !value.asText().isBlank()) {
        return value.asText().trim();
      }
    }
    return null;
  }

  private static String stripModelsPrefix(final String value) {
    return value.startsWith("models/") ? value.substring("models/".length()) : value;
  }

  private static void requireApiKey(final String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("供应商未配置 API Key");
    }
  }

}
