package org.simplepoint.plugin.ai.core.rest.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.EventType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationEvent;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.Message;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.MessageRole;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ResponseFormat;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ResponseFormatType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.TokenUsage;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ToolDefinition;
import org.springframework.stereotype.Component;

/** Maps the stateless OpenAI Responses API to the provider-neutral generation runtime. */
@Component
public class OpenAiResponsesProtocol {

  private final ObjectMapper mapper;

  /** Creates the protocol mapper with the application's configured Jackson mapper. */
  public OpenAiResponsesProtocol(final ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** Converts a Responses API request into the provider-neutral request contract. */
  public GenerationRequest fromRequest(final JsonNode body, final String modelDefinitionId) {
    requireObject(body, "请求体必须是 JSON 对象");
    validateStatelessOptions(body);
    List<ToolDefinition> tools = tools(body.get("tools"));
    String toolChoice = toolChoice(body.get("tool_choice"));
    if ("none".equals(toolChoice)) {
      tools = List.of();
    }
    return new GenerationRequest(
        modelDefinitionId,
        optionalText(body, "instructions"),
        input(body.get("input")),
        optionalInteger(body, "max_output_tokens"),
        optionalDouble(body, "temperature"),
        optionalDouble(body, "top_p"),
        tools,
        responseFormat(body.path("text").get("format"))
    );
  }

  /** Converts a completed neutral result into an OpenAI Responses API response. */
  public ObjectNode toResponse(
      final GenerationResult result,
      final String requestedModel,
      final JsonNode request
  ) {
    ResponseState state = new ResponseState(requestedModel, request, epoch(result.completedAt()));
    state.captureInvocation(result.invocationId());
    state.synchronize(result);
    return response(state, result);
  }

  /** Creates state for translating neutral streaming events into Responses API SSE events. */
  public StreamState newStreamState(final String requestedModel, final JsonNode request) {
    return new StreamState(new ResponseState(
        requestedModel,
        request == null ? mapper.createObjectNode() : request.deepCopy(),
        Instant.now().getEpochSecond()
    ));
  }

  /** Translates one neutral generation event into zero or more Responses API SSE events. */
  public List<StreamEvent> streamEvents(
      final StreamState stream,
      final GenerationEvent generationEvent
  ) {
    if (stream == null || generationEvent == null || generationEvent.type() == null) {
      return List.of();
    }
    ResponseState state = stream.response;
    state.captureInvocation(generationEvent.invocationId());
    return switch (generationEvent.type()) {
      case STARTED -> startedEvents(state);
      case TEXT_DELTA -> textDeltaEvents(state, generationEvent.textDelta());
      case REFUSAL_DELTA -> refusalDeltaEvents(state, generationEvent.textDelta());
      case TOOL_CALL_STARTED -> toolStartedEvents(
          state, generationEvent.toolCallId(), generationEvent.toolName());
      case TOOL_ARGUMENTS_DELTA -> toolDeltaEvents(
          state, generationEvent.toolCallId(), generationEvent.argumentsDelta());
      case COMPLETED -> completedEvents(state, generationEvent.result());
      case ERROR -> List.of(errorEvent(
          state, generationEvent.errorCode(), generationEvent.errorMessage()));
      case USAGE -> List.of();
    };
  }

  private List<StreamEvent> startedEvents(final ResponseState state) {
    ObjectNode created = event(state, "response.created");
    created.set("response", response(state, null));
    ObjectNode inProgress = event(state, "response.in_progress");
    inProgress.set("response", response(state, null));
    return List.of(
        new StreamEvent("response.created", created),
        new StreamEvent("response.in_progress", inProgress)
    );
  }

  private List<StreamEvent> textDeltaEvents(
      final ResponseState state,
      final String delta
  ) {
    List<StreamEvent> events = new ArrayList<>();
    ensureMessageStarted(state, events);
    if (!state.textStarted) {
      state.textStarted = true;
      state.textContentIndex = state.nextContentIndex++;
      ObjectNode added = event(state, "response.content_part.added");
      added.put("item_id", state.messageId());
      added.put("output_index", state.messageIndex);
      added.put("content_index", state.textContentIndex);
      added.set("part", outputTextPart(""));
      events.add(new StreamEvent("response.content_part.added", added));
    }
    String value = safe(delta);
    state.text.append(value);
    ObjectNode payload = event(state, "response.output_text.delta");
    payload.put("item_id", state.messageId());
    payload.put("output_index", state.messageIndex);
    payload.put("content_index", state.textContentIndex);
    payload.put("delta", value);
    events.add(new StreamEvent("response.output_text.delta", payload));
    return List.copyOf(events);
  }

  private List<StreamEvent> refusalDeltaEvents(
      final ResponseState state,
      final String delta
  ) {
    List<StreamEvent> events = new ArrayList<>();
    ensureMessageStarted(state, events);
    if (!state.refusalStarted) {
      state.refusalStarted = true;
      state.refusalContentIndex = state.nextContentIndex++;
      ObjectNode added = event(state, "response.content_part.added");
      added.put("item_id", state.messageId());
      added.put("output_index", state.messageIndex);
      added.put("content_index", state.refusalContentIndex);
      added.set("part", refusalPart(""));
      events.add(new StreamEvent("response.content_part.added", added));
    }
    String value = safe(delta);
    state.refusal.append(value);
    ObjectNode payload = event(state, "response.refusal.delta");
    payload.put("item_id", state.messageId());
    payload.put("output_index", state.messageIndex);
    payload.put("content_index", state.refusalContentIndex);
    payload.put("delta", value);
    events.add(new StreamEvent("response.refusal.delta", payload));
    return List.copyOf(events);
  }

  private List<StreamEvent> toolStartedEvents(
      final ResponseState state,
      final String toolCallId,
      final String toolName
  ) {
    ToolState tool = state.tool(toolCallId, toolName);
    if (tool.started) {
      return List.of();
    }
    tool.started = true;
    ObjectNode added = event(state, "response.output_item.added");
    added.put("output_index", tool.outputIndex);
    added.set("item", functionItem(tool, "in_progress"));
    return List.of(new StreamEvent("response.output_item.added", added));
  }

  private List<StreamEvent> toolDeltaEvents(
      final ResponseState state,
      final String toolCallId,
      final String delta
  ) {
    final List<StreamEvent> events = new ArrayList<>(
        toolStartedEvents(state, toolCallId, null));
    ToolState tool = state.tool(toolCallId, null);
    String value = safe(delta);
    tool.arguments.append(value);
    ObjectNode payload = event(state, "response.function_call_arguments.delta");
    payload.put("item_id", tool.itemId());
    payload.put("output_index", tool.outputIndex);
    payload.put("delta", value);
    events.add(new StreamEvent("response.function_call_arguments.delta", payload));
    return List.copyOf(events);
  }

  private List<StreamEvent> completedEvents(
      final ResponseState state,
      final GenerationResult result
  ) {
    List<StreamEvent> events = new ArrayList<>();
    synchronizeMissingOutput(state, result, events);
    if (state.textStarted) {
      ObjectNode textDone = event(state, "response.output_text.done");
      textDone.put("item_id", state.messageId());
      textDone.put("output_index", state.messageIndex);
      textDone.put("content_index", state.textContentIndex);
      textDone.put("text", state.text.toString());
      events.add(new StreamEvent("response.output_text.done", textDone));
      events.add(contentPartDone(
          state, state.textContentIndex, outputTextPart(state.text.toString())));
    }
    if (state.refusalStarted) {
      ObjectNode refusalDone = event(state, "response.refusal.done");
      refusalDone.put("item_id", state.messageId());
      refusalDone.put("output_index", state.messageIndex);
      refusalDone.put("content_index", state.refusalContentIndex);
      refusalDone.put("refusal", state.refusal.toString());
      events.add(new StreamEvent("response.refusal.done", refusalDone));
      events.add(contentPartDone(
          state, state.refusalContentIndex, refusalPart(state.refusal.toString())));
    }
    state.tools.values().stream()
        .sorted(Comparator.comparingInt(tool -> tool.outputIndex))
        .forEach(tool -> completeTool(state, tool, events));
    if (state.messageStarted) {
      ObjectNode done = event(state, "response.output_item.done");
      done.put("output_index", state.messageIndex);
      done.set("item", messageItem(state, "completed"));
      events.add(new StreamEvent("response.output_item.done", done));
    }
    ObjectNode terminal = event(state, terminalEventType(result));
    terminal.set("response", response(state, result));
    events.add(new StreamEvent(terminal.path("type").asText(), terminal));
    return List.copyOf(events);
  }

  private void synchronizeMissingOutput(
      final ResponseState state,
      final GenerationResult result,
      final List<StreamEvent> events
  ) {
    if (result == null) {
      return;
    }
    StringBuilder finalText = new StringBuilder();
    StringBuilder finalRefusal = new StringBuilder();
    for (ContentBlock block : safe(result.output())) {
      if (block == null || block.type() == null) {
        continue;
      }
      switch (block.type()) {
        case TEXT -> finalText.append(safe(block.text()));
        case REFUSAL -> finalRefusal.append(safe(block.text()));
        case TOOL_CALL -> {
          ToolState tool = state.tool(block.toolCallId(), block.toolName());
          if (!tool.started) {
            events.addAll(toolStartedEvents(state, block.toolCallId(), block.toolName()));
          }
          tool.name = firstNonBlank(block.toolName(), tool.name);
          tool.arguments.setLength(0);
          tool.arguments.append(defaultJson(block.argumentsJson()));
        }
        default -> {
          // Response output only contains assistant messages and function calls.
        }
      }
    }
    if (!finalText.isEmpty()) {
      if (!state.textStarted) {
        events.addAll(textDeltaEvents(state, finalText.toString()));
      } else {
        replaceIfComplete(state.text, finalText.toString());
      }
    }
    if (!finalRefusal.isEmpty()) {
      if (!state.refusalStarted) {
        events.addAll(refusalDeltaEvents(state, finalRefusal.toString()));
      } else {
        replaceIfComplete(state.refusal, finalRefusal.toString());
      }
    }
  }

  private void completeTool(
      final ResponseState state,
      final ToolState tool,
      final List<StreamEvent> events
  ) {
    ObjectNode argumentsDone = event(state, "response.function_call_arguments.done");
    argumentsDone.put("item_id", tool.itemId());
    argumentsDone.put("output_index", tool.outputIndex);
    argumentsDone.put("arguments", tool.arguments.toString());
    events.add(new StreamEvent("response.function_call_arguments.done", argumentsDone));
    ObjectNode itemDone = event(state, "response.output_item.done");
    itemDone.put("output_index", tool.outputIndex);
    itemDone.set("item", functionItem(tool, "completed"));
    events.add(new StreamEvent("response.output_item.done", itemDone));
  }

  private StreamEvent contentPartDone(
      final ResponseState state,
      final int contentIndex,
      final ObjectNode part
  ) {
    ObjectNode done = event(state, "response.content_part.done");
    done.put("item_id", state.messageId());
    done.put("output_index", state.messageIndex);
    done.put("content_index", contentIndex);
    done.set("part", part);
    return new StreamEvent("response.content_part.done", done);
  }

  private StreamEvent errorEvent(
      final ResponseState state,
      final String code,
      final String message
  ) {
    ObjectNode error = event(state, "error");
    error.put("code", firstNonBlank(code, "server_error"));
    error.put("message", firstNonBlank(message, "模型生成失败"));
    error.putNull("param");
    return new StreamEvent("error", error);
  }

  private void ensureMessageStarted(
      final ResponseState state,
      final List<StreamEvent> events
  ) {
    if (state.messageStarted) {
      return;
    }
    state.messageStarted = true;
    state.messageIndex = state.nextOutputIndex++;
    ObjectNode added = event(state, "response.output_item.added");
    added.put("output_index", state.messageIndex);
    added.set("item", messageItem(state, "in_progress"));
    events.add(new StreamEvent("response.output_item.added", added));
  }

  private ObjectNode response(
      final ResponseState state,
      final GenerationResult result
  ) {
    boolean terminal = result != null;
    boolean incomplete = terminal && incomplete(result.stopReason());
    ObjectNode response = mapper.createObjectNode();
    response.put("id", state.responseId());
    response.put("object", "response");
    response.put("created_at", state.createdAt);
    response.put("status", terminal ? (incomplete ? "incomplete" : "completed") : "in_progress");
    if (terminal && !incomplete) {
      response.put("completed_at", epoch(result.completedAt()));
    } else {
      response.putNull("completed_at");
    }
    response.put("background", false);
    response.putNull("error");
    if (incomplete) {
      response.putObject("incomplete_details").put("reason", "max_output_tokens");
    } else {
      response.putNull("incomplete_details");
    }
    copyOrNull(response, "instructions", state.request.get("instructions"));
    copyOrNull(response, "max_output_tokens", state.request.get("max_output_tokens"));
    response.putNull("max_tool_calls");
    response.put("model", state.requestedModel);
    ArrayNode output = response.putArray("output");
    if (terminal) {
      state.outputItems().forEach(output::add);
    }
    response.put("parallel_tool_calls", state.request.path("parallel_tool_calls").asBoolean(true));
    response.putNull("previous_response_id");
    response.set("reasoning", reasoning(state.request.get("reasoning")));
    response.put("store", false);
    response.put("temperature", state.request.path("temperature").asDouble(1.0D));
    response.set("text", textConfiguration(state.request.get("text")));
    response.set("tool_choice", toolChoiceResponse(state.request.get("tool_choice")));
    response.set("tools", responseTools(state.request.get("tools")));
    response.put("top_p", state.request.path("top_p").asDouble(1.0D));
    response.put("truncation", state.request.path("truncation").asText("disabled"));
    if (terminal) {
      response.set("usage", usage(result.usage()));
    } else {
      response.putNull("usage");
    }
    response.putNull("user");
    JsonNode metadata = state.request.get("metadata");
    response.set("metadata", metadata != null && metadata.isObject()
        ? metadata.deepCopy() : mapper.createObjectNode());
    return response;
  }

  private ObjectNode messageItem(final ResponseState state, final String status) {
    ObjectNode message = mapper.createObjectNode();
    message.put("id", state.messageId());
    message.put("type", "message");
    message.put("status", status);
    message.put("role", "assistant");
    ArrayNode content = message.putArray("content");
    if ("completed".equals(status)) {
      if (state.textStarted) {
        content.add(outputTextPart(state.text.toString()));
      }
      if (state.refusalStarted) {
        content.add(refusalPart(state.refusal.toString()));
      }
    }
    return message;
  }

  private ObjectNode functionItem(final ToolState tool, final String status) {
    ObjectNode item = mapper.createObjectNode();
    item.put("id", tool.itemId());
    item.put("type", "function_call");
    item.put("status", status);
    item.put("call_id", tool.callId);
    item.put("name", safe(tool.name));
    item.put("arguments", tool.arguments.toString());
    return item;
  }

  private ObjectNode outputTextPart(final String text) {
    ObjectNode part = mapper.createObjectNode();
    part.put("type", "output_text");
    part.put("text", safe(text));
    part.putArray("annotations");
    part.putArray("logprobs");
    return part;
  }

  private ObjectNode refusalPart(final String refusal) {
    return mapper.createObjectNode()
        .put("type", "refusal")
        .put("refusal", safe(refusal));
  }

  private ObjectNode usage(final TokenUsage usage) {
    TokenUsage value = usage == null ? new TokenUsage(0, 0, 0, 0) : usage;
    int inputTokens = integer(value.inputTokens());
    int outputTokens = integer(value.outputTokens());
    ObjectNode node = mapper.createObjectNode();
    node.put("input_tokens", inputTokens);
    node.putObject("input_tokens_details")
        .put("cached_tokens", integer(value.cachedInputTokens()));
    node.put("output_tokens", outputTokens);
    node.putObject("output_tokens_details").put("reasoning_tokens", 0);
    node.put("total_tokens", value.totalTokens() == null
        ? inputTokens + outputTokens : value.totalTokens());
    return node;
  }

  private ObjectNode reasoning(final JsonNode requested) {
    ObjectNode reasoning = mapper.createObjectNode();
    if (requested != null && requested.isObject() && requested.hasNonNull("effort")) {
      reasoning.set("effort", requested.get("effort").deepCopy());
    } else {
      reasoning.putNull("effort");
    }
    if (requested != null && requested.isObject() && requested.hasNonNull("summary")) {
      reasoning.set("summary", requested.get("summary").deepCopy());
    } else {
      reasoning.putNull("summary");
    }
    return reasoning;
  }

  private ObjectNode textConfiguration(final JsonNode requested) {
    ObjectNode text = requested != null && requested.isObject()
        ? requested.deepCopy() : mapper.createObjectNode();
    if (!text.path("format").isObject()) {
      text.putObject("format").put("type", "text");
    }
    return text;
  }

  private JsonNode toolChoiceResponse(final JsonNode requested) {
    if (requested == null || requested.isNull() || requested.isMissingNode()) {
      return mapper.getNodeFactory().textNode("auto");
    }
    return requested.deepCopy();
  }

  private ArrayNode responseTools(final JsonNode requested) {
    ArrayNode tools = mapper.createArrayNode();
    if (requested != null && requested.isArray()) {
      requested.forEach(tool -> tools.add(tool.deepCopy()));
    }
    return tools;
  }

  private ObjectNode event(final ResponseState state, final String type) {
    ObjectNode event = mapper.createObjectNode();
    event.put("type", type);
    event.put("sequence_number", state.nextSequence++);
    return event;
  }

  private List<Message> input(final JsonNode input) {
    if (input == null || input.isNull() || input.isMissingNode()) {
      throw new IllegalArgumentException("input 不能为空");
    }
    if (input.isTextual()) {
      return List.of(new Message(MessageRole.USER, List.of(block(
          ContentType.TEXT, input.asText(), null, null, null, null))));
    }
    requireArray(input, "input 必须是字符串或数组");
    List<Message> messages = new ArrayList<>();
    for (JsonNode item : input) {
      requireObject(item, "input 中的元素必须是对象");
      String type = item.path("type").asText("message");
      switch (type) {
        case "message" -> messages.add(inputMessage(item));
        case "function_call" -> messages.add(new Message(
            MessageRole.ASSISTANT,
            List.of(block(
                ContentType.TOOL_CALL, null, null,
                requireText(item, "call_id"), requireText(item, "name"),
                requiredJsonString(item, "arguments")))
        ));
        case "function_call_output" -> messages.add(new Message(
            MessageRole.TOOL,
            List.of(block(
                ContentType.TOOL_RESULT, textContent(item.get("output")), null,
                requireText(item, "call_id"), null, null))
        ));
        default -> throw new IllegalArgumentException("不支持的 Responses input 类型: " + type);
      }
    }
    return List.copyOf(messages);
  }

  private Message inputMessage(final JsonNode item) {
    String role = requireText(item, "role").toLowerCase(Locale.ROOT);
    MessageRole messageRole = switch (role) {
      case "system", "developer" -> MessageRole.SYSTEM;
      case "user" -> MessageRole.USER;
      case "assistant" -> MessageRole.ASSISTANT;
      default -> throw new IllegalArgumentException("不支持的 Responses 消息角色: " + role);
    };
    JsonNode content = item.get("content");
    if (content != null && content.isTextual()) {
      return new Message(messageRole, List.of(block(
          ContentType.TEXT, content.asText(), null, null, null, null)));
    }
    requireArray(content, "input message.content 必须是字符串或数组");
    List<ContentBlock> blocks = new ArrayList<>();
    for (JsonNode part : content) {
      requireObject(part, "input message.content 中的元素必须是对象");
      String type = requireText(part, "type");
      switch (type) {
        case "input_text", "output_text", "text" -> blocks.add(block(
            ContentType.TEXT, requireText(part, "text"), null, null, null, null));
        case "input_image", "image_url" -> blocks.add(inputImage(part));
        case "refusal" -> blocks.add(block(
            ContentType.REFUSAL, requireText(part, "refusal"), null, null, null, null));
        default -> throw new IllegalArgumentException("不支持的 Responses 内容类型: " + type);
      }
    }
    return new Message(messageRole, List.copyOf(blocks));
  }

  private ContentBlock inputImage(final JsonNode part) {
    JsonNode imageUrl = part.get("image_url");
    String url = imageUrl != null && imageUrl.isTextual()
        ? imageUrl.asText() : imageUrl == null ? null : imageUrl.path("url").asText(null);
    if (normalize(url) == null && part.hasNonNull("file_id")) {
      throw new IllegalArgumentException("当前网关暂不支持 input_image.file_id，请使用 image_url");
    }
    return block(ContentType.IMAGE_URL, null,
        requireValue(url, "input_image.image_url 不能为空"), null, null, null);
  }

  private List<ToolDefinition> tools(final JsonNode tools) {
    if (tools == null || tools.isNull() || tools.isMissingNode()) {
      return List.of();
    }
    requireArray(tools, "tools 必须是数组");
    List<ToolDefinition> definitions = new ArrayList<>();
    for (JsonNode tool : tools) {
      requireObject(tool, "tools 中的元素必须是对象");
      String type = requireText(tool, "type");
      if (!"function".equals(type)) {
        throw new IllegalArgumentException("当前网关仅支持 function 工具，不支持: " + type);
      }
      definitions.add(new ToolDefinition(
          requireText(tool, "name"),
          optionalText(tool, "description"),
          jsonObject(tool.get("parameters"), "tools.parameters"),
          tool.has("strict") ? tool.path("strict").asBoolean() : null
      ));
    }
    return List.copyOf(definitions);
  }

  private ResponseFormat responseFormat(final JsonNode format) {
    if (format == null || format.isNull() || format.isMissingNode()) {
      return null;
    }
    requireObject(format, "text.format 必须是对象");
    String type = format.path("type").asText("text");
    return switch (type) {
      case "text" -> new ResponseFormat(ResponseFormatType.TEXT, null, null, null, null);
      case "json_object" -> new ResponseFormat(
          ResponseFormatType.JSON_OBJECT, null, null, null, null);
      case "json_schema" -> new ResponseFormat(
          ResponseFormatType.JSON_SCHEMA,
          requireText(format, "name"),
          optionalText(format, "description"),
          jsonObject(format.get("schema"), "text.format.schema"),
          format.has("strict") ? format.path("strict").asBoolean() : null
      );
      default -> throw new IllegalArgumentException("不支持的 text.format.type: " + type);
    };
  }

  private String toolChoice(final JsonNode choice) {
    if (choice == null || choice.isNull() || choice.isMissingNode()) {
      return "auto";
    }
    if (!choice.isTextual()) {
      throw new IllegalArgumentException("当前网关暂不支持指定函数的 tool_choice");
    }
    String value = choice.asText();
    if (!"auto".equals(value) && !"none".equals(value)) {
      throw new IllegalArgumentException("当前网关的 tool_choice 仅支持 auto 或 none");
    }
    return value;
  }

  private void validateStatelessOptions(final JsonNode body) {
    validateOptionalBoolean(body, "stream");
    validateOptionalBoolean(body, "store");
    rejectPresent(body, "previous_response_id", "当前网关不保存响应正文，暂不支持 previous_response_id");
    rejectPresent(body, "conversation", "当前网关暂不支持 conversation");
    rejectPresent(body, "prompt", "当前网关暂不支持托管 prompt");
    rejectPresent(body, "max_tool_calls", "当前网关暂不支持 max_tool_calls");
    JsonNode background = body.get("background");
    if (background != null && !background.isNull() && !background.isBoolean()) {
      throw new IllegalArgumentException("background 必须是布尔值");
    }
    if (body.path("background").asBoolean(false)) {
      throw new IllegalArgumentException("当前网关暂不支持 background 响应");
    }
    JsonNode parallelToolCalls = body.get("parallel_tool_calls");
    if (parallelToolCalls != null && !parallelToolCalls.isNull()
        && !parallelToolCalls.isBoolean()) {
      throw new IllegalArgumentException("parallel_tool_calls 必须是布尔值");
    }
    if (parallelToolCalls != null && !parallelToolCalls.asBoolean()) {
      throw new IllegalArgumentException("当前网关暂不支持关闭 parallel_tool_calls");
    }
    String truncation = body.path("truncation").asText("disabled");
    if (!"disabled".equals(truncation)) {
      throw new IllegalArgumentException("当前网关的 truncation 仅支持 disabled");
    }
    JsonNode text = body.get("text");
    if (text != null && !text.isNull()) {
      requireObject(text, "text 必须是对象");
      rejectPresent(text, "verbosity", "当前网关暂不支持 text.verbosity");
    }
    rejectPresent(body, "top_logprobs", "当前网关暂不支持 top_logprobs");
    JsonNode reasoning = body.get("reasoning");
    if (reasoning != null && !reasoning.isNull()) {
      requireObject(reasoning, "reasoning 必须是对象");
      if (!reasoning.isEmpty()) {
        throw new IllegalArgumentException("当前网关暂不支持 reasoning 参数");
      }
    }
    JsonNode include = body.get("include");
    if (include != null && !include.isNull()) {
      requireArray(include, "include 必须是数组");
      if (!include.isEmpty()) {
        throw new IllegalArgumentException("当前网关暂不支持 include 参数");
      }
    }
  }

  private static void validateOptionalBoolean(final JsonNode body, final String field) {
    JsonNode value = body.get(field);
    if (value != null && !value.isNull() && !value.isBoolean()) {
      throw new IllegalArgumentException(field + " 必须是布尔值");
    }
  }

  private String textContent(final JsonNode content) {
    if (content == null || content.isNull() || content.isMissingNode()) {
      return "";
    }
    if (content.isTextual()) {
      return content.asText();
    }
    requireArray(content, "function_call_output.output 必须是字符串或数组");
    StringBuilder text = new StringBuilder();
    for (JsonNode part : content) {
      if (part.isTextual()) {
        text.append(part.asText());
      } else {
        String type = part.path("type").asText();
        if ("input_text".equals(type) || "output_text".equals(type)
            || "text".equals(type)) {
          text.append(part.path("text").asText(""));
        } else {
          throw new IllegalArgumentException("不支持的 function_call_output 内容类型: " + type);
        }
      }
    }
    return text.toString();
  }

  private String requiredJsonString(final JsonNode node, final String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " 必须是 JSON 字符串");
    }
    try {
      JsonNode parsed = mapper.readTree(value.asText());
      if (parsed == null || !parsed.isObject()) {
        throw new IllegalArgumentException(field + " 必须包含 JSON 对象");
      }
      return value.asText();
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(field + " 不是有效 JSON", ex);
    }
  }

  private String jsonObject(final JsonNode value, final String field) {
    JsonNode target = value == null || value.isNull() || value.isMissingNode()
        ? mapper.createObjectNode() : value;
    if (!target.isObject()) {
      throw new IllegalArgumentException(field + " 必须是 JSON 对象");
    }
    try {
      return mapper.writeValueAsString(target);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(field + " 不是有效 JSON", ex);
    }
  }

  private static String terminalEventType(final GenerationResult result) {
    return incomplete(result == null ? null : result.stopReason())
        ? "response.incomplete" : "response.completed";
  }

  private static boolean incomplete(final String stopReason) {
    String value = normalize(stopReason);
    return value != null && ("length".equalsIgnoreCase(value)
        || "max_tokens".equalsIgnoreCase(value)
        || "max_output_tokens".equalsIgnoreCase(value));
  }

  private static void replaceIfComplete(final StringBuilder target, final String completed) {
    if (!completed.isEmpty() && !completed.equals(target.toString())) {
      target.setLength(0);
      target.append(completed);
    }
  }

  private static void rejectPresent(
      final JsonNode body,
      final String field,
      final String message
  ) {
    if (body.has(field) && !body.path(field).isNull()) {
      throw new IllegalArgumentException(message);
    }
  }

  private static void copyOrNull(
      final ObjectNode target,
      final String field,
      final JsonNode value
  ) {
    if (value == null || value.isNull() || value.isMissingNode()) {
      target.putNull(field);
    } else {
      target.set(field, value.deepCopy());
    }
  }

  private static String optionalText(final JsonNode body, final String field) {
    JsonNode value = body == null ? null : body.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " 必须是字符串");
    }
    return normalize(value.asText());
  }

  private static Integer optionalInteger(final JsonNode body, final String field) {
    JsonNode value = body.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.canConvertToInt()) {
      throw new IllegalArgumentException(field + " 必须是整数");
    }
    return value.asInt();
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

  private static String firstNonBlank(final String first, final String second) {
    return normalize(first) == null ? safe(second) : first;
  }

  private static String defaultJson(final String value) {
    return normalize(value) == null ? "{}" : value;
  }

  private static int integer(final Integer value) {
    return value == null ? 0 : value;
  }

  private static long epoch(final Instant value) {
    return value == null ? Instant.now().getEpochSecond() : value.getEpochSecond();
  }

  private static String compactId(final String value) {
    String compact = safe(value).replaceAll("[^A-Za-z0-9]", "");
    return compact.isEmpty() ? "pending" : compact;
  }

  /** One named server-sent event in the OpenAI Responses API format. */
  public record StreamEvent(String name, ObjectNode data) {
  }

  /** Mutable state scoped to one streaming response. */
  public static final class StreamState {

    private final ResponseState response;

    private StreamState(final ResponseState response) {
      this.response = response;
    }
  }

  private final class ResponseState {

    private final String requestedModel;
    private final JsonNode request;
    private final long createdAt;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder refusal = new StringBuilder();
    private final Map<String, ToolState> tools = new LinkedHashMap<>();
    private String invocationId = "pending";
    private int nextSequence;
    private int nextOutputIndex;
    private int nextContentIndex;
    private int messageIndex = -1;
    private int textContentIndex = -1;
    private int refusalContentIndex = -1;
    private boolean messageStarted;
    private boolean textStarted;
    private boolean refusalStarted;

    private ResponseState(
        final String requestedModel,
        final JsonNode request,
        final long createdAt
    ) {
      this.requestedModel = requestedModel;
      this.request = request;
      this.createdAt = createdAt;
    }

    private void captureInvocation(final String value) {
      if (normalize(value) != null) {
        invocationId = value;
      }
    }

    private String responseId() {
      return "resp_" + compactId(invocationId);
    }

    private String messageId() {
      return "msg_" + compactId(invocationId);
    }

    private ToolState tool(final String callId, final String name) {
      String key = firstNonBlank(callId, "call_" + tools.size());
      ToolState tool = tools.computeIfAbsent(key, ignored -> new ToolState(
          nextOutputIndex++, key, name));
      tool.name = firstNonBlank(name, tool.name);
      return tool;
    }

    private void synchronize(final GenerationResult result) {
      if (result == null) {
        return;
      }
      for (ContentBlock block : safe(result.output())) {
        if (block == null || block.type() == null) {
          continue;
        }
        switch (block.type()) {
          case TEXT -> {
            ensureMessage();
            textStarted = true;
            text.append(safe(block.text()));
          }
          case REFUSAL -> {
            ensureMessage();
            refusalStarted = true;
            refusal.append(safe(block.text()));
          }
          case TOOL_CALL -> {
            ToolState tool = tool(block.toolCallId(), block.toolName());
            tool.started = true;
            tool.arguments.append(defaultJson(block.argumentsJson()));
          }
          default -> {
            // Input-only content is not part of a Responses output array.
          }
        }
      }
      if (textStarted) {
        textContentIndex = nextContentIndex++;
      }
      if (refusalStarted) {
        refusalContentIndex = nextContentIndex++;
      }
    }

    private void ensureMessage() {
      if (!messageStarted) {
        messageStarted = true;
        messageIndex = nextOutputIndex++;
      }
    }

    private List<ObjectNode> outputItems() {
      List<IndexedItem> items = new ArrayList<>();
      if (messageStarted) {
        items.add(new IndexedItem(messageIndex, messageItem(this, "completed")));
      }
      tools.values().forEach(tool -> items.add(new IndexedItem(
          tool.outputIndex, functionItem(tool, "completed"))));
      return items.stream()
          .sorted(Comparator.comparingInt(IndexedItem::index))
          .map(IndexedItem::item)
          .toList();
    }
  }

  private static final class ToolState {

    private final int outputIndex;
    private final String callId;
    private final StringBuilder arguments = new StringBuilder();
    private String name;
    private boolean started;

    private ToolState(final int outputIndex, final String callId, final String name) {
      this.outputIndex = outputIndex;
      this.callId = callId;
      this.name = name;
    }

    private String itemId() {
      return "fc_" + compactId(callId);
    }
  }

  private record IndexedItem(int index, ObjectNode item) {
  }
}
