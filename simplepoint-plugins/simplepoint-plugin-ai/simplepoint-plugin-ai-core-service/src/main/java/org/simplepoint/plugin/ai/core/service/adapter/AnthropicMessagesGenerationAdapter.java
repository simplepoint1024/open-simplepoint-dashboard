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

/** Generation adapter for the Anthropic Messages API. */
@Component
final class AnthropicMessagesGenerationAdapter implements AiGenerationAdapter {

  private static final String DEFAULT_API_VERSION = "2023-06-01";

  private static final int DEFAULT_MAX_TOKENS = 1024;

  private final ObjectMapper objectMapper;

  private final ProviderHttpSupport http;

  AnthropicMessagesGenerationAdapter(
      final ObjectMapper objectMapper,
      final AiProperties properties
  ) {
    this.objectMapper = objectMapper;
    this.http = new ProviderHttpSupport(properties);
  }

  @Override
  public boolean supports(final AiProviderType providerType) {
    return providerType == AiProviderType.ANTHROPIC;
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
      throw new IllegalStateException("无法解析 Anthropic Messages 响应", ex);
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
        state::accept
    );
    state.complete();
  }

  private HttpRequest request(final AiRuntimeInvocation invocation, final boolean stream) {
    ObjectNode body = requestBody(invocation);
    body.put("stream", stream);
    return HttpRequest.newBuilder()
        .uri(ProviderHttpSupport.endpoint(invocation.provider().getBaseUrl(), "/messages"))
        .timeout(ProviderHttpSupport.timeout(invocation.timeoutSeconds()))
        .header("x-api-key", invocation.apiKey())
        .header("anthropic-version", version(invocation.provider().getApiVersion()))
        .header("Accept", stream ? "text/event-stream" : "application/json")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build();
  }

  private ObjectNode requestBody(final AiRuntimeInvocation invocation) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", invocation.model().getModelId());
    body.put("max_tokens", invocation.request().maxOutputTokens() == null
        ? DEFAULT_MAX_TOKENS : invocation.request().maxOutputTokens());
    if (invocation.request().temperature() != null) {
      body.put("temperature", invocation.request().temperature());
    }
    if (invocation.request().topP() != null) {
      body.put("top_p", invocation.request().topP());
    }
    StringBuilder system = new StringBuilder();
    if (notBlank(invocation.request().instructions())) {
      system.append(invocation.request().instructions().trim());
    }
    ArrayNode messages = body.putArray("messages");
    if (invocation.request().messages() != null) {
      for (Message message : invocation.request().messages()) {
        if (message != null && message.role() == MessageRole.SYSTEM) {
          appendSystem(system, message);
        } else {
          addMessage(messages, message);
        }
      }
    }
    if (!system.isEmpty()) {
      body.put("system", system.toString());
    }
    addTools(body, invocation.request().tools());
    addResponseFormat(body, invocation.request().responseFormat());
    return body;
  }

  private void addMessage(final ArrayNode messages, final Message message) {
    if (message == null || message.role() == null || message.content() == null) {
      return;
    }
    ObjectNode target = messages.addObject();
    target.put("role", message.role() == MessageRole.ASSISTANT ? "assistant" : "user");
    ArrayNode content = target.putArray("content");
    for (ContentBlock block : message.content()) {
      if (block == null || block.type() == null) {
        continue;
      }
      switch (block.type()) {
        case TEXT -> content.addObject().put("type", "text").put("text", safe(block.text()));
        case REFUSAL -> content.addObject().put("type", "text").put("text", safe(block.text()));
        case IMAGE_URL -> content.addObject().put("type", "image")
            .putObject("source").put("type", "url").put("url", safe(block.url()));
        case TOOL_CALL -> {
          ObjectNode call = content.addObject();
          call.put("type", "tool_use").put("id", block.toolCallId())
              .put("name", block.toolName());
          call.set("input", parseObject(defaultJson(block.argumentsJson()), "工具调用参数"));
        }
        case TOOL_RESULT -> content.addObject().put("type", "tool_result")
            .put("tool_use_id", block.toolCallId()).put("content", safe(block.text()));
        default -> throw new IllegalArgumentException("不支持的 Anthropic 内容类型: " + block.type());
      }
    }
    if (content.isEmpty()) {
      messages.remove(messages.size() - 1);
    }
  }

  private void addTools(final ObjectNode body, final List<ToolDefinition> tools) {
    if (tools == null || tools.isEmpty()) {
      return;
    }
    ArrayNode array = body.putArray("tools");
    for (ToolDefinition tool : tools) {
      ObjectNode target = array.addObject();
      target.put("name", tool.name());
      if (notBlank(tool.description())) {
        target.put("description", tool.description());
      }
      target.set("input_schema", parseObject(tool.inputSchemaJson(), "工具输入 Schema"));
      if (tool.strict() != null) {
        target.put("strict", tool.strict());
      }
    }
  }

  private void addResponseFormat(final ObjectNode body, final ResponseFormat format) {
    if (format == null || format.type() == null || format.type() == ResponseFormatType.TEXT) {
      return;
    }
    if (format.type() == ResponseFormatType.JSON_OBJECT) {
      throw new IllegalArgumentException("Anthropic 结构化输出需要提供 JSON Schema");
    }
    ObjectNode target = body.putObject("output_config").putObject("format");
    target.put("type", "json_schema");
    target.set("schema", parseObject(format.jsonSchema(), "响应 JSON Schema"));
  }

  private GenerationResult parseResult(
      final AiRuntimeInvocation invocation,
      final JsonNode root,
      final long durationMillis
  ) {
    List<ContentBlock> output = new ArrayList<>();
    for (JsonNode content : root.path("content")) {
      if ("text".equals(content.path("type").asText())) {
        output.add(textBlock(content.path("text").asText()));
      } else if ("tool_use".equals(content.path("type").asText())) {
        output.add(new ContentBlock(
            ContentType.TOOL_CALL, null, null, null,
            content.path("id").asText(null), content.path("name").asText(null),
            content.path("input").toString()
        ));
      }
    }
    return new GenerationResult(
        invocation.invocationId(), invocation.model().getId(), invocation.model().getModelId(),
        root.path("id").asText(null), List.copyOf(output),
        root.path("stop_reason").asText(null), usage(root.path("usage")),
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
    private final Map<Integer, ToolState> tools = new LinkedHashMap<>();
    private Integer inputTokens;
    private Integer outputTokens;
    private String providerRequestId;
    private String stopReason;
    private boolean completed;
    private boolean messageStopped;

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
      try {
        JsonNode root = objectMapper.readTree(data);
        switch (root.path("type").asText()) {
          case "message_start" -> {
            JsonNode message = root.path("message");
            providerRequestId = message.path("id").asText(null);
            inputTokens = integer(message.path("usage"), "input_tokens");
          }
          case "content_block_start" -> startBlock(root);
          case "content_block_delta" -> delta(root);
          case "message_delta" -> {
            stopReason = root.path("delta").path("stop_reason").asText(stopReason);
            outputTokens = integer(root.path("usage"), "output_tokens");
            emit(EventType.USAGE, null, null, null, null, usage());
          }
          case "message_stop" -> messageStopped = true;
          case "error" -> throw new IllegalStateException(
              root.path("error").path("message").asText("Anthropic 生成失败")
          );
          default -> {
            // Ping and lifecycle events do not carry normalized content.
          }
        }
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException("无法解析 Anthropic 流式响应", ex);
      }
    }

    private void startBlock(final JsonNode root) {
      JsonNode block = root.path("content_block");
      if ("tool_use".equals(block.path("type").asText())) {
        ToolState tool = tools.computeIfAbsent(root.path("index").asInt(), ignored -> new ToolState());
        tool.id = block.path("id").asText(null);
        tool.name = block.path("name").asText(null);
        emit(EventType.TOOL_CALL_STARTED, null, tool.id, tool.name, null, null);
      }
    }

    private void delta(final JsonNode root) {
      JsonNode delta = root.path("delta");
      if ("text_delta".equals(delta.path("type").asText())) {
        String value = delta.path("text").asText();
        text.append(value);
        emit(EventType.TEXT_DELTA, value, null, null, null, null);
      } else if ("input_json_delta".equals(delta.path("type").asText())) {
        ToolState tool = tools.computeIfAbsent(root.path("index").asInt(), ignored -> new ToolState());
        String value = delta.path("partial_json").asText();
        tool.arguments.append(value);
        emit(EventType.TOOL_ARGUMENTS_DELTA, null, tool.id, tool.name, value, null);
      }
    }

    private void complete() {
      if (completed) {
        return;
      }
      if (!messageStopped) {
        throw new IllegalStateException("Anthropic 流式响应提前结束");
      }
      completed = true;
      List<ContentBlock> output = new ArrayList<>();
      if (!text.isEmpty()) {
        output.add(textBlock(text.toString()));
      }
      tools.values().forEach(tool -> output.add(new ContentBlock(
          ContentType.TOOL_CALL, null, null, null,
          tool.id, tool.name, tool.arguments.toString()
      )));
      GenerationResult result = new GenerationResult(
          invocation.invocationId(), invocation.model().getId(), invocation.model().getModelId(),
          providerRequestId, List.copyOf(output), stopReason, usage(),
          elapsedMillis(started), Instant.now()
      );
      consumer.accept(new GenerationEvent(
          invocation.invocationId(), sequence.incrementAndGet(), EventType.COMPLETED,
          null, null, null, null, result.usage(), result, null, null
      ));
    }

    private TokenUsage usage() {
      Integer total = inputTokens == null || outputTokens == null
          ? null : inputTokens + outputTokens;
      return new TokenUsage(inputTokens, outputTokens, total, null);
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

  private static void appendSystem(final StringBuilder target, final Message message) {
    if (message.content() == null) {
      return;
    }
    for (ContentBlock block : message.content()) {
      if (block != null && block.type() == ContentType.TEXT && notBlank(block.text())) {
        if (!target.isEmpty()) {
          target.append("\n\n");
        }
        target.append(block.text().trim());
      }
    }
  }

  private static ContentBlock textBlock(final String text) {
    return new ContentBlock(ContentType.TEXT, text, null, null, null, null, null);
  }

  private static TokenUsage usage(final JsonNode usage) {
    Integer input = integer(usage, "input_tokens");
    Integer output = integer(usage, "output_tokens");
    Integer total = input == null || output == null ? null : input + output;
    return new TokenUsage(
        input, output, total, integer(usage, "cache_read_input_tokens")
    );
  }

  private static Integer integer(final JsonNode node, final String field) {
    return node.has(field) && node.path(field).isNumber() ? node.path(field).asInt() : null;
  }

  private static String version(final String configured) {
    return notBlank(configured) ? configured.trim() : DEFAULT_API_VERSION;
  }

  private static long elapsedMillis(final long started) {
    return (System.nanoTime() - started) / 1_000_000L;
  }

  private static String defaultJson(final String value) {
    return notBlank(value) ? value : "{}";
  }

  private static String safe(final String value) {
    return value == null ? "" : value;
  }

  private static boolean notBlank(final String value) {
    return value != null && !value.isBlank();
  }
}
