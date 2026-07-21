package org.simplepoint.plugin.ai.core.service.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.EventType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationEvent;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.Message;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.MessageRole;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ResponseFormat;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ResponseFormatType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.TokenUsage;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ToolDefinition;
import org.springframework.stereotype.Component;

/** Generation adapter for OpenAI-compatible Chat Completions APIs. */
@Component
final class OpenAiCompatibleGenerationAdapter implements AiGenerationAdapter {

  private final ObjectMapper objectMapper;

  private final ProviderHttpSupport http;

  OpenAiCompatibleGenerationAdapter(
      final ObjectMapper objectMapper,
      final AiProperties properties
  ) {
    this.objectMapper = objectMapper;
    this.http = new ProviderHttpSupport(properties);
  }

  @Override
  public boolean supports(final AiProviderType providerType) {
    return providerType == AiProviderType.OPENAI_COMPATIBLE;
  }

  @Override
  public GenerationResult generate(final AiRuntimeInvocation invocation) {
    long started = System.nanoTime();
    HttpResponse<String> response = http.send(
        request(invocation, false),
        Boolean.TRUE.equals(invocation.provider().getAllowPrivateNetwork())
    );
    try {
      return parseResult(invocation, objectMapper.readTree(response.body()), elapsedMillis(started));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("无法解析 OpenAI 兼容接口响应", ex);
    }
  }

  @Override
  public void stream(
      final AiRuntimeInvocation invocation,
      final Consumer<GenerationEvent> consumer
  ) {
    StreamState state = new StreamState(invocation, consumer);
    state.emit(EventType.STARTED, null, null, null, null, null);
    http.stream(
        request(invocation, true),
        Boolean.TRUE.equals(invocation.provider().getAllowPrivateNetwork()),
        line -> state.accept(line)
    );
    state.complete();
  }

  private HttpRequest request(final AiRuntimeInvocation invocation, final boolean stream) {
    ObjectNode body = requestBody(invocation);
    body.put("stream", stream);
    if (stream) {
      body.putObject("stream_options").put("include_usage", true);
    }
    HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(ProviderHttpSupport.endpoint(
            invocation.provider().getBaseUrl(), "/chat/completions"
        ))
        .timeout(ProviderHttpSupport.timeout(invocation.timeoutSeconds()))
        .header("Accept", stream ? "text/event-stream" : "application/json")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    if (notBlank(invocation.apiKey())) {
      request.header("Authorization", "Bearer " + invocation.apiKey());
    }
    addHeader(request, "OpenAI-Organization", invocation.provider().getOrganizationId());
    addHeader(request, "OpenAI-Project", invocation.provider().getProjectId());
    return request.build();
  }

  private ObjectNode requestBody(final AiRuntimeInvocation invocation) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", invocation.model().getModelId());
    if (invocation.request().maxOutputTokens() != null) {
      body.put("max_tokens", invocation.request().maxOutputTokens());
    }
    if (invocation.request().temperature() != null) {
      body.put("temperature", invocation.request().temperature());
    }
    if (invocation.request().topP() != null) {
      body.put("top_p", invocation.request().topP());
    }
    ArrayNode messages = body.putArray("messages");
    if (notBlank(invocation.request().instructions())) {
      messages.addObject().put("role", "system")
          .put("content", invocation.request().instructions().trim());
    }
    if (invocation.request().messages() != null) {
      invocation.request().messages().forEach(message -> addMessage(messages, message));
    }
    addTools(body, invocation.request().tools());
    addResponseFormat(body, invocation.request().responseFormat());
    return body;
  }

  private void addMessage(final ArrayNode messages, final Message message) {
    if (message == null || message.role() == null || message.content() == null) {
      return;
    }
    if (message.role() == MessageRole.TOOL) {
      message.content().stream()
          .filter(block -> block != null && block.type() == ContentType.TOOL_RESULT)
          .forEach(block -> messages.addObject()
              .put("role", "tool")
              .put("tool_call_id", block.toolCallId())
              .put("content", block.text()));
      return;
    }
    ObjectNode target = messages.addObject();
    target.put("role", message.role().name().toLowerCase(Locale.ROOT));
    ArrayNode content = target.putArray("content");
    ArrayNode toolCalls = null;
    for (ContentBlock block : message.content()) {
      if (block == null || block.type() == null) {
        continue;
      }
      if (block.type() == ContentType.TEXT) {
        content.addObject().put("type", "text").put("text", safe(block.text()));
      } else if (block.type() == ContentType.REFUSAL) {
        target.put("refusal", safe(block.text()));
      } else if (block.type() == ContentType.IMAGE_URL) {
        content.addObject().put("type", "image_url")
            .putObject("image_url").put("url", safe(block.url()));
      } else if (block.type() == ContentType.TOOL_CALL) {
        if (toolCalls == null) {
          toolCalls = target.putArray("tool_calls");
        }
        ObjectNode call = toolCalls.addObject();
        call.put("id", block.toolCallId()).put("type", "function");
        call.putObject("function").put("name", block.toolName())
            .put("arguments", defaultJson(block.argumentsJson()));
      }
    }
    if (content.isEmpty()) {
      target.remove("content");
    }
  }

  private void addTools(final ObjectNode body, final List<ToolDefinition> tools) {
    if (tools == null || tools.isEmpty()) {
      return;
    }
    ArrayNode target = body.putArray("tools");
    for (ToolDefinition tool : tools) {
      ObjectNode function = target.addObject().put("type", "function").putObject("function");
      function.put("name", tool.name());
      if (notBlank(tool.description())) {
        function.put("description", tool.description());
      }
      function.set("parameters", parseObject(tool.inputSchemaJson(), "工具输入 Schema"));
      function.put("strict", Boolean.TRUE.equals(tool.strict()));
    }
  }

  private void addResponseFormat(final ObjectNode body, final ResponseFormat format) {
    if (format == null || format.type() == null || format.type() == ResponseFormatType.TEXT) {
      return;
    }
    ObjectNode target = body.putObject("response_format");
    if (format.type() == ResponseFormatType.JSON_OBJECT) {
      target.put("type", "json_object");
      return;
    }
    target.put("type", "json_schema");
    ObjectNode schema = target.putObject("json_schema");
    schema.put("name", notBlank(format.name()) ? format.name() : "response");
    if (notBlank(format.description())) {
      schema.put("description", format.description());
    }
    schema.set("schema", parseObject(format.jsonSchema(), "响应 JSON Schema"));
    schema.put("strict", Boolean.TRUE.equals(format.strict()));
  }

  private GenerationResult parseResult(
      final AiRuntimeInvocation invocation,
      final JsonNode root,
      final long durationMillis
  ) {
    JsonNode choice = root.path("choices").path(0);
    JsonNode message = choice.path("message");
    List<ContentBlock> output = new ArrayList<>();
    if (message.hasNonNull("content")) {
      output.add(textBlock(message.path("content").asText()));
    }
    if (message.hasNonNull("refusal")) {
      output.add(new ContentBlock(
          ContentType.REFUSAL, message.path("refusal").asText(), null, null,
          null, null, null
      ));
    }
    for (JsonNode call : message.path("tool_calls")) {
      output.add(toolBlock(call));
    }
    return new GenerationResult(
        invocation.invocationId(), invocation.model().getId(), invocation.model().getModelId(),
        root.path("id").asText(null), List.copyOf(output),
        choice.path("finish_reason").asText(null), usage(root.path("usage")),
        durationMillis, Instant.now()
    );
  }

  private ObjectNode parseObject(final String json, final String field) {
    try {
      JsonNode node = objectMapper.readTree(json);
      if (!node.isObject()) {
        throw new IllegalArgumentException(field + "必须是 JSON 对象");
      }
      return (ObjectNode) node;
    } catch (JsonProcessingException | NullPointerException ex) {
      throw new IllegalArgumentException(field + "格式无效", ex);
    }
  }

  private final class StreamState {

    private final AiRuntimeInvocation invocation;
    private final Consumer<GenerationEvent> consumer;
    private final AtomicLong sequence = new AtomicLong();
    private final long started = System.nanoTime();
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder refusal = new StringBuilder();
    private final Map<Integer, ToolState> tools = new LinkedHashMap<>();
    private TokenUsage usage;
    private String providerRequestId;
    private String stopReason;
    private boolean completed;
    private boolean finished;

    private StreamState(
        final AiRuntimeInvocation invocation,
        final Consumer<GenerationEvent> consumer
    ) {
      this.invocation = invocation;
      this.consumer = consumer;
    }

    private void accept(final String line) {
      if (!line.startsWith("data:")) {
        return;
      }
      String data = line.substring(5).trim();
      if (data.isEmpty()) {
        return;
      }
      if ("[DONE]".equals(data)) {
        finished = true;
        return;
      }
      try {
        JsonNode root = objectMapper.readTree(data);
        providerRequestId = root.path("id").asText(providerRequestId);
        if (!root.path("usage").isMissingNode() && !root.path("usage").isNull()) {
          usage = usage(root.path("usage"));
          emit(EventType.USAGE, null, null, null, null, usage);
        }
        JsonNode choice = root.path("choices").path(0);
        if (choice.hasNonNull("finish_reason")) {
          stopReason = choice.path("finish_reason").asText();
          finished = true;
        }
        JsonNode delta = choice.path("delta");
        if (delta.hasNonNull("content")) {
          String value = delta.path("content").asText();
          text.append(value);
          emit(EventType.TEXT_DELTA, value, null, null, null, null);
        }
        if (delta.hasNonNull("refusal")) {
          String value = delta.path("refusal").asText();
          refusal.append(value);
          emit(EventType.REFUSAL_DELTA, value, null, null, null, null);
        }
        for (JsonNode call : delta.path("tool_calls")) {
          int index = call.path("index").asInt();
          ToolState state = tools.computeIfAbsent(index, ignored -> new ToolState());
          if (call.hasNonNull("id")) {
            state.id = call.path("id").asText();
          }
          JsonNode function = call.path("function");
          if (function.hasNonNull("name")) {
            state.name = function.path("name").asText();
            emit(EventType.TOOL_CALL_STARTED, null, state.id, state.name, null, null);
          }
          if (function.hasNonNull("arguments")) {
            String arguments = function.path("arguments").asText();
            state.arguments.append(arguments);
            emit(EventType.TOOL_ARGUMENTS_DELTA, null, state.id, state.name, arguments, null);
          }
        }
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException("无法解析 OpenAI 兼容流式响应", ex);
      }
    }

    private void complete() {
      if (completed) {
        return;
      }
      if (!finished) {
        throw new IllegalStateException("OpenAI 兼容流式响应提前结束");
      }
      completed = true;
      List<ContentBlock> output = new ArrayList<>();
      if (!text.isEmpty()) {
        output.add(textBlock(text.toString()));
      }
      if (!refusal.isEmpty()) {
        output.add(new ContentBlock(
            ContentType.REFUSAL, refusal.toString(), null, null,
            null, null, null
        ));
      }
      tools.values().forEach(tool -> output.add(new ContentBlock(
          ContentType.TOOL_CALL, null, null, null,
          tool.id, tool.name, tool.arguments.toString()
      )));
      GenerationResult result = new GenerationResult(
          invocation.invocationId(), invocation.model().getId(), invocation.model().getModelId(),
          providerRequestId, List.copyOf(output), stopReason, usage,
          elapsedMillis(started), Instant.now()
      );
      consumer.accept(new GenerationEvent(
          invocation.invocationId(), sequence.incrementAndGet(), EventType.COMPLETED,
          null, null, null, null, usage, result, null, null
      ));
    }

    private void emit(
        final EventType type,
        final String textDelta,
        final String toolCallId,
        final String toolName,
        final String arguments,
        final TokenUsage eventUsage
    ) {
      consumer.accept(new GenerationEvent(
          invocation.invocationId(), sequence.incrementAndGet(), type, textDelta,
          toolCallId, toolName, arguments, eventUsage, null, null, null
      ));
    }
  }

  private static final class ToolState {
    private String id;
    private String name;
    private final StringBuilder arguments = new StringBuilder();
  }

  private static ContentBlock textBlock(final String text) {
    return new ContentBlock(ContentType.TEXT, text, null, null, null, null, null);
  }

  private static ContentBlock toolBlock(final JsonNode call) {
    return new ContentBlock(
        ContentType.TOOL_CALL, null, null, null, call.path("id").asText(null),
        call.path("function").path("name").asText(null),
        call.path("function").path("arguments").asText("{}")
    );
  }

  private static TokenUsage usage(final JsonNode usage) {
    return new TokenUsage(
        integer(usage, "prompt_tokens"), integer(usage, "completion_tokens"),
        integer(usage, "total_tokens"),
        integer(usage.path("prompt_tokens_details"), "cached_tokens")
    );
  }

  private static Integer integer(final JsonNode node, final String field) {
    return node.has(field) && node.path(field).isNumber() ? node.path(field).asInt() : null;
  }

  private static long elapsedMillis(final long started) {
    return (System.nanoTime() - started) / 1_000_000L;
  }

  private static boolean notBlank(final String value) {
    return value != null && !value.isBlank();
  }

  private static String safe(final String value) {
    return value == null ? "" : value;
  }

  private static String defaultJson(final String value) {
    return notBlank(value) ? value : "{}";
  }

  private static void addHeader(
      final HttpRequest.Builder request,
      final String name,
      final String value
  ) {
    if (notBlank(value)) {
      request.header(name, value.trim());
    }
  }
}
