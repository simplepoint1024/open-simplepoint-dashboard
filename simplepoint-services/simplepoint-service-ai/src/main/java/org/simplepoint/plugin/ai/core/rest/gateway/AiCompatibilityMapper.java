package org.simplepoint.plugin.ai.core.rest.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.Message;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.MessageRole;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ResponseFormat;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ResponseFormatType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.TokenUsage;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ToolDefinition;
import org.springframework.stereotype.Component;

/** Converts OpenAI and Anthropic public payloads to and from the provider-neutral runtime. */
@Component
public class AiCompatibilityMapper {

  private final ObjectMapper mapper;

  /** Creates the mapper with the application's configured Jackson mapper. */
  public AiCompatibilityMapper(final ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** Converts one OpenAI Chat Completions request to the neutral runtime contract. */
  public GenerationRequest fromOpenAi(final JsonNode body, final String modelDefinitionId) {
    requireObject(body, "请求体必须是 JSON 对象");
    return new GenerationRequest(
        modelDefinitionId,
        null,
        openAiMessages(body.path("messages")),
        firstInteger(body, "max_completion_tokens", "max_tokens"),
        optionalDouble(body, "temperature"),
        optionalDouble(body, "top_p"),
        openAiTools(body.path("tools")),
        openAiResponseFormat(body.get("response_format"))
    );
  }

  /** Converts one Anthropic Messages request to the neutral runtime contract. */
  public GenerationRequest fromAnthropic(final JsonNode body, final String modelDefinitionId) {
    requireObject(body, "请求体必须是 JSON 对象");
    return new GenerationRequest(
        modelDefinitionId,
        anthropicSystem(body.get("system")),
        anthropicMessages(body.path("messages")),
        requiredPositiveInteger(body, "max_tokens"),
        optionalDouble(body, "temperature"),
        optionalDouble(body, "top_p"),
        anthropicTools(body.path("tools")),
        null
    );
  }

  /** Converts a completed neutral result to an OpenAI Chat Completions response. */
  public ObjectNode toOpenAiResponse(
      final GenerationResult result,
      final String requestedModel
  ) {
    ObjectNode response = mapper.createObjectNode();
    response.put("id", "chatcmpl-" + result.invocationId());
    response.put("object", "chat.completion");
    response.put("created", epoch(result.completedAt()));
    response.put("model", requestedModel);
    ObjectNode choice = response.putArray("choices").addObject();
    choice.put("index", 0);
    choice.put("finish_reason", openAiStopReason(result.stopReason()));
    ObjectNode message = choice.putObject("message");
    message.put("role", "assistant");
    appendOpenAiOutput(message, result.output());
    response.set("usage", openAiUsage(result.usage()));
    return response;
  }

  /** Converts a completed neutral result to an Anthropic Messages response. */
  public ObjectNode toAnthropicResponse(
      final GenerationResult result,
      final String requestedModel
  ) {
    ObjectNode response = mapper.createObjectNode();
    response.put("id", "msg_" + compactId(result.invocationId()));
    response.put("type", "message");
    response.put("role", "assistant");
    response.put("model", requestedModel);
    ArrayNode content = response.putArray("content");
    for (ContentBlock block : safe(result.output())) {
      if (block.type() == ContentType.TEXT || block.type() == ContentType.REFUSAL) {
        content.addObject().put("type", "text").put("text", safe(block.text()));
      } else if (block.type() == ContentType.TOOL_CALL) {
        ObjectNode tool = content.addObject();
        tool.put("type", "tool_use");
        tool.put("id", block.toolCallId());
        tool.put("name", block.toolName());
        tool.set("input", parseObject(block.argumentsJson(), "工具调用参数"));
      }
    }
    response.put("stop_reason", anthropicStopReason(result.stopReason()));
    response.putNull("stop_sequence");
    response.set("usage", anthropicUsage(result.usage()));
    return response;
  }

  /** Builds an OpenAI-compatible error envelope. */
  public ObjectNode openAiError(final String message, final String type, final String code) {
    ObjectNode response = mapper.createObjectNode();
    ObjectNode error = response.putObject("error");
    error.put("message", safe(message));
    error.put("type", type);
    error.putNull("param");
    error.put("code", code);
    return response;
  }

  /** Builds an Anthropic-compatible error envelope. */
  public ObjectNode anthropicError(final String message, final String type) {
    ObjectNode response = mapper.createObjectNode();
    response.put("type", "error");
    ObjectNode error = response.putObject("error");
    error.put("type", type);
    error.put("message", safe(message));
    return response;
  }

  /** Builds the invariant fields of an OpenAI streaming chunk. */
  public ObjectNode openAiChunkBase(
      final String invocationId,
      final String requestedModel,
      final long created
  ) {
    ObjectNode chunk = mapper.createObjectNode();
    chunk.put("id", "chatcmpl-" + invocationId);
    chunk.put("object", "chat.completion.chunk");
    chunk.put("created", created);
    chunk.put("model", requestedModel);
    return chunk;
  }

  /** Maps neutral token usage to the OpenAI usage shape. */
  public ObjectNode openAiUsage(final TokenUsage usage) {
    TokenUsage value = usage == null ? new TokenUsage(0, 0, 0, 0) : usage;
    ObjectNode node = mapper.createObjectNode();
    node.put("prompt_tokens", integer(value.inputTokens()));
    node.put("completion_tokens", integer(value.outputTokens()));
    node.put("total_tokens", integer(value.totalTokens()));
    if (value.cachedInputTokens() != null) {
      node.putObject("prompt_tokens_details")
          .put("cached_tokens", integer(value.cachedInputTokens()));
    }
    return node;
  }

  /** Maps neutral token usage to the Anthropic usage shape. */
  public ObjectNode anthropicUsage(final TokenUsage usage) {
    TokenUsage value = usage == null ? new TokenUsage(0, 0, 0, 0) : usage;
    ObjectNode node = mapper.createObjectNode();
    node.put("input_tokens", integer(value.inputTokens()));
    node.put("output_tokens", integer(value.outputTokens()));
    if (value.cachedInputTokens() != null) {
      node.put("cache_read_input_tokens", integer(value.cachedInputTokens()));
    }
    return node;
  }

  /** Exposes the configured mapper for protocol event assembly. */
  public ObjectMapper objectMapper() {
    return mapper;
  }

  /** Maps a neutral stop reason to its OpenAI equivalent. */
  public static String openAiStopReason(final String reason) {
    String normalized = normalize(reason);
    if (normalized == null) {
      return "stop";
    }
    return switch (normalized.toLowerCase(Locale.ROOT)) {
      case "tool_use", "tool_calls", "function_call" -> "tool_calls";
      case "max_tokens", "length" -> "length";
      case "content_filter" -> "content_filter";
      default -> "stop";
    };
  }

  /** Maps a neutral stop reason to its Anthropic equivalent. */
  public static String anthropicStopReason(final String reason) {
    String normalized = normalize(reason);
    if (normalized == null) {
      return "end_turn";
    }
    return switch (normalized.toLowerCase(Locale.ROOT)) {
      case "tool_use", "tool_calls", "function_call" -> "tool_use";
      case "max_tokens", "length" -> "max_tokens";
      case "stop_sequence" -> "stop_sequence";
      default -> "end_turn";
    };
  }

  private List<Message> openAiMessages(final JsonNode messagesNode) {
    requireArray(messagesNode, "messages 必须是数组");
    List<Message> messages = new ArrayList<>();
    for (JsonNode item : messagesNode) {
      requireObject(item, "messages 中的元素必须是对象");
      String roleText = requireText(item, "role");
      MessageRole role = switch (roleText.toLowerCase(Locale.ROOT)) {
        case "system", "developer" -> MessageRole.SYSTEM;
        case "user" -> MessageRole.USER;
        case "assistant" -> MessageRole.ASSISTANT;
        case "tool" -> MessageRole.TOOL;
        default -> throw new IllegalArgumentException("不支持的消息角色: " + roleText);
      };
      List<ContentBlock> blocks = new ArrayList<>();
      if (role == MessageRole.TOOL) {
        blocks.add(block(ContentType.TOOL_RESULT, textContent(item.get("content")), null,
            item.path("tool_call_id").asText(null), null, null));
      } else {
        blocks.addAll(openAiContent(item.get("content")));
        if (item.path("tool_calls").isArray()) {
          for (JsonNode call : item.path("tool_calls")) {
            JsonNode function = call.path("function");
            blocks.add(block(
                ContentType.TOOL_CALL, null, null,
                requireText(call, "id"), requireText(function, "name"),
                function.path("arguments").asText("{}")
            ));
          }
        }
      }
      messages.add(new Message(role, blocks));
    }
    return messages;
  }

  private List<ContentBlock> openAiContent(final JsonNode content) {
    List<ContentBlock> blocks = new ArrayList<>();
    if (content == null || content.isNull() || content.isMissingNode()) {
      return blocks;
    }
    if (content.isTextual()) {
      blocks.add(block(ContentType.TEXT, content.asText(), null, null, null, null));
      return blocks;
    }
    requireArray(content, "message.content 必须是字符串或数组");
    for (JsonNode part : content) {
      String type = requireText(part, "type");
      if ("text".equals(type) || "input_text".equals(type)) {
        blocks.add(block(ContentType.TEXT, requireText(part, "text"), null, null, null, null));
      } else if ("image_url".equals(type)) {
        String url = part.path("image_url").isTextual()
            ? part.path("image_url").asText() : part.path("image_url").path("url").asText(null);
        blocks.add(block(ContentType.IMAGE_URL, null, requireValue(url, "image_url.url 不能为空"),
            null, null, null));
      } else {
        throw new IllegalArgumentException("不支持的 OpenAI 内容类型: " + type);
      }
    }
    return blocks;
  }

  private List<Message> anthropicMessages(final JsonNode messagesNode) {
    requireArray(messagesNode, "messages 必须是数组");
    List<Message> messages = new ArrayList<>();
    for (JsonNode item : messagesNode) {
      requireObject(item, "messages 中的元素必须是对象");
      String roleValue = requireText(item, "role").toLowerCase(Locale.ROOT);
      MessageRole role = switch (roleValue) {
        case "assistant" -> MessageRole.ASSISTANT;
        case "user" -> MessageRole.USER;
        default -> throw new IllegalArgumentException("不支持的 Anthropic 消息角色: " + roleValue);
      };
      JsonNode content = item.get("content");
      if (content != null && content.isTextual()) {
        messages.add(new Message(role, List.of(block(
            ContentType.TEXT, content.asText(), null, null, null, null))));
        continue;
      }
      requireArray(content, "message.content 必须是字符串或数组");
      List<ContentBlock> normalBlocks = new ArrayList<>();
      List<ContentBlock> toolResults = new ArrayList<>();
      for (JsonNode part : content) {
        String type = requireText(part, "type");
        switch (type) {
          case "text" -> normalBlocks.add(block(
              ContentType.TEXT, requireText(part, "text"), null, null, null, null));
          case "image" -> normalBlocks.add(anthropicImage(part.path("source")));
          case "tool_use" -> normalBlocks.add(block(
              ContentType.TOOL_CALL, null, null,
              requireText(part, "id"), requireText(part, "name"), json(part.path("input"))));
          case "tool_result" -> toolResults.add(block(
              ContentType.TOOL_RESULT, textContent(part.get("content")), null,
              requireText(part, "tool_use_id"), null, null));
          default -> throw new IllegalArgumentException("不支持的 Anthropic 内容类型: " + type);
        }
      }
      if (!normalBlocks.isEmpty()) {
        messages.add(new Message(role, normalBlocks));
      }
      if (!toolResults.isEmpty()) {
        messages.add(new Message(MessageRole.TOOL, toolResults));
      }
    }
    return messages;
  }

  private ContentBlock anthropicImage(final JsonNode source) {
    String type = requireText(source, "type");
    if ("url".equals(type)) {
      return block(ContentType.IMAGE_URL, null, requireText(source, "url"), null, null, null);
    }
    if ("base64".equals(type)) {
      String mediaType = requireText(source, "media_type");
      String data = requireText(source, "data");
      return new ContentBlock(ContentType.IMAGE_URL, null,
          "data:" + mediaType + ";base64," + data, mediaType, null, null, null);
    }
    throw new IllegalArgumentException("不支持的 Anthropic 图片来源: " + type);
  }

  private List<ToolDefinition> openAiTools(final JsonNode toolsNode) {
    if (toolsNode == null || toolsNode.isMissingNode() || toolsNode.isNull()) {
      return List.of();
    }
    requireArray(toolsNode, "tools 必须是数组");
    List<ToolDefinition> tools = new ArrayList<>();
    for (JsonNode tool : toolsNode) {
      if (!"function".equals(tool.path("type").asText("function"))) {
        continue;
      }
      JsonNode function = tool.path("function");
      tools.add(new ToolDefinition(
          requireText(function, "name"),
          function.path("description").asText(null),
          json(function.path("parameters")),
          function.has("strict") ? function.path("strict").asBoolean() : null
      ));
    }
    return tools;
  }

  private List<ToolDefinition> anthropicTools(final JsonNode toolsNode) {
    if (toolsNode == null || toolsNode.isMissingNode() || toolsNode.isNull()) {
      return List.of();
    }
    requireArray(toolsNode, "tools 必须是数组");
    List<ToolDefinition> tools = new ArrayList<>();
    for (JsonNode tool : toolsNode) {
      tools.add(new ToolDefinition(
          requireText(tool, "name"),
          tool.path("description").asText(null),
          json(tool.path("input_schema")),
          null
      ));
    }
    return tools;
  }

  private ResponseFormat openAiResponseFormat(final JsonNode format) {
    if (format == null || format.isNull() || format.isMissingNode()) {
      return null;
    }
    String type = format.path("type").asText("text");
    if ("text".equals(type)) {
      return new ResponseFormat(ResponseFormatType.TEXT, null, null, null, null);
    }
    if ("json_object".equals(type)) {
      return new ResponseFormat(ResponseFormatType.JSON_OBJECT, null, null, null, null);
    }
    if ("json_schema".equals(type)) {
      JsonNode definition = format.path("json_schema");
      return new ResponseFormat(
          ResponseFormatType.JSON_SCHEMA,
          definition.path("name").asText("response"),
          definition.path("description").asText(null),
          json(definition.path("schema")),
          definition.has("strict") ? definition.path("strict").asBoolean() : null
      );
    }
    throw new IllegalArgumentException("不支持的 response_format.type: " + type);
  }

  private String anthropicSystem(final JsonNode system) {
    if (system == null || system.isNull() || system.isMissingNode()) {
      return null;
    }
    return textContent(system);
  }

  private void appendOpenAiOutput(final ObjectNode message, final List<ContentBlock> output) {
    StringBuilder text = new StringBuilder();
    ArrayNode calls = null;
    for (ContentBlock block : safe(output)) {
      if (block.type() == ContentType.TEXT || block.type() == ContentType.REFUSAL) {
        text.append(safe(block.text()));
      } else if (block.type() == ContentType.TOOL_CALL) {
        if (calls == null) {
          calls = message.putArray("tool_calls");
        }
        ObjectNode call = calls.addObject();
        call.put("id", block.toolCallId());
        call.put("type", "function");
        call.putObject("function")
            .put("name", block.toolName())
            .put("arguments", defaultJson(block.argumentsJson()));
      }
    }
    if (!text.isEmpty()) {
      message.put("content", text.toString());
    } else {
      message.putNull("content");
    }
  }

  private String textContent(final JsonNode content) {
    if (content == null || content.isNull() || content.isMissingNode()) {
      return "";
    }
    if (content.isTextual()) {
      return content.asText();
    }
    requireArray(content, "文本内容必须是字符串或数组");
    StringBuilder text = new StringBuilder();
    for (JsonNode part : content) {
      if (part.isTextual()) {
        text.append(part.asText());
      } else if ("text".equals(part.path("type").asText())) {
        text.append(part.path("text").asText(""));
      }
    }
    return text.toString();
  }

  private ObjectNode parseObject(final String value, final String field) {
    try {
      JsonNode parsed = mapper.readTree(defaultJson(value));
      if (!parsed.isObject()) {
        throw new IllegalArgumentException(field + "必须是 JSON 对象");
      }
      return (ObjectNode) parsed;
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(field + "不是有效 JSON", ex);
    }
  }

  private String json(final JsonNode value) {
    try {
      return value == null || value.isMissingNode() || value.isNull()
          ? "{}" : mapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("无法读取 JSON 参数", ex);
    }
  }

  private static ContentBlock block(
      final ContentType type,
      final String text,
      final String url,
      final String toolCallId,
      final String toolName,
      final String arguments
  ) {
    return new ContentBlock(type, text, url, null, toolCallId, toolName, arguments);
  }

  private static int requiredPositiveInteger(final JsonNode body, final String field) {
    JsonNode value = body.get(field);
    if (value == null || !value.canConvertToInt() || value.asInt() <= 0) {
      throw new IllegalArgumentException(field + " 必须是正整数");
    }
    return value.asInt();
  }

  private static Integer firstInteger(final JsonNode body, final String... fields) {
    for (String field : fields) {
      JsonNode value = body.get(field);
      if (value != null && !value.isNull()) {
        if (!value.canConvertToInt()) {
          throw new IllegalArgumentException(field + " 必须是整数");
        }
        return value.asInt();
      }
    }
    return null;
  }

  private static Double optionalDouble(final JsonNode body, final String field) {
    JsonNode value = body.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isNumber()) {
      throw new IllegalArgumentException(field + " 必须是数字");
    }
    return value.asDouble();
  }

  private static String requireText(final JsonNode node, final String field) {
    return requireValue(node == null ? null : node.path(field).asText(null), field + " 不能为空");
  }

  private static String requireValue(final String value, final String message) {
    String normalized = normalize(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static void requireObject(final JsonNode value, final String message) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(message);
    }
  }

  private static void requireArray(final JsonNode value, final String message) {
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException(message);
    }
  }

  private static long epoch(final Instant value) {
    return value == null ? Instant.now().getEpochSecond() : value.getEpochSecond();
  }

  private static int integer(final Integer value) {
    return value == null ? 0 : value;
  }

  private static String compactId(final String value) {
    return safe(value).replace("-", "");
  }

  private static String defaultJson(final String value) {
    return normalize(value) == null ? "{}" : value;
  }

  private static String normalize(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static String safe(final String value) {
    return value == null ? "" : value;
  }

  private static <T> List<T> safe(final List<T> values) {
    return values == null ? List.of() : values;
  }
}
