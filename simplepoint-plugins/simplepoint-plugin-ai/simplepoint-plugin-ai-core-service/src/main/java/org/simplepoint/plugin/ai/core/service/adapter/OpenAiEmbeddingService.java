package org.simplepoint.plugin.ai.core.service.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiProviderDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.service.AiEmbeddingService;
import org.simplepoint.plugin.ai.core.api.vo.AiEmbeddingResult;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.springframework.stereotype.Service;

/**
 * Embedding invocation through the OpenAI embeddings protocol.
 */
@Service
public class OpenAiEmbeddingService implements AiEmbeddingService {

  private static final int MAX_BATCH_SIZE = 128;

  private final ObjectMapper objectMapper;

  private final AiModelDefinitionRepository modelRepository;

  private final AiProviderDefinitionRepository providerRepository;

  private final AiCredentialCipher credentialCipher;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiProperties properties;

  private final ProviderHttpSupport http;

  /**
   * Creates the embedding service.
   */
  public OpenAiEmbeddingService(
      final ObjectMapper objectMapper,
      final AiModelDefinitionRepository modelRepository,
      final AiProviderDefinitionRepository providerRepository,
      final AiCredentialCipher credentialCipher,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiProperties properties
  ) {
    this.objectMapper = objectMapper;
    this.modelRepository = modelRepository;
    this.providerRepository = providerRepository;
    this.credentialCipher = credentialCipher;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.properties = properties;
    this.http = new ProviderHttpSupport(properties);
  }

  /** {@inheritDoc} */
  @Override
  public AiEmbeddingResult embed(
      final String modelDefinitionId,
      final List<String> inputs,
      final Integer dimensions
  ) {
    String localModelId = requireValue(modelDefinitionId, "Embedding 模型不能为空");
    if (inputs == null || inputs.isEmpty()) {
      throw new IllegalArgumentException("Embedding 输入不能为空");
    }
    if (inputs.size() > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException("单次 Embedding 最多支持 " + MAX_BATCH_SIZE + " 条输入");
    }
    final List<String> normalizedInputs = inputs.stream()
        .map(input -> requireValue(input, "Embedding 输入不能包含空文本"))
        .toList();
    AiModelDefinition model = modelRepository.findActiveById(localModelId)
        .orElseThrow(() -> new IllegalArgumentException("Embedding 模型不存在: " + localModelId));
    if (!scopeAccessPolicy.canUseResource(model.getScopeType(), model.getTenantId())) {
      throw new IllegalArgumentException("Embedding 模型不存在或当前作用域不可用");
    }
    if (model.getModelType() != AiModelType.EMBEDDING
        || !Boolean.TRUE.equals(model.getEnabled())
        || !Boolean.TRUE.equals(model.getAvailable())) {
      throw new IllegalArgumentException("所选模型不是可用的 Embedding 模型");
    }
    AiProviderDefinition provider = providerRepository.findActiveById(model.getProviderId())
        .orElseThrow(() -> new IllegalArgumentException("Embedding 模型供应商不存在"));
    if (!Boolean.TRUE.equals(provider.getEnabled())) {
      throw new IllegalArgumentException("Embedding 模型供应商未启用");
    }
    if (provider.getProviderType() == AiProviderType.ANTHROPIC) {
      throw new IllegalArgumentException("Anthropic 标准接口暂不提供 Embedding 模型");
    }
    if (dimensions != null && dimensions <= 0) {
      throw new IllegalArgumentException("Embedding 维度必须大于 0");
    }
    return invoke(provider, model, normalizedInputs, dimensions);
  }

  private AiEmbeddingResult invoke(
      final AiProviderDefinition provider,
      final AiModelDefinition model,
      final List<String> inputs,
      final Integer dimensions
  ) {
    String apiKey = credentialCipher.decrypt(provider.getCredentialCiphertext());
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("供应商未配置 API Key");
    }
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", model.getModelId());
    ArrayNode inputNode = body.putArray("input");
    inputs.forEach(inputNode::add);
    if (dimensions != null) {
      body.put("dimensions", dimensions);
    }
    HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(ProviderHttpSupport.endpoint(provider.getBaseUrl(), "/embeddings"))
        .timeout(ProviderHttpSupport.timeout(
            ProviderHttpSupport.positive(properties.getRequestTimeoutSeconds(), 30)
        ))
        .header("Authorization", "Bearer " + apiKey)
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    addHeader(request, "OpenAI-Organization", provider.getOrganizationId());
    addHeader(request, "OpenAI-Project", provider.getProjectId());
    HttpResponse<String> response = http.send(request.build());
    return parseResponse(model.getModelId(), inputs.size(), response.body());
  }

  private AiEmbeddingResult parseResponse(
      final String modelId,
      final int inputSize,
      final String responseBody
  ) {
    try {
      JsonNode data = objectMapper.readTree(responseBody).path("data");
      if (!data.isArray() || data.size() != inputSize) {
        throw new IllegalStateException("供应商 Embedding 响应数量与输入不一致");
      }
      List<IndexedVector> indexed = new ArrayList<>();
      for (int position = 0; position < data.size(); position++) {
        JsonNode item = data.get(position);
        JsonNode embedding = item.path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
          throw new IllegalStateException("供应商 Embedding 响应缺少向量");
        }
        List<Double> vector = new ArrayList<>(embedding.size());
        embedding.forEach(value -> vector.add(value.asDouble()));
        indexed.add(new IndexedVector(item.path("index").asInt(position), vector));
      }
      indexed.sort(Comparator.comparingInt(IndexedVector::index));
      List<List<Double>> vectors = indexed.stream().map(IndexedVector::vector).toList();
      int actualDimensions = vectors.getFirst().size();
      if (vectors.stream().anyMatch(vector -> vector.size() != actualDimensions)) {
        throw new IllegalStateException("供应商返回的 Embedding 向量维度不一致");
      }
      return new AiEmbeddingResult(modelId, actualDimensions, vectors);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("无法解析供应商 Embedding 响应", ex);
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

  private static String requireValue(final String value, final String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }

  private record IndexedVector(int index, List<Double> vector) {
  }
}
