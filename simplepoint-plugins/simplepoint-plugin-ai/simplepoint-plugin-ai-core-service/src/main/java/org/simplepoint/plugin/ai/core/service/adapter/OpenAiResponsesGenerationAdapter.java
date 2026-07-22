package org.simplepoint.plugin.ai.core.service.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ResponseFormat;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ResponseFormatType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.TokenUsage;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ToolDefinition;
import org.springframework.stereotype.Component;

/** Generation adapter for the native OpenAI Responses API. */
@Component
final class OpenAiResponsesGenerationAdapter implements AiGenerationAdapter {

  private final ObjectMapper objectMapper;

  private final ProviderHttpSupport http;

  OpenAiResponsesGenerationAdapter(
      final ObjectMapper objectMapper,
      final AiProperties properties
  ) {
    this.objectMapper = objectMapper;
    this.http = new ProviderHttpSupport(properties);
  }

  @Override
  public boolean supports(final AiProviderType providerType) {
    return providerType == AiProviderType.OPENAI;
  }

  @Override
  public GenerationResult generate(final AiRuntimeInvocation invocation) {
    long started = System.nanoTime();
    String responseBody = http.send(
        request(invocation, false),
        Boolean.TRUE.equals(invocation.provider().getAllowPrivateNetwork())
    );
    try {
      return parseResult(
          invocation,
          objectMapper.readTree(responseBody),
          elapsedMillis(started)
      );
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("无法解析 OpenAI Responses 响应", ex);
    }
  }

  @Override
  public void stream(
      final AiRuntimeInvocation invocation,
      final Consumer<GenerationEvent> consumer,
      final AiStreamCancellation cancellation
  ) {
    long started = System.nanoTime();
    AtomicLong sequence = new AtomicLong();
    Map<String, String> callIds = new HashMap<>();
    consumer.accept(event(invocation, sequence, EventType.STARTED, null, null, null, null, null));
    http.stream(
        request(invocation, true),
        Boolean.TRUE.equals(invocation.provider().getAllowPrivateNetwork()),
        line -> consumeSseLine(invocation, line, started, sequence, callIds, consumer),
        cancellation
    );
  }

  private void consumeSseLine(
      final AiRuntimeInvocation invocation,
      final String line,
      final long started,
      final AtomicLong sequence,
      final Map<String, String> callIds,
      final Consumer<GenerationEvent> consumer
  ) {
    if (!line.startsWith("data:")) {
      return;
    }
    String data = line.substring(5).trim();
    if (data.isEmpty() || "[DONE]".equals(data)) {
      return;
    }
    try {
      JsonNode root = objectMapper.readTree(data);
      String type = root.path("type").asText();
      switch (type) {
        case "response.output_text.delta" -> consumer.accept(event(
            invocation, sequence, EventType.TEXT_DELTA, root.path("delta").asText(),
            null, null, null, null
        ));
        case "response.refusal.delta" -> consumer.accept(event(
            invocation, sequence, EventType.REFUSAL_DELTA, root.path("delta").asText(),
            null, null, null, null
        ));
        case "response.output_item.added" -> {
          JsonNode item = root.path("item");
          if ("function_call".equals(item.path("type").asText())) {
            String itemId = item.path("id").asText(null);
            String callId = item.path("call_id").asText(itemId);
            if (itemId != null) {
              callIds.put(itemId, callId);
            }
            consumer.accept(event(
                invocation, sequence, EventType.TOOL_CALL_STARTED, null,
                callId, item.path("name").asText(null), null, null
            ));
          }
        }
        case "response.function_call_arguments.delta" -> {
          String itemId = root.path("item_id").asText(null);
          consumer.accept(event(
              invocation, sequence, EventType.TOOL_ARGUMENTS_DELTA, null,
              callIds.getOrDefault(itemId, itemId), null, root.path("delta").asText(), null
          ));
        }
        case "response.completed", "response.incomplete" -> {
          GenerationResult result = parseResult(
              invocation,
              root.path("response"),
              elapsedMillis(started)
          );
          consumer.accept(event(
              invocation, sequence, EventType.USAGE, null, null, null, null, result.usage()
          ));
          consumer.accept(new GenerationEvent(
              invocation.invocationId(), sequence.incrementAndGet(), EventType.COMPLETED,
              null, null, null, null, result.usage(), result, null, null
          ));
        }
        case "response.failed", "response.cancelled", "error" -> throw new IllegalStateException(
            root.path("error").path("message").asText("OpenAI 生成失败")
        );
        default -> {
          // Other lifecycle events do not carry normalized application data.
        }
      }
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("无法解析 OpenAI 流式响应", ex);
    }
  }

  private HttpRequest request(final AiRuntimeInvocation invocation, final boolean stream) {
    ObjectNode body = requestBody(invocation);
    body.put("stream", stream);
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(ProviderHttpSupport.endpoint(invocation.provider().getBaseUrl(), "/responses"))
        .timeout(ProviderHttpSupport.timeout(invocation.timeoutSeconds()))
        .header("Authorization", "Bearer " + invocation.apiKey())
        .header("Accept", stream ? "text/event-stream" : "application/json")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    addHeader(builder, "OpenAI-Organization", invocation.provider().getOrganizationId());
    addHeader(builder, "OpenAI-Project", invocation.provider().getProjectId());
    return builder.build();
  }

  private ObjectNode requestBody(final AiRuntimeInvocation invocation) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", invocation.model().getModelId());
    if (notBlank(invocation.request().instructions())) {
      body.put("instructions", invocation.request().instructions().trim());
    }
    putNumber(body, "max_output_tokens", invocation.request().maxOutputTokens());
    putNumber(body, "temperature", invocation.request().temperature());
    putNumber(body, "top_p", invocation.request().topP());
    ArrayNode input = body.putArray("input");
    if (invocation.request().messages() != null) {
      invocation.request().messages().forEach(message -> addMessage(input, message));
    }
    addTools(body, invocation.request().tools());
    addResponseFormat(body, invocation.request().responseFormat());
    return body;
  }

  private void addMessage(final ArrayNode input, final Message message) {
    if (message == null || message.role() == null || message.content() == null) {
      return;
    }
    ArrayNode regular = objectMapper.createArrayNode();
    for (ContentBlock block : message.content()) {
      if (block == null || block.type() == null) {
        continue;
      }
      switch (block.type()) {
        case TEXT -> {
          ObjectNode content = regular.addObject();
          content.put("type", message.role().name().equals("ASSISTANT")
              ? "output_text" : "input_text");
          content.put("text", value(block.text()));
        }
        case IMAGE_URL -> {
          ObjectNode content = regular.addObject();
          content.put("type", "input_image");
          content.put("image_url", value(block.url()));
        }
        case REFUSAL -> {
          ObjectNode content = regular.addObject();
          content.put("type", "refusal");
          content.put("refusal", value(block.text()));
        }
        case TOOL_RESULT -> {
          ObjectNode item = input.addObject();
          item.put("type", "function_call_output");
          item.put("call_id", value(block.toolCallId()));
          item.put("output", value(block.text()));
        }
        case TOOL_CALL -> {
          ObjectNode item = input.addObject();
          item.put("type", "function_call");
          item.put("call_id", value(block.toolCallId()));
          item.put("name", value(block.toolName()));
          item.put("arguments", jsonOrEmpty(block.argumentsJson()));
        }
        default -> throw new IllegalArgumentException("不支持的 OpenAI 内容类型: " + block.type());
      }
    }
    if (!regular.isEmpty()) {
      ObjectNode item = input.addObject();
      item.put("role", message.role().name().toLowerCase(java.util.Locale.ROOT));
      item.set("content", regular);
    }
  }

  private void addTools(final ObjectNode body, final List<ToolDefinition> tools) {
    if (tools == null || tools.isEmpty()) {
      return;
    }
    ArrayNode array = body.putArray("tools");
    for (ToolDefinition tool : tools) {
      ObjectNode node = array.addObject();
      node.put("type", "function");
      node.put("name", tool.name());
      if (notBlank(tool.description())) {
        node.put("description", tool.description());
      }
      node.set("parameters", parseJsonObject(tool.inputSchemaJson(), "工具输入 Schema"));
      node.put("strict", Boolean.TRUE.equals(tool.strict()));
    }
  }

  private void addResponseFormat(final ObjectNode body, final ResponseFormat format) {
    if (format == null || format.type() == null || format.type() == ResponseFormatType.TEXT) {
      return;
    }
    ObjectNode node = body.putObject("text").putObject("format");
    if (format.type() == ResponseFormatType.JSON_OBJECT) {
      node.put("type", "json_object");
      return;
    }
    node.put("type", "json_schema");
    node.put("name", notBlank(format.name()) ? format.name() : "response");
    if (notBlank(format.description())) {
      node.put("description", format.description());
    }
    node.set("schema", parseJsonObject(format.jsonSchema(), "响应 JSON Schema"));
    node.put("strict", Boolean.TRUE.equals(format.strict()));
  }

  private GenerationResult parseResult(
      final AiRuntimeInvocation invocation,
      final JsonNode root,
      final long durationMillis
  ) {
    List<ContentBlock> output = new ArrayList<>();
    for (JsonNode item : root.path("output")) {
      if ("message".equals(item.path("type").asText())) {
        for (JsonNode content : item.path("content")) {
          if ("output_text".equals(content.path("type").asText())) {
            output.add(new ContentBlock(
                ContentType.TEXT, content.path("text").asText(), null, null,
                null, null, null
            ));
          } else if ("refusal".equals(content.path("type").asText())) {
            output.add(new ContentBlock(
                ContentType.REFUSAL, content.path("refusal").asText(), null, null,
                null, null, null
            ));
          }
        }
      } else if ("function_call".equals(item.path("type").asText())) {
        output.add(new ContentBlock(
            ContentType.TOOL_CALL, null, null, null,
            item.path("call_id").asText(null), item.path("name").asText(null),
            item.path("arguments").asText("{}")
        ));
      }
    }
    JsonNode usage = root.path("usage");
    TokenUsage tokenUsage = new TokenUsage(
        intOrNull(usage, "input_tokens"), intOrNull(usage, "output_tokens"),
        intOrNull(usage, "total_tokens"),
        intOrNull(usage.path("input_tokens_details"), "cached_tokens")
    );
    String stopReason = root.path("status").asText("completed");
    if (root.path("incomplete_details").hasNonNull("reason")) {
      stopReason = root.path("incomplete_details").path("reason").asText();
    }
    return new GenerationResult(
        invocation.invocationId(), invocation.model().getId(), invocation.model().getModelId(),
        root.path("id").asText(null), List.copyOf(output), stopReason,
        tokenUsage, durationMillis, Instant.now()
    );
  }

  private ObjectNode parseJsonObject(final String value, final String field) {
    if (!notBlank(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    try {
      JsonNode parsed = objectMapper.readTree(value);
      if (!parsed.isObject()) {
        throw new IllegalArgumentException(field + "必须是 JSON 对象");
      }
      return (ObjectNode) parsed;
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(field + "格式无效", ex);
    }
  }

  private static GenerationEvent event(
      final AiRuntimeInvocation invocation,
      final AtomicLong sequence,
      final EventType type,
      final String text,
      final String toolCallId,
      final String toolName,
      final String arguments,
      final TokenUsage usage
  ) {
    return new GenerationEvent(
        invocation.invocationId(), sequence.incrementAndGet(), type, text,
        toolCallId, toolName, arguments, usage, null, null, null
    );
  }

  private static void putNumber(final ObjectNode body, final String name, final Number value) {
    if (value instanceof Integer integer) {
      body.put(name, integer);
    } else if (value instanceof Double decimal) {
      body.put(name, decimal);
    }
  }

  private static Integer intOrNull(final JsonNode node, final String field) {
    return node.has(field) && node.get(field).isNumber() ? node.get(field).asInt() : null;
  }

  private static long elapsedMillis(final long started) {
    return (System.nanoTime() - started) / 1_000_000L;
  }

  private static String jsonOrEmpty(final String value) {
    return notBlank(value) ? value : "{}";
  }

  private static String value(final String value) {
    return value == null ? "" : value;
  }

  private static boolean notBlank(final String value) {
    return value != null && !value.isBlank();
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
