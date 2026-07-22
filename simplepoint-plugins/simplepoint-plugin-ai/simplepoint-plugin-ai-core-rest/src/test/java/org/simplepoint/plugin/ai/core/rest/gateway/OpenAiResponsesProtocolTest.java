package org.simplepoint.plugin.ai.core.rest.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.EventType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationEvent;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.MessageRole;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ResponseFormatType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.TokenUsage;

class OpenAiResponsesProtocolTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final OpenAiResponsesProtocol protocol = new OpenAiResponsesProtocol(objectMapper);

  @Test
  void mapsResponsesInputToolsAndStructuredOutput() throws Exception {
    var body = objectMapper.readTree("""
        {
          "model": "public-model",
          "instructions": "Be concise",
          "input": [
            {"role": "user", "content": [
              {"type": "input_text", "text": "What is this?"},
              {"type": "input_image", "image_url": "https://example.com/image.png"}
            ]},
            {"type": "function_call", "call_id": "call_1", "name": "weather",
             "arguments": "{\\\"city\\\":\\\"Shanghai\\\"}"},
            {"type": "function_call_output", "call_id": "call_1", "output": "sunny"}
          ],
          "max_output_tokens": 256,
          "tools": [{"type": "function", "name": "weather",
            "description": "Get weather", "parameters": {"type": "object"}, "strict": true}],
          "text": {"format": {"type": "json_schema", "name": "answer",
            "schema": {"type": "object"}, "strict": true}}
        }
        """);

    var request = protocol.fromRequest(body, "definition-1");

    assertThat(request.modelDefinitionId()).isEqualTo("definition-1");
    assertThat(request.instructions()).isEqualTo("Be concise");
    assertThat(request.messages()).extracting(message -> message.role())
        .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL);
    assertThat(request.messages().getFirst().content()).extracting(ContentBlock::type)
        .containsExactly(ContentType.TEXT, ContentType.IMAGE_URL);
    assertThat(request.messages().get(1).content().getFirst().argumentsJson())
        .isEqualTo("{\"city\":\"Shanghai\"}");
    assertThat(request.tools()).singleElement().satisfies(tool -> {
      assertThat(tool.name()).isEqualTo("weather");
      assertThat(tool.strict()).isTrue();
    });
    assertThat(request.responseFormat().type()).isEqualTo(ResponseFormatType.JSON_SCHEMA);
  }

  @Test
  void toolChoiceNoneSuppressesToolsAndStatefulOptionsFailClearly() throws Exception {
    var body = objectMapper.readTree("""
        {"model":"public-model","input":"hello","tool_choice":"none",
         "tools":[{"type":"function","name":"weather","parameters":{"type":"object"}}]}
        """);

    assertThat(protocol.fromRequest(body, "definition-1").tools()).isEmpty();

    ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("previous_response_id", "resp_1");
    assertThatThrownBy(() -> protocol.fromRequest(body, "definition-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("previous_response_id");
  }

  @Test
  void emitsCompletedResponsesObjectWithMessageFunctionAndUsage() throws Exception {
    var request = objectMapper.readTree("""
        {"model":"public-model","input":"hello","store":true,
         "metadata":{"trace":"abc"},"temperature":0.2}
        """);
    GenerationResult result = result("completed");

    var response = protocol.toResponse(result, "public-model", request);

    assertThat(response.path("id").asText()).isEqualTo("resp_inv1");
    assertThat(response.path("object").asText()).isEqualTo("response");
    assertThat(response.path("status").asText()).isEqualTo("completed");
    assertThat(response.path("store").asBoolean()).isFalse();
    assertThat(response.path("output").get(0).path("type").asText()).isEqualTo("message");
    assertThat(response.path("output").get(0).path("content").get(0).path("text").asText())
        .isEqualTo("hello");
    assertThat(response.path("output").get(1).path("type").asText())
        .isEqualTo("function_call");
    assertThat(response.path("output").get(1).path("arguments").asText())
        .isEqualTo("{\"city\":\"Shanghai\"}");
    assertThat(response.path("usage").path("input_tokens_details")
        .path("cached_tokens").asInt()).isEqualTo(2);
    assertThat(response.path("metadata").path("trace").asText()).isEqualTo("abc");
  }

  @Test
  void emitsProtocolNativeStreamingLifecycle() throws Exception {
    var request = objectMapper.readTree("""
        {"model":"public-model","input":"hello","stream":true}
        """);
    var state = protocol.newStreamState("public-model", request);
    List<OpenAiResponsesProtocol.StreamEvent> events = new ArrayList<>();

    events.addAll(protocol.streamEvents(state, generationEvent(EventType.STARTED, null, null)));
    events.addAll(protocol.streamEvents(state, generationEvent(EventType.TEXT_DELTA, "hel", null)));
    events.addAll(protocol.streamEvents(state, generationEvent(EventType.TEXT_DELTA, "lo", null)));
    events.addAll(protocol.streamEvents(state, generationEvent(
        EventType.COMPLETED, null, result("completed"))));

    assertThat(events).extracting(OpenAiResponsesProtocol.StreamEvent::name)
        .containsExactly(
            "response.created",
            "response.in_progress",
            "response.output_item.added",
            "response.content_part.added",
            "response.output_text.delta",
            "response.output_text.delta",
            "response.output_item.added",
            "response.output_text.done",
            "response.content_part.done",
            "response.function_call_arguments.done",
            "response.output_item.done",
            "response.output_item.done",
            "response.completed"
    );
    assertThat(events).extracting(event -> event.data().path("sequence_number").asInt())
        .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    var completed = events.getLast().data().path("response");
    assertThat(completed.path("status").asText()).isEqualTo("completed");
    assertThat(completed.path("output").get(0).path("content").get(0)
        .path("text").asText()).isEqualTo("hello");
  }

  private GenerationEvent generationEvent(
      final EventType type,
      final String text,
      final GenerationResult result
  ) {
    return new GenerationEvent(
        "inv-1", 1, type, text, null, null, null,
        result == null ? null : result.usage(), result, null, null
    );
  }

  private GenerationResult result(final String stopReason) {
    return new GenerationResult(
        "inv-1",
        "definition-1",
        "provider-model",
        "provider-request-1",
        List.of(
            new ContentBlock(ContentType.TEXT, "hello", null, null, null, null, null),
            new ContentBlock(ContentType.TOOL_CALL, null, null, null,
                "call-1", "weather", "{\"city\":\"Shanghai\"}")),
        stopReason,
        new TokenUsage(10, 5, 15, 2),
        12,
        Instant.parse("2026-01-01T00:00:00Z")
    );
  }
}
