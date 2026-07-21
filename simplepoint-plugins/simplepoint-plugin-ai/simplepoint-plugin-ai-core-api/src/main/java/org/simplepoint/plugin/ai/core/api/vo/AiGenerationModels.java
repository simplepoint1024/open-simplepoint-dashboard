package org.simplepoint.plugin.ai.core.api.vo;

import java.time.Instant;
import java.util.List;

/**
 * Provider-neutral contracts for text generation, tools, structured output, and streaming.
 */
public final class AiGenerationModels {

  private AiGenerationModels() {
  }

  /** Roles supported by the normalized conversation protocol. */
  public enum MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
  }

  /** Content variants supported by normalized messages and results. */
  public enum ContentType {
    TEXT,
    IMAGE_URL,
    REFUSAL,
    TOOL_CALL,
    TOOL_RESULT
  }

  /** Requested response representation. */
  public enum ResponseFormatType {
    TEXT,
    JSON_OBJECT,
    JSON_SCHEMA
  }

  /** Normalized stream event types. */
  public enum EventType {
    STARTED,
    TEXT_DELTA,
    REFUSAL_DELTA,
    TOOL_CALL_STARTED,
    TOOL_ARGUMENTS_DELTA,
    USAGE,
    COMPLETED,
    ERROR
  }

  /** One content block in a message or generation result. */
  public record ContentBlock(
      ContentType type,
      String text,
      String url,
      String mimeType,
      String toolCallId,
      String toolName,
      String argumentsJson
  ) {
  }

  /** One normalized conversation message. */
  public record Message(MessageRole role, List<ContentBlock> content) {
  }

  /** Tool callable by the model. */
  public record ToolDefinition(
      String name,
      String description,
      String inputSchemaJson,
      Boolean strict
  ) {
  }

  /** Optional response format constraint. */
  public record ResponseFormat(
      ResponseFormatType type,
      String name,
      String description,
      String jsonSchema,
      Boolean strict
  ) {
  }

  /** Provider-neutral generation request. */
  public record GenerationRequest(
      String modelDefinitionId,
      String instructions,
      List<Message> messages,
      Integer maxOutputTokens,
      Double temperature,
      Double topP,
      List<ToolDefinition> tools,
      ResponseFormat responseFormat
  ) {
  }

  /** Normalized token accounting. */
  public record TokenUsage(
      Integer inputTokens,
      Integer outputTokens,
      Integer totalTokens,
      Integer cachedInputTokens
  ) {
  }

  /** Completed normalized generation. */
  public record GenerationResult(
      String invocationId,
      String modelDefinitionId,
      String modelId,
      String providerRequestId,
      List<ContentBlock> output,
      String stopReason,
      TokenUsage usage,
      long durationMillis,
      Instant completedAt
  ) {
  }

  /** One normalized server-sent generation event. */
  public record GenerationEvent(
      String invocationId,
      long sequence,
      EventType type,
      String textDelta,
      String toolCallId,
      String toolName,
      String argumentsDelta,
      TokenUsage usage,
      GenerationResult result,
      String errorCode,
      String errorMessage
  ) {
  }
}
