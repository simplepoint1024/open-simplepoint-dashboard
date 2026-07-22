package org.simplepoint.plugin.ai.core.rest.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.simplepoint.plugin.ai.core.api.constants.AiPaths;
import org.simplepoint.plugin.ai.core.api.exception.AiGatewayAccessException;
import org.simplepoint.plugin.ai.core.api.exception.AiGatewayAccessException.FailureType;
import org.simplepoint.plugin.ai.core.api.exception.AiProviderRequestException;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService.GatewaySession;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService.GenerationStream;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.EventType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationEvent;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.rest.gateway.AiCompatibilityMapper;
import org.simplepoint.plugin.ai.core.rest.gateway.OpenAiResponsesProtocol;
import org.simplepoint.plugin.ai.core.rest.gateway.OpenAiResponsesProtocol.StreamEvent;
import org.simplepoint.plugin.ai.core.rest.gateway.OpenAiResponsesProtocol.StreamState;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** OpenAI Chat Completions, Responses, and Anthropic Messages compatible model gateway. */
@RestController
@RequestMapping(AiPaths.COMPATIBLE_API)
@Tag(name = "AI兼容网关", description = "使用平台签发的 API Key 调用 SimplePoint 模型")
public class AiCompatibleGatewayController {

  private static final String ANTHROPIC_API_KEY = "x-api-key";

  private final AiGatewayAccessService accessService;

  private final AiGenerationService generationService;

  private final AiCompatibilityMapper compatibilityMapper;

  private final OpenAiResponsesProtocol responsesProtocol;

  private final Executor inferenceExecutor;

  private final AiProperties properties;

  /** Creates the public compatibility gateway controller. */
  public AiCompatibleGatewayController(
      final AiGatewayAccessService accessService,
      final AiGenerationService generationService,
      final AiCompatibilityMapper compatibilityMapper,
      final OpenAiResponsesProtocol responsesProtocol,
      @Qualifier("aiInferenceExecutor") final Executor inferenceExecutor,
      final AiProperties properties
  ) {
    this.accessService = accessService;
    this.generationService = generationService;
    this.compatibilityMapper = compatibilityMapper;
    this.responsesProtocol = responsesProtocol;
    this.inferenceExecutor = inferenceExecutor;
    this.properties = properties;
  }

  /** OpenAI-compatible model catalog. */
  @GetMapping("/models")
  @Operation(summary = "OpenAI 兼容模型列表")
  public ResponseEntity<String> models(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) final String authorization,
      @RequestHeader(value = ANTHROPIC_API_KEY, required = false) final String anthropicKey,
      final HttpServletRequest servletRequest
  ) {
    try {
      GatewaySession session = authenticate(authorization, anthropicKey, servletRequest);
      ObjectNode response = compatibilityMapper.objectMapper().createObjectNode();
      response.put("object", "list");
      ArrayNode data = response.putArray("data");
      accessService.availableModels(session).forEach(model -> {
        ObjectNode item = data.addObject();
        item.put("id", model.id());
        item.put("object", "model");
        item.put("created", model.createdAtEpochSeconds());
        item.put("owned_by", "simplepoint");
        item.put("display_name", model.displayName());
      });
      return jsonResponse(response);
    } catch (RuntimeException ex) {
      return openAiError(ex);
    }
  }

  /** OpenAI-compatible chat completions, including SSE streaming. */
  @PostMapping("/chat/completions")
  @Operation(summary = "OpenAI Chat Completions 兼容接口")
  public ResponseEntity<?> chatCompletions(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) final String authorization,
      @RequestHeader(value = ANTHROPIC_API_KEY, required = false) final String alternateKey,
      @RequestBody final String payload,
      final HttpServletRequest servletRequest
  ) {
    try {
      JsonNode body = parseBody(payload);
      GatewaySession session = authenticate(authorization, alternateKey, servletRequest);
      String requestedModel = requireModel(body);
      String modelDefinitionId = accessService.resolveModelDefinitionId(session, requestedModel);
      GenerationRequest request = compatibilityMapper.fromOpenAi(body, modelDefinitionId);
      if (body.path("stream").asBoolean(false)) {
        GenerationStream stream = accessService.withSession(
            session, () -> generationService.prepareStream(request));
        return startOpenAiStream(stream, requestedModel);
      }
      GenerationResult result = accessService.withSession(
          session, () -> generationService.generate(request));
      return jsonResponse(compatibilityMapper.toOpenAiResponse(result, requestedModel));
    } catch (RuntimeException ex) {
      return openAiError(ex);
    }
  }

  /** OpenAI-compatible Responses API, including protocol-native SSE events. */
  @PostMapping("/responses")
  @Operation(summary = "OpenAI Responses API 兼容接口")
  public ResponseEntity<?> responses(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) final String authorization,
      @RequestHeader(value = ANTHROPIC_API_KEY, required = false) final String alternateKey,
      @RequestBody final String payload,
      final HttpServletRequest servletRequest
  ) {
    try {
      JsonNode body = parseBody(payload);
      GatewaySession session = authenticate(authorization, alternateKey, servletRequest);
      String requestedModel = requireModel(body);
      String modelDefinitionId = accessService.resolveModelDefinitionId(session, requestedModel);
      GenerationRequest request = responsesProtocol.fromRequest(body, modelDefinitionId);
      if (body.path("stream").asBoolean(false)) {
        GenerationStream stream = accessService.withSession(
            session, () -> generationService.prepareStream(request));
        return startResponsesStream(stream, requestedModel, body);
      }
      GenerationResult result = accessService.withSession(
          session, () -> generationService.generate(request));
      return jsonResponse(responsesProtocol.toResponse(result, requestedModel, body));
    } catch (RuntimeException ex) {
      return openAiError(ex);
    }
  }

  /** Anthropic-compatible Messages API, including protocol-native SSE events. */
  @PostMapping("/messages")
  @Operation(summary = "Anthropic Messages 兼容接口")
  public ResponseEntity<?> messages(
      @RequestHeader(value = ANTHROPIC_API_KEY, required = false) final String anthropicKey,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) final String authorization,
      @RequestBody final String payload,
      final HttpServletRequest servletRequest
  ) {
    try {
      JsonNode body = parseBody(payload);
      GatewaySession session = authenticate(authorization, anthropicKey, servletRequest);
      String requestedModel = requireModel(body);
      String modelDefinitionId = accessService.resolveModelDefinitionId(session, requestedModel);
      GenerationRequest request = compatibilityMapper.fromAnthropic(body, modelDefinitionId);
      if (body.path("stream").asBoolean(false)) {
        GenerationStream stream = accessService.withSession(
            session, () -> generationService.prepareStream(request));
        return startAnthropicStream(stream, requestedModel);
      }
      GenerationResult result = accessService.withSession(
          session, () -> generationService.generate(request));
      return jsonResponse(compatibilityMapper.toAnthropicResponse(result, requestedModel));
    } catch (RuntimeException ex) {
      return anthropicError(ex);
    }
  }

  /** Keeps malformed JSON errors compatible with the selected public protocol. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<String> malformedJson(
      final HttpMessageNotReadableException exception,
      final HttpServletRequest request
  ) {
    IllegalArgumentException error = new IllegalArgumentException("请求体不是有效的 JSON", exception);
    return request.getRequestURI().endsWith("/messages")
        ? anthropicError(error) : openAiError(error);
  }

  private ResponseEntity<SseEmitter> startOpenAiStream(
      final GenerationStream stream,
      final String requestedModel
  ) {
    SseEmitter emitter = emitter(stream);
    try {
      inferenceExecutor.execute(() -> consumeOpenAi(stream, emitter, requestedModel));
    } catch (RejectedExecutionException ex) {
      stream.cancel();
      throw ex;
    }
    return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
  }

  private ResponseEntity<SseEmitter> startAnthropicStream(
      final GenerationStream stream,
      final String requestedModel
  ) {
    SseEmitter emitter = emitter(stream);
    try {
      inferenceExecutor.execute(() -> consumeAnthropic(stream, emitter, requestedModel));
    } catch (RejectedExecutionException ex) {
      stream.cancel();
      throw ex;
    }
    return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
  }

  private ResponseEntity<SseEmitter> startResponsesStream(
      final GenerationStream stream,
      final String requestedModel,
      final JsonNode request
  ) {
    SseEmitter emitter = emitter(stream);
    try {
      inferenceExecutor.execute(() -> consumeResponses(stream, emitter, requestedModel, request));
    } catch (RejectedExecutionException ex) {
      stream.cancel();
      throw ex;
    }
    return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
  }

  private SseEmitter emitter(final GenerationStream stream) {
    SseEmitter emitter = new SseEmitter(timeout());
    AtomicBoolean terminal = new AtomicBoolean();
    Runnable cancel = () -> {
      if (terminal.compareAndSet(false, true)) {
        stream.cancel();
      }
    };
    emitter.onTimeout(cancel);
    emitter.onError(error -> cancel.run());
    emitter.onCompletion(cancel);
    return emitter;
  }

  private void consumeOpenAi(
      final GenerationStream stream,
      final SseEmitter emitter,
      final String requestedModel
  ) {
    OpenAiStreamState state = new OpenAiStreamState(requestedModel);
    try {
      stream.consume(event -> sendOpenAiEvent(emitter, state, event));
      emitter.complete();
    } catch (CancellationException ex) {
      emitter.complete();
    } catch (RuntimeException ex) {
      emitter.completeWithError(ex);
    }
  }

  private void sendOpenAiEvent(
      final SseEmitter emitter,
      final OpenAiStreamState state,
      final GenerationEvent event
  ) {
    state.capture(event);
    ObjectNode chunk = compatibilityMapper.openAiChunkBase(
        state.invocationId, state.requestedModel, state.created);
    if (event.type() == EventType.USAGE) {
      chunk.putArray("choices");
      chunk.set("usage", compatibilityMapper.openAiUsage(event.usage()));
      sendData(emitter, chunk);
      return;
    }
    if (event.type() == EventType.ERROR) {
      sendData(emitter, compatibilityMapper.openAiError(
          event.errorMessage(), "api_error", event.errorCode()));
      return;
    }
    ObjectNode choice = chunk.putArray("choices").addObject();
    choice.put("index", 0);
    ObjectNode delta = choice.putObject("delta");
    choice.putNull("finish_reason");
    switch (event.type()) {
      case STARTED -> delta.put("role", "assistant");
      case TEXT_DELTA -> delta.put("content", safe(event.textDelta()));
      case REFUSAL_DELTA -> delta.put("refusal", safe(event.textDelta()));
      case TOOL_CALL_STARTED -> {
        int index = state.toolIndex(event.toolCallId());
        ObjectNode call = delta.putArray("tool_calls").addObject();
        call.put("index", index);
        call.put("id", event.toolCallId());
        call.put("type", "function");
        call.putObject("function").put("name", event.toolName()).put("arguments", "");
      }
      case TOOL_ARGUMENTS_DELTA -> {
        int index = state.toolIndex(event.toolCallId());
        ObjectNode call = delta.putArray("tool_calls").addObject();
        call.put("index", index);
        call.putObject("function").put("arguments", safe(event.argumentsDelta()));
      }
      case COMPLETED -> {
        choice.remove("delta");
        choice.put("finish_reason", AiCompatibilityMapper.openAiStopReason(
            event.result() == null ? null : event.result().stopReason()));
        if (event.result() != null) {
          chunk.set("usage", compatibilityMapper.openAiUsage(event.result().usage()));
        }
      }
      default -> {
        return;
      }
    }
    sendData(emitter, chunk);
    if (event.type() == EventType.COMPLETED) {
      sendData(emitter, "[DONE]");
    }
  }

  private void consumeAnthropic(
      final GenerationStream stream,
      final SseEmitter emitter,
      final String requestedModel
  ) {
    AnthropicStreamState state = new AnthropicStreamState(requestedModel);
    try {
      stream.consume(event -> sendAnthropicEvent(emitter, state, event));
      emitter.complete();
    } catch (CancellationException ex) {
      emitter.complete();
    } catch (RuntimeException ex) {
      emitter.completeWithError(ex);
    }
  }

  private void consumeResponses(
      final GenerationStream stream,
      final SseEmitter emitter,
      final String requestedModel,
      final JsonNode request
  ) {
    StreamState state = responsesProtocol.newStreamState(requestedModel, request);
    try {
      stream.consume(event -> responsesProtocol.streamEvents(state, event)
          .forEach(responseEvent -> sendResponseEvent(emitter, responseEvent)));
      emitter.complete();
    } catch (CancellationException ex) {
      emitter.complete();
    } catch (RuntimeException ex) {
      emitter.completeWithError(ex);
    }
  }

  private void sendAnthropicEvent(
      final SseEmitter emitter,
      final AnthropicStreamState state,
      final GenerationEvent event
  ) {
    state.capture(event);
    switch (event.type()) {
      case STARTED -> {
        ObjectNode payload = event("message_start");
        ObjectNode message = payload.putObject("message");
        message.put("id", "msg_" + state.invocationId.replace("-", ""));
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", state.requestedModel);
        message.putArray("content");
        message.putNull("stop_reason");
        message.putNull("stop_sequence");
        message.set("usage", compatibilityMapper.anthropicUsage(null));
        sendEvent(emitter, "message_start", payload);
      }
      case TEXT_DELTA -> {
        int index = state.textIndex();
        if (state.openIndexes.add(index)) {
          ObjectNode start = event("content_block_start");
          start.put("index", index);
          start.putObject("content_block").put("type", "text").put("text", "");
          sendEvent(emitter, "content_block_start", start);
        }
        ObjectNode delta = event("content_block_delta");
        delta.put("index", index);
        delta.putObject("delta").put("type", "text_delta").put("text", safe(event.textDelta()));
        sendEvent(emitter, "content_block_delta", delta);
      }
      case TOOL_CALL_STARTED -> {
        int index = state.toolIndex(event.toolCallId());
        state.openIndexes.add(index);
        ObjectNode start = event("content_block_start");
        start.put("index", index);
        ObjectNode block = start.putObject("content_block");
        block.put("type", "tool_use");
        block.put("id", event.toolCallId());
        block.put("name", event.toolName());
        block.set("input", compatibilityMapper.objectMapper().createObjectNode());
        sendEvent(emitter, "content_block_start", start);
      }
      case TOOL_ARGUMENTS_DELTA -> {
        int index = state.toolIndex(event.toolCallId());
        ObjectNode delta = event("content_block_delta");
        delta.put("index", index);
        delta.putObject("delta").put("type", "input_json_delta")
            .put("partial_json", safe(event.argumentsDelta()));
        sendEvent(emitter, "content_block_delta", delta);
      }
      case COMPLETED -> {
        state.openIndexes.forEach(index -> {
          ObjectNode stop = event("content_block_stop");
          stop.put("index", index);
          sendEvent(emitter, "content_block_stop", stop);
        });
        ObjectNode delta = event("message_delta");
        delta.putObject("delta")
            .put("stop_reason", AiCompatibilityMapper.anthropicStopReason(
                event.result() == null ? null : event.result().stopReason()))
            .putNull("stop_sequence");
        delta.set("usage", compatibilityMapper.anthropicUsage(
            event.result() == null ? null : event.result().usage()));
        sendEvent(emitter, "message_delta", delta);
        sendEvent(emitter, "message_stop", event("message_stop"));
      }
      case ERROR -> sendEvent(emitter, "error", compatibilityMapper.anthropicError(
          event.errorMessage(), "api_error"));
      default -> {
        // Usage is included in message_delta; refusal deltas are represented as text.
        if (event.type() == EventType.REFUSAL_DELTA) {
          sendAnthropicEvent(emitter, state, new GenerationEvent(
              event.invocationId(), event.sequence(), EventType.TEXT_DELTA,
              event.textDelta(), null, null, null, event.usage(), event.result(),
              event.errorCode(), event.errorMessage()));
        }
      }
    }
  }

  private ObjectNode event(final String type) {
    return compatibilityMapper.objectMapper().createObjectNode().put("type", type);
  }

  private GatewaySession authenticate(
      final String authorization,
      final String alternateKey,
      final HttpServletRequest request
  ) {
    String bearer = null;
    if (authorization != null && !authorization.isBlank()) {
      if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
        throw accessFailure(FailureType.AUTHENTICATION, "Authorization 必须使用 Bearer API Key");
      }
      bearer = authorization.substring(7).trim();
    }
    String direct = normalize(alternateKey);
    if (bearer != null && direct != null && !bearer.equals(direct)) {
      throw accessFailure(FailureType.AUTHENTICATION, "请求包含不一致的 API Key");
    }
    String apiKey = normalize(bearer != null ? bearer : direct);
    if (apiKey == null) {
      throw accessFailure(FailureType.AUTHENTICATION, "缺少模型 API Key");
    }
    return accessService.authenticate(apiKey, request.getRemoteAddr());
  }

  private ResponseEntity<String> openAiError(final RuntimeException ex) {
    ErrorStatus error = status(ex);
    return ResponseEntity.status(error.status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(json(compatibilityMapper.openAiError(error.message, error.openAiType, error.code)));
  }

  private ResponseEntity<String> anthropicError(final RuntimeException ex) {
    ErrorStatus error = status(ex);
    return ResponseEntity.status(error.status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(json(compatibilityMapper.anthropicError(error.message, error.anthropicType)));
  }

  private ResponseEntity<String> jsonResponse(final JsonNode body) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(json(body));
  }

  private JsonNode parseBody(final String payload) {
    try {
      JsonNode body = compatibilityMapper.objectMapper().readTree(payload);
      if (body == null) {
        throw new IllegalArgumentException("请求体必须是 JSON 对象");
      }
      return body;
    } catch (IOException ex) {
      throw new IllegalArgumentException("请求体不是有效的 JSON", ex);
    }
  }

  private static ErrorStatus status(final RuntimeException ex) {
    if (ex instanceof AiGatewayAccessException access) {
      return switch (access.getFailureType()) {
        case AUTHENTICATION -> new ErrorStatus(401, access.getMessage(),
            "authentication_error", "invalid_api_key", "authentication_error");
        case PERMISSION -> new ErrorStatus(403, access.getMessage(),
            "permission_error", "permission_denied", "permission_error");
        case RATE_LIMIT -> new ErrorStatus(429, access.getMessage(),
            "rate_limit_error", "rate_limit_exceeded", "rate_limit_error");
      };
    }
    if (ex instanceof IllegalArgumentException) {
      return new ErrorStatus(400, safeMessage(ex, "请求参数无效"),
          "invalid_request_error", "invalid_request", "invalid_request_error");
    }
    if (ex instanceof AiProviderRequestException) {
      return new ErrorStatus(502, safeMessage(ex, "上游模型服务调用失败"),
          "api_error", "upstream_error", "api_error");
    }
    if (ex instanceof RejectedExecutionException) {
      return new ErrorStatus(503, "模型服务繁忙，请稍后重试",
          "api_error", "service_unavailable", "overloaded_error");
    }
    return new ErrorStatus(500, "模型服务调用失败",
        "api_error", "internal_error", "api_error");
  }

  private static String requireModel(final JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new IllegalArgumentException("请求体必须是 JSON 对象");
    }
    String model = normalize(body.path("model").asText(null));
    if (model == null) {
      throw new IllegalArgumentException("model 不能为空");
    }
    return model;
  }

  private static AiGatewayAccessException accessFailure(
      final FailureType type,
      final String message
  ) {
    return new AiGatewayAccessException(type, message);
  }

  private static String safeMessage(final RuntimeException ex, final String fallback) {
    return normalize(ex.getMessage()) == null ? fallback : ex.getMessage();
  }

  private static String safe(final String value) {
    return value == null ? "" : value;
  }

  private static String json(final JsonNode value) {
    return value == null ? "null" : value.toString();
  }

  private static String normalize(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private long timeout() {
    Long configured = properties.getStreamingTimeoutMs();
    return configured != null && configured > 0 ? configured : 300_000L;
  }

  private static void sendData(final SseEmitter emitter, final Object data) {
    try {
      emitter.send(SseEmitter.event().data(data instanceof JsonNode node ? json(node) : data));
    } catch (IOException ex) {
      CancellationException cancellation = new CancellationException("客户端已断开流式连接");
      cancellation.initCause(ex);
      throw cancellation;
    }
  }

  private static void sendEvent(
      final SseEmitter emitter,
      final String eventName,
      final JsonNode data
  ) {
    try {
      emitter.send(SseEmitter.event().name(eventName).data(json(data)));
    } catch (IOException ex) {
      CancellationException cancellation = new CancellationException("客户端已断开流式连接");
      cancellation.initCause(ex);
      throw cancellation;
    }
  }

  private static void sendResponseEvent(
      final SseEmitter emitter,
      final StreamEvent event
  ) {
    sendEvent(emitter, event.name(), event.data());
  }

  private record ErrorStatus(
      int status,
      String message,
      String openAiType,
      String code,
      String anthropicType
  ) {
  }

  private static final class OpenAiStreamState {

    private final String requestedModel;

    private final long created = Instant.now().getEpochSecond();

    private final Map<String, Integer> tools = new LinkedHashMap<>();

    private String invocationId = "pending";

    private OpenAiStreamState(final String requestedModel) {
      this.requestedModel = requestedModel;
    }

    private void capture(final GenerationEvent event) {
      if (event.invocationId() != null && !event.invocationId().isBlank()) {
        invocationId = event.invocationId();
      }
    }

    private int toolIndex(final String toolCallId) {
      return tools.computeIfAbsent(safe(toolCallId), ignored -> tools.size());
    }
  }

  private static final class AnthropicStreamState {

    private final String requestedModel;

    private final Map<String, Integer> tools = new LinkedHashMap<>();

    private final Set<Integer> openIndexes = new LinkedHashSet<>();

    private String invocationId = "pending";

    private Integer textIndex;

    private AnthropicStreamState(final String requestedModel) {
      this.requestedModel = requestedModel;
    }

    private void capture(final GenerationEvent event) {
      if (event.invocationId() != null && !event.invocationId().isBlank()) {
        invocationId = event.invocationId();
      }
    }

    private int textIndex() {
      if (textIndex == null) {
        textIndex = tools.size();
      }
      return textIndex;
    }

    private int toolIndex(final String toolCallId) {
      return tools.computeIfAbsent(safe(toolCallId), ignored -> {
        int candidate = tools.size();
        return textIndex != null && candidate >= textIndex ? candidate + 1 : candidate;
      });
    }
  }
}
