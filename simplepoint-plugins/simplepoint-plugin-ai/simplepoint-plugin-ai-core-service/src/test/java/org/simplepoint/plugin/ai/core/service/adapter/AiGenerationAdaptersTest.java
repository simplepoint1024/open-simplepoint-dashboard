package org.simplepoint.plugin.ai.core.service.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentBlock;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.ContentType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.EventType;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationEvent;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationRequest;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.Message;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.MessageRole;

class AiGenerationAdaptersTest {

  private HttpServer server;

  private String baseUrl;

  private ObjectMapper objectMapper;

  private AiProperties properties;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    objectMapper = new ObjectMapper();
    properties = new AiProperties();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void parsesOpenAiResponsesOutputAndUsage() {
    server.createContext("/v1/responses", exchange -> respondJson(exchange, """
        {
          "id":"resp_1",
          "status":"completed",
          "output":[
            {"type":"message","content":[{"type":"output_text","text":"hello"}]},
            {"type":"function_call","call_id":"call_1","name":"weather","arguments":"{\\"city\\":\\"Shanghai\\"}"}
          ],
          "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}
        }
        """));
    var adapter = new OpenAiResponsesGenerationAdapter(objectMapper, properties);

    GenerationResult result = adapter.generate(invocation(AiProviderType.OPENAI));

    assertEquals("resp_1", result.providerRequestId());
    assertEquals("hello", result.output().getFirst().text());
    assertEquals(ContentType.TOOL_CALL, result.output().get(1).type());
    assertEquals(15, result.usage().totalTokens());
  }

  @Test
  void parsesAnthropicMessagesOutputAndUsage() {
    server.createContext("/v1/messages", exchange -> respondJson(exchange, """
        {
          "id":"msg_1",
          "content":[
            {"type":"text","text":"hello"},
            {"type":"tool_use","id":"tool_1","name":"weather","input":{"city":"Shanghai"}}
          ],
          "stop_reason":"tool_use",
          "usage":{"input_tokens":8,"output_tokens":4}
        }
        """));
    var adapter = new AnthropicMessagesGenerationAdapter(objectMapper, properties);

    GenerationResult result = adapter.generate(invocation(AiProviderType.ANTHROPIC));

    assertEquals("msg_1", result.providerRequestId());
    assertEquals("tool_use", result.stopReason());
    assertEquals(12, result.usage().totalTokens());
    assertEquals("weather", result.output().get(1).toolName());
  }

  @Test
  void normalizesOpenAiCompatibleStreamingEvents() {
    server.createContext("/v1/chat/completions", exchange -> respondSse(exchange, """
        data: {"id":"chat_1","choices":[{"delta":{"content":"hel"}}]}

        data: {"id":"chat_1","choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}

        data: {"id":"chat_1","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}

        data: [DONE]

        """));
    var adapter = new OpenAiCompatibleGenerationAdapter(objectMapper, properties);
    List<GenerationEvent> events = new ArrayList<>();

    adapter.stream(invocation(AiProviderType.OPENAI_COMPATIBLE), events::add);

    assertEquals("hello", events.stream()
        .filter(event -> event.type() == EventType.TEXT_DELTA)
        .map(GenerationEvent::textDelta)
        .reduce("", String::concat));
    GenerationEvent completed = events.getLast();
    assertEquals(EventType.COMPLETED, completed.type());
    assertEquals(5, completed.result().usage().totalTokens());
  }

  private AiRuntimeInvocation invocation(final AiProviderType providerType) {
    AiProviderDefinition provider = new AiProviderDefinition();
    provider.setId("provider-1");
    provider.setProviderType(providerType);
    provider.setBaseUrl(baseUrl);
    provider.setAllowPrivateNetwork(Boolean.TRUE);
    AiModelDefinition model = new AiModelDefinition();
    model.setId("model-1");
    model.setModelId("test-model");
    model.setModelType(AiModelType.LLM);
    GenerationRequest request = new GenerationRequest(
        model.getId(), "be helpful",
        List.of(new Message(
            MessageRole.USER,
            List.of(new ContentBlock(
                ContentType.TEXT, "hello", null, null, null, null, null
            ))
        )),
        128, 0.2, null, List.of(), null
    );
    return new AiRuntimeInvocation(
        "invocation-1", provider, model, request, "secret", 5
    );
  }

  private static void respondJson(final HttpExchange exchange, final String body)
      throws IOException {
    assertTrue(new String(
        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8
    ).contains("test-model"));
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static void respondSse(final HttpExchange exchange, final String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
