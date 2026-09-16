package org.simplepoint.plugin.ai.core.service.adapter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiInvocationOperation;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiProviderDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.EventType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationEvent;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.Message;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ToolDefinition;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.springframework.stereotype.Service;

/** Resolves an accessible model and delegates generation to its provider protocol adapter. */
@Service
public class ProviderNeutralGenerationService implements AiGenerationService {

  private final AiModelDefinitionRepository modelRepository;

  private final AiProviderDefinitionRepository providerRepository;

  private final AiCredentialCipher credentialCipher;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  private final AiGenerationAdapterRegistry adapterRegistry;

  private final AiProperties properties;

  private final AiInvocationLedger invocationLedger;

  /** Creates the provider-neutral generation service. */
  public ProviderNeutralGenerationService(
      final AiModelDefinitionRepository modelRepository,
      final AiProviderDefinitionRepository providerRepository,
      final AiCredentialCipher credentialCipher,
      final AiScopeAccessPolicy scopeAccessPolicy,
      final AiGenerationAdapterRegistry adapterRegistry,
      final AiProperties properties,
      final AiInvocationLedger invocationLedger
  ) {
    this.modelRepository = modelRepository;
    this.providerRepository = providerRepository;
    this.credentialCipher = credentialCipher;
    this.scopeAccessPolicy = scopeAccessPolicy;
    this.adapterRegistry = adapterRegistry;
    this.properties = properties;
    this.invocationLedger = invocationLedger;
  }

  @Override
  public GenerationResult generate(final GenerationRequest request) {
    PreparedInvocation prepared = prepare(request);
    var record = invocationLedger.start(
        prepared.actor(), prepared.invocation().invocationId(), prepared.invocation().provider(),
        prepared.invocation().model(), AiInvocationOperation.GENERATION, false
    );
    try {
      GenerationResult result = prepared.adapter().generate(prepared.invocation());
      invocationLedger.success(record, result);
      return result;
    } catch (RuntimeException ex) {
      invocationLedger.failure(record, ex);
      throw ex;
    }
  }

  @Override
  public GenerationStream prepareStream(final GenerationRequest request) {
    PreparedInvocation prepared = prepare(request);
    AtomicBoolean consumed = new AtomicBoolean();
    AiStreamCancellation cancellation = new AiStreamCancellation();
    return new GenerationStream() {
      @Override
      public void consume(final Consumer<GenerationEvent> consumer) {
        if (!consumed.compareAndSet(false, true)) {
          throw new IllegalStateException("同一个 AI 流式调用只能消费一次");
        }
        cancellation.throwIfCancelled();
        consumeStream(prepared, consumer, cancellation);
      }

      @Override
      public void cancel() {
        cancellation.cancel();
      }
    };
  }

  private void consumeStream(
      final PreparedInvocation prepared,
      final Consumer<GenerationEvent> consumer,
      final AiStreamCancellation cancellation
  ) {
    if (consumer == null) {
      throw new IllegalArgumentException("流式事件消费者不能为空");
    }
    var record = invocationLedger.start(
        prepared.actor(), prepared.invocation().invocationId(),
        prepared.invocation().provider(), prepared.invocation().model(),
        AiInvocationOperation.GENERATION, true
    );
    AtomicLong lastSequence = new AtomicLong();
    try {
      AtomicBoolean completed = new AtomicBoolean();
      prepared.adapter().stream(prepared.invocation(), event -> {
        lastSequence.accumulateAndGet(event.sequence(), Math::max);
        consumer.accept(event);
        if (event.type() == EventType.COMPLETED && event.result() != null) {
          completed.set(true);
          invocationLedger.success(record, event.result());
        }
      }, cancellation);
      if (!completed.get()) {
        throw new IllegalStateException("供应商流式响应未正常完成");
      }
    } catch (CancellationException ex) {
      invocationLedger.cancelled(record);
      throw ex;
    } catch (RuntimeException ex) {
      invocationLedger.failure(record, ex);
      consumer.accept(new GenerationEvent(
          prepared.invocation().invocationId(), lastSequence.incrementAndGet(), EventType.ERROR,
          null, null, null, null, null, null,
          "GENERATION_FAILED", safeMessage(ex)
      ));
      throw ex;
    }
  }

  private PreparedInvocation prepare(final GenerationRequest request) {
    validate(request);
    AiModelDefinition model = modelRepository.findActiveById(request.modelDefinitionId().trim())
        .orElseThrow(() -> new IllegalArgumentException("AI 模型不存在"));
    if (!scopeAccessPolicy.canUseResource(model.getScopeType(), model.getTenantId())) {
      throw new IllegalArgumentException("AI 模型不存在或当前作用域不可用");
    }
    if ((model.getModelType() != AiModelType.LLM
        && model.getModelType() != AiModelType.MULTIMODAL)
        || !Boolean.TRUE.equals(model.getEnabled())
        || !Boolean.TRUE.equals(model.getAvailable())) {
      throw new IllegalArgumentException("所选模型不是可用的生成模型");
    }
    AiProviderDefinition provider = providerRepository.findActiveById(model.getProviderId())
        .orElseThrow(() -> new IllegalArgumentException("AI 模型供应商不存在"));
    if (!scopeAccessPolicy.canUseResource(provider.getScopeType(), provider.getTenantId())
        || !Boolean.TRUE.equals(provider.getEnabled())) {
      throw new IllegalArgumentException("AI 模型供应商不可用");
    }
    if (provider.getProviderType() == AiProviderType.ANTHROPIC
        && request.temperature() != null && request.temperature() > 1.0D) {
      throw new IllegalArgumentException("Anthropic temperature 必须在 0 到 1 之间");
    }
    String apiKey = credentialCipher.decrypt(provider.getCredentialCiphertext());
    if (!notBlank(apiKey) && provider.getProviderType() != AiProviderType.OPENAI_COMPATIBLE) {
      throw new IllegalStateException("供应商未配置 API Key");
    }
    int timeout = ProviderHttpSupport.positive(properties.getRequestTimeoutSeconds(), 30);
    AiRuntimeInvocation invocation = new AiRuntimeInvocation(
        UUID.randomUUID().toString(), provider, model, request, apiKey, timeout
    );
    return new PreparedInvocation(
        adapterRegistry.require(provider),
        invocation,
        invocationLedger.captureActor()
    );
  }

  private void validate(final GenerationRequest request) {
    if (request == null || !notBlank(request.modelDefinitionId())) {
      throw new IllegalArgumentException("生成模型不能为空");
    }
    if (!notBlank(request.instructions())
        && (request.messages() == null || request.messages().isEmpty())) {
      throw new IllegalArgumentException("生成消息不能为空");
    }
    List<Message> messages = request.messages() == null ? List.of() : request.messages();
    int maxMessages = positive(properties.getGenerationMaxMessages(), 200);
    if (messages.size() > maxMessages) {
      throw new IllegalArgumentException("单次生成最多支持 " + maxMessages + " 条消息");
    }
    List<ToolDefinition> tools = request.tools() == null ? List.of() : request.tools();
    int maxTools = positive(properties.getGenerationMaxTools(), 64);
    if (tools.size() > maxTools) {
      throw new IllegalArgumentException("单次生成最多支持 " + maxTools + " 个工具");
    }
    Set<String> toolNames = new HashSet<>();
    tools.forEach(tool -> {
      if (tool == null || !notBlank(tool.name()) || !notBlank(tool.inputSchemaJson())) {
        throw new IllegalArgumentException("工具名称和输入 Schema 不能为空");
      }
      if (!tool.name().matches("[A-Za-z0-9_-]{1,64}")) {
        throw new IllegalArgumentException("工具名称仅支持字母、数字、下划线和连字符，最长 64 个字符");
      }
      if (!toolNames.add(tool.name())) {
        throw new IllegalArgumentException("工具名称不能重复: " + tool.name());
      }
    });
    int maxCharacters = positive(properties.getGenerationMaxInputCharacters(), 1_000_000);
    long characters = length(request.instructions());
    int contentBlocks = 0;
    for (Message message : messages) {
      if (message == null || message.role() == null || message.content() == null) {
        throw new IllegalArgumentException("消息角色和内容不能为空");
      }
      for (ContentBlock block : message.content()) {
        if (block == null || block.type() == null) {
          throw new IllegalArgumentException("消息内容类型不能为空");
        }
        contentBlocks++;
        if ((block.type() == ContentType.TEXT || block.type() == ContentType.REFUSAL)
            && !notBlank(block.text())) {
          throw new IllegalArgumentException("文本消息内容不能为空");
        }
        if (block.type() == ContentType.IMAGE_URL
            && !notBlank(block.url())) {
          throw new IllegalArgumentException("图片 URL 不能为空");
        }
        if (block.type() == ContentType.TOOL_CALL
            && (!notBlank(block.toolCallId()) || !notBlank(block.toolName()))) {
          throw new IllegalArgumentException("工具调用 ID 和名称不能为空");
        }
        if (block.type() == ContentType.TOOL_RESULT
            && (!notBlank(block.toolCallId()) || block.text() == null)) {
          throw new IllegalArgumentException("工具结果必须包含调用 ID 和结果内容");
        }
        characters += length(block.text()) + length(block.url()) + length(block.argumentsJson());
      }
    }
    if (!notBlank(request.instructions()) && contentBlocks == 0) {
      throw new IllegalArgumentException("生成消息不能为空");
    }
    if (characters > maxCharacters) {
      throw new IllegalArgumentException("单次生成输入内容过长，最多 " + maxCharacters + " 字符");
    }
    if (request.maxOutputTokens() != null) {
      int maxOutput = positive(properties.getGenerationMaxOutputTokens(), 32_768);
      if (request.maxOutputTokens() <= 0 || request.maxOutputTokens() > maxOutput) {
        throw new IllegalArgumentException("输出 Token 数必须在 1 到 " + maxOutput + " 之间");
      }
    }
    validateProbability(request.temperature(), "temperature", 0, 2);
    validateProbability(request.topP(), "topP", 0, 1);
  }

  private static void validateProbability(
      final Double value,
      final String field,
      final double minimum,
      final double maximum
  ) {
    if (value != null && (!Double.isFinite(value) || value < minimum || value > maximum)) {
      throw new IllegalArgumentException(field + " 必须在 " + minimum + " 到 " + maximum + " 之间");
    }
  }

  private static int positive(final Integer value, final int fallback) {
    return value != null && value > 0 ? value : fallback;
  }

  private static int length(final String value) {
    return value == null ? 0 : value.length();
  }

  private static boolean notBlank(final String value) {
    return value != null && !value.isBlank();
  }

  private static String safeMessage(final RuntimeException ex) {
    return notBlank(ex.getMessage()) ? ex.getMessage() : "AI 生成失败";
  }

  private record PreparedInvocation(
      AiGenerationAdapter adapter,
      AiRuntimeInvocation invocation,
      AiInvocationLedger.InvocationActor actor
  ) {
  }
}
