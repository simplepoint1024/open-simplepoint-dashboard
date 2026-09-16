package org.simplepoint.plugin.ai.core.rest.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.MessageRole;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ResponseFormatType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.TokenUsage;

class AiCompatibilityMapperTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final AiCompatibilityMapper mapper = new AiCompatibilityMapper(objectMapper);

  @Test
  void mapsOpenAiChatToolsAndStructuredOutput() throws Exception {
    var body = objectMapper.readTree("""
        {
          "model": "public-model",
          "messages": [
            {"role": "developer", "content": "Be concise"},
            {"role": "user", "content": [{"type": "text", "text": "hello"}]}
          ],
          "max_completion_tokens": 256,
          "tools": [{"type": "function", "function": {
            "name": "weather", "description": "Get weather",
            "parameters": {"type": "object"}, "strict": true
          }}],
          "response_format": {"type": "json_schema", "json_schema": {
            "name": "answer", "strict": true, "schema": {"type": "object"}
          }}
        }
        """);

    var request = mapper.fromOpenAi(body, "definition-1");

    assertThat(request.modelDefinitionId()).isEqualTo("definition-1");
    assertThat(request.messages()).extracting(message -> message.role())
        .containsExactly(MessageRole.SYSTEM, MessageRole.USER);
    assertThat(request.tools()).singleElement().satisfies(tool -> {
      assertThat(tool.name()).isEqualTo("weather");
      assertThat(tool.strict()).isTrue();
    });
    assertThat(request.responseFormat().type()).isEqualTo(ResponseFormatType.JSON_SCHEMA);
  }

  @Test
  void mapsAnthropicContentAndRejectsUnknownRole() throws Exception {
    var body = objectMapper.readTree("""
        {
          "model": "public-model",
          "system": [{"type": "text", "text": "Be concise"}],
          "max_tokens": 128,
          "messages": [{"role": "user", "content": [
            {"type": "text", "text": "hello"},
            {"type": "image", "source": {
              "type": "base64", "media_type": "image/png", "data": "AA=="
            }}
          ]}]
        }
        """);

    var request = mapper.fromAnthropic(body, "definition-1");

    assertThat(request.instructions()).isEqualTo("Be concise");
    assertThat(request.messages()).singleElement().satisfies(message -> {
      assertThat(message.role()).isEqualTo(MessageRole.USER);
      assertThat(message.content()).extracting(ContentBlock::type)
          .containsExactly(ContentType.TEXT, ContentType.IMAGE_URL);
    });

    ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("messages").get(0))
        .put("role", "system");
    assertThatThrownBy(() -> mapper.fromAnthropic(body, "definition-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Anthropic 消息角色");
  }

  @Test
  void emitsProtocolNativeResponsesAndUsage() {
    GenerationResult result = new GenerationResult(
        "inv-1",
        "definition-1",
        "provider-model",
        "provider-request-1",
        List.of(
            new ContentBlock(ContentType.TEXT, "hello", null, null, null, null, null),
            new ContentBlock(ContentType.TOOL_CALL, null, null, null,
                "call-1", "weather", "{\"city\":\"Shanghai\"}")),
        "tool_use",
        new TokenUsage(10, 5, 15, 2),
        12,
        Instant.parse("2026-01-01T00:00:00Z")
    );

    var openAi = mapper.toOpenAiResponse(result, "public-model");
    var anthropic = mapper.toAnthropicResponse(result, "public-model");

    assertThat(openAi.path("choices").get(0).path("finish_reason").asText())
        .isEqualTo("tool_calls");
    assertThat(openAi.path("usage").path("total_tokens").asInt()).isEqualTo(15);
    assertThat(anthropic.path("content").get(1).path("type").asText()).isEqualTo("tool_use");
    assertThat(anthropic.path("stop_reason").asText()).isEqualTo("tool_use");
  }
}
