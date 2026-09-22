package org.simplepoint.plugin.ai.core.service.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.entity.AiGatewayResource;
import org.simplepoint.plugin.ai.core.api.entity.AiInvocationRecord;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.exception.AiOpenAiRequestException;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiProviderDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService.GatewaySession;
import org.simplepoint.plugin.ai.core.api.service.AiNativeOpenAiGateway.Exchange;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.TokenUsage;
import org.simplepoint.plugin.ai.core.repository.AiGatewayResourceRepository;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;

class NativeOpenAiGatewayTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final GatewaySession session = new GatewaySession("key-a", "A", AiResourceScope.SYSTEM, null);
  private final Map<String, AiGatewayResource> bindings = new ConcurrentHashMap<>();
  private HttpServer server;
  private NativeOpenAiGateway gateway;
  private AiProviderDefinition provider;
  private AiProperties properties;
  private AiInvocationLedger ledger;
  private AiInvocationRecord record;
  private AiGatewayResourceRepository resources;
  private AiScopeAccessPolicy policy;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    final var models = mock(AiModelDefinitionRepository.class);
    final var providers = mock(AiProviderDefinitionRepository.class);
    final var cipher = mock(AiCredentialCipher.class);
    policy = mock(AiScopeAccessPolicy.class);
    resources = mock(AiGatewayResourceRepository.class);
    ledger = mock(AiInvocationLedger.class);
    record = new AiInvocationRecord();
    record.setId("invocation-1");
    when(ledger.start(anyString(), any(), any(), any(), anyBoolean())).thenReturn(record);
    AiModelDefinition model = new AiModelDefinition();
    model.setId("definition-1");
    model.setProviderId("provider-1");
    model.setModelId("upstream-model");
    model.setScopeType(AiResourceScope.SYSTEM);
    model.setModelType(AiModelType.LLM);
    model.setEnabled(true);
    model.setAvailable(true);
    provider = new AiProviderDefinition();
    provider.setId("provider-1");
    provider.setProviderType(AiProviderType.OPENAI);
    provider.setScopeType(AiResourceScope.SYSTEM);
    provider.setEnabled(true);
    provider.setAllowPrivateNetwork(true);
    provider.setCredentialCiphertext("encrypted");
    provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    when(models.findActiveById("definition-1")).thenReturn(Optional.of(model));
    when(providers.findActiveById("provider-1")).thenReturn(Optional.of(provider));
    when(cipher.decrypt("encrypted")).thenReturn("upstream-secret");
    when(policy.canUseResource(any(), any())).thenReturn(true);
    when(resources.findById(anyString())).thenAnswer(call -> Optional.ofNullable(bindings.get(call.getArgument(0))));
    when(resources.existsById(anyString())).thenAnswer(call -> bindings.containsKey(call.getArgument(0)));
    when(resources.saveAndFlush(any())).thenAnswer(call -> {
      AiGatewayResource resource = call.getArgument(0);
      bindings.put(resource.getId(), resource);
      return resource;
    });
    properties = new AiProperties();
    gateway = new NativeOpenAiGateway(models, providers, cipher, policy, resources, ledger, mapper, properties);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void preservesCodexFieldsToolsReasoningAndUnknownExtensions() throws Exception {
    AtomicReference<JsonNode> captured = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    String response = """
        {"id":"resp_1","object":"response","status":"completed","model":"upstream-model",
         "output":[{"type":"reasoning","id":"rs_1","encrypted_content":"opaque","summary":[]},
          {"type":"custom_tool_call","id":"ct_1","call_id":"call_1","name":"apply_patch",
           "namespace":"functions","input":"*** Begin Patch\\n*** End Patch"}],
         "usage":{"input_tokens":12,"output_tokens":8,"total_tokens":20,
          "input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":5}},
         "future_extension":{"kept":true}}
        """;
    server.createContext("/v1/responses", exchange -> {
      captured.set(mapper.readTree(exchange.getRequestBody()));
      auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      assertThat(exchange.getRequestHeaders().getFirst("OpenAI-Beta")).isEqualTo("responses_multi_agent=v1");
      respond(exchange, 200, "application/json", response);
    });
    String payload = """
        {"model":"public-model","input":[{"type":"reasoning","encrypted_content":"previous"},
          {"role":"assistant","phase":"commentary","content":"Working"},
          {"type":"custom_tool_call_output","call_id":"call_0","output":"done"}],
         "tools":[{"type":"namespace","name":"functions","tools":[{"type":"custom",
           "name":"apply_patch","format":{"type":"grammar","syntax":"lark","definition":"start: /.+/"}}]},
          {"type":"web_search"},{"type":"tool_search"}],
         "reasoning":{"effort":"high","summary":"auto"},"include":["reasoning.encrypted_content"],
         "text":{"verbosity":"low"},"parallel_tool_calls":false,"store":false,
         "tool_choice":"required","future_extension":{"kept":true}}
        """;
    Exchange exchange = create("/responses", payload, Map.of("OpenAI-Beta", "responses_multi_agent=v1"));
    assertThat(read(exchange)).isEqualTo(response);
    var expected = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(payload);
    expected.put("model", "upstream-model");
    assertThat(captured.get()).isEqualTo(expected);
    assertThat(auth.get()).isEqualTo("Bearer upstream-secret");
    verify(ledger).success(eq(record), eq(new TokenUsage(12, 8, 20, 3)), anyLong(), eq("resp_1"));
    assertThat(bindings).hasSize(3);
  }

  @Test
  void preservesAllSseFramesIncludingFutureEventsAndToolDeltas() {
    String events = """
        : heartbeat

        event: response.created
        data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_1","object":"response","status":"in_progress"}}

        event: response.reasoning_summary_text.delta
        data: {"type":"response.reasoning_summary_text.delta","sequence_number":1,"delta":"思考中"}

        event: response.custom_tool_call_input.delta
        id: event-2
        data: {"type":"response.custom_tool_call_input.delta","delta":"text('你好')"}

        event: future.event
        data: {"type":"future.event",
        data: "extension":true}

        event: response.completed
        data: {"type":"response.completed","sequence_number":4,"response":{"id":"resp_1","object":"response","status":"completed","output":[],
        data: "usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5}}}

        """;
    server.createContext("/v1/responses", exchange -> respond(exchange, 200, "text/event-stream", events));
    Exchange exchange = create("/responses", "{\"model\":\"public-model\",\"input\":\"hi\",\"stream\":true}", Map.of());
    assertThat(exchange.streaming()).isTrue();
    assertThat(read(exchange)).isEqualTo(events);
    verify(ledger).success(eq(record), eq(new TokenUsage(2, 3, 5, null)), anyLong(), eq("resp_1"));
  }

  @Test
  void preservesHttpErrorsRetryHeadersAndRedactsCredential() {
    server.createContext("/v1/responses", exchange -> {
      exchange.getResponseHeaders().add("Retry-After", "12");
      exchange.getResponseHeaders().add("X-Request-Id", "request-1");
      exchange.getResponseHeaders().add("Set-Cookie", "private-cookie");
      respond(exchange, 429, "application/json", """
          {"error":{"message":"Rate limit for upstream-secret","type":"rate_limit_error","param":null,"code":"rate_limit_exceeded"}}
          """);
    });
    Exchange exchange = create("/responses", "{\"model\":\"public-model\",\"input\":\"hi\"}", Map.of());
    assertThat(exchange.status()).isEqualTo(429);
    assertThat(exchange.headers()).containsKey("retry-after").containsKey("x-request-id").doesNotContainKey("set-cookie");
    assertThat(read(exchange)).contains("rate_limit_exceeded", "[REDACTED]").doesNotContain("upstream-secret");
    verify(ledger).failure(eq(record), any());
  }

  @Test
  void compactionAndTokenCountingPreserveEncryptedItems() {
    for (String endpoint : new String[]{"compact", "input_tokens"}) {
      server.createContext("/v1/responses/" + endpoint, exchange -> {
        JsonNode body = mapper.readTree(exchange.getRequestBody());
        assertThat(body.path("input").get(0).path("encrypted_content").asText()).isEqualTo("opaque");
        respond(exchange, 200, "application/json", endpoint.equals("compact")
            ? "{\"object\":\"response.compaction\",\"output\":[{\"type\":\"compaction\",\"encrypted_content\":\"compact-opaque\"}]}"
            : "{\"object\":\"response.input_tokens\",\"input_tokens\":123}");
      });
      String result = read(create("/responses/" + endpoint,
          "{\"model\":\"public-model\",\"input\":[{\"type\":\"reasoning\",\"encrypted_content\":\"opaque\"}]}", Map.of()));
      assertThat(result).contains(endpoint.equals("compact") ? "compact-opaque" : "123");
    }
  }

  @Test
  void scopesResponseOperationsAndPreviousResponseToApiKeyAndProvider() {
    AtomicReference<String> method = new AtomicReference<>();
    AtomicReference<String> query = new AtomicReference<>();
    server.createContext("/v1/responses", exchange -> {
      method.set(exchange.getRequestMethod());
      query.set(exchange.getRequestURI().getRawQuery());
      if (exchange.getRequestURI().getPath().endsWith("/input_items")) {
        respond(exchange, 200, "application/json",
            "{\"object\":\"list\",\"data\":[{\"id\":\"msg_input\",\"type\":\"message\",\"role\":\"user\",\"content\":[]}]}");
        return;
      }
      respond(exchange, 200, "application/json",
          "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"output\":[]}");
    });
    read(create("/responses", "{\"model\":\"public-model\",\"input\":\"hi\"}", Map.of()));
    read(gateway.response(session, "resp_1", "GET", "/input_items", "limit=3&order=asc", Map.of()));
    assertThat(query.get()).isEqualTo("limit=3&order=asc");
    read(create("/responses", "{\"model\":\"public-model\",\"input\":[{\"type\":\"item_reference\",\"id\":\"msg_input\"}]}", Map.of()));
    read(gateway.response(session, "resp_1", "POST", "/cancel", null, Map.of()));
    assertThat(method.get()).isEqualTo("POST");
    read(create("/responses", "{\"model\":\"public-model\",\"previous_response_id\":\"resp_1\",\"input\":\"next\"}", Map.of()));
    GatewaySession other = new GatewaySession("key-b", "B", AiResourceScope.SYSTEM, null);
    assertThatThrownBy(() -> gateway.response(other, "resp_1", "GET", "", null, Map.of()))
        .isInstanceOf(AiOpenAiRequestException.class).extracting("status").isEqualTo(404);
    assertThatThrownBy(() -> gateway.create(other, "definition-1", "/responses",
        "{\"model\":\"public-model\",\"previous_response_id\":\"resp_1\"}", Map.of()))
        .isInstanceOf(AiOpenAiRequestException.class);
    when(policy.canUseResource(any(), any())).thenReturn(false);
    assertThatThrownBy(() -> gateway.response(session, "resp_1", "DELETE", "", null, Map.of()))
        .isInstanceOf(AiOpenAiRequestException.class);
  }

  @Test
  void idempotencyKeysAreIsolatedAndClientCredentialsNeverForwarded() {
    AtomicReference<String> first = new AtomicReference<>();
    server.createContext("/v1/responses", exchange -> {
      String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
      if (first.get() == null) {
        first.set(key);
      } else {
        assertThat(key).isNotEqualTo(first.get());
      }
      assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer upstream-secret");
      assertThat(exchange.getRequestHeaders().getFirst("OpenAI-Project")).isNull();
      respond(exchange, 200, "application/json", "{\"object\":\"response\",\"output\":[]}");
    });
    Map<String, String> headers = Map.of("Idempotency-Key", "same", "Authorization", "Bearer platform-secret",
        "OpenAI-Project", "attacker-project");
    read(create("/responses", "{\"model\":\"public-model\",\"input\":\"hi\"}", headers));
    read(gateway.create(new GatewaySession("key-b", "B", AiResourceScope.SYSTEM, null), "definition-1",
        "/responses", "{\"model\":\"public-model\",\"input\":\"hi\"}", headers).orElseThrow());
  }

  @Test
  void refusesUnownedResourcesAndUnexpectedPathsBeforeCallingUpstream() {
    for (String field : new String[]{"\"previous_response_id\":\"resp_unknown\"",
        "\"conversation\":\"conv_other\"", "\"input\":[{\"type\":\"item_reference\",\"id\":\"msg_other\"}]",
        "\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_file\",\"file_id\":\"file_other\"}]}"}) {
      assertThatThrownBy(() -> create("/responses", "{\"model\":\"public-model\"," + field + "}", Map.of()))
          .isInstanceOf(AiOpenAiRequestException.class);
    }
    assertThatThrownBy(() -> create("/../admin", "{}", Map.of())).isInstanceOf(AiOpenAiRequestException.class);
    verifyNoInteractions(ledger);
  }

  @Test
  void rejectsTruncatedStreamsInsteadOfReportingSuccess() {
    server.createContext("/v1/responses", exchange -> respond(exchange, 200, "text/event-stream",
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"));
    Exchange exchange = create("/responses", "{\"model\":\"public-model\",\"stream\":true}", Map.of());
    assertThatThrownBy(() -> read(exchange)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal");
    verify(ledger).failure(eq(record), any());
    verify(ledger, never()).success(any(), any(TokenUsage.class), anyLong(), any());
  }

  @Test
  void closesUnconsumedStreamsAndHonorsPrivateNetworkPolicy() {
    server.createContext("/v1/responses", exchange -> respond(exchange, 200, "application/json", "{}"));
    Exchange exchange = create("/responses", "{\"model\":\"public-model\",\"input\":\"hi\"}", Map.of());
    exchange.close();
    verify(ledger).cancelled(record);
    provider.setAllowPrivateNetwork(false);
    assertThatThrownBy(() -> create("/responses", "{\"model\":\"public-model\",\"input\":\"hi\"}", Map.of()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("受限网络");
  }

  @Test
  void forwardsChatCompletionsWithoutDroppingOptions() {
    provider.setProviderType(AiProviderType.OPENAI_COMPATIBLE);
    server.createContext("/v1/chat/completions", exchange -> {
      JsonNode body = mapper.readTree(exchange.getRequestBody());
      assertThat(body.path("n").asInt()).isEqualTo(2);
      assertThat(body.path("tool_choice").asText()).isEqualTo("required");
      assertThat(body.path("logprobs").asBoolean()).isTrue();
      respond(exchange, 200, "application/json", "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0},{\"index\":1}]}");
    });
    assertThat(read(create("/chat/completions",
        "{\"model\":\"public-model\",\"messages\":[],\"n\":2,\"tool_choice\":\"required\",\"logprobs\":true}", Map.of())))
        .contains("chatcmpl-1");
    assertThat(gateway.create(session, "definition-1", "/responses", "{}", Map.of())).isEmpty();
  }

  @Test
  void backgroundResponsesAreChargedOnlyAfterCompletion() {
    AtomicReference<String> state = new AtomicReference<>("queued");
    server.createContext("/v1/responses", exchange -> respond(exchange, 200, "application/json",
        "{\"id\":\"resp_background\",\"object\":\"response\",\"status\":\"" + state.get()
            + "\",\"output\":[],\"usage\":{\"input_tokens\":2,\"output_tokens\":3,\"total_tokens\":5}}"));
    when(ledger.find("invocation-1")).thenReturn(record);
    read(create("/responses", "{\"model\":\"public-model\",\"input\":\"hi\",\"background\":true}", Map.of()));
    verify(ledger, never()).success(any(), any(TokenUsage.class), anyLong(), any());
    state.set("completed");
    read(gateway.response(session, "resp_background", "GET", "", null, Map.of()));
    verify(ledger).success(eq(record), eq(new TokenUsage(2, 3, 5, null)), anyLong(), eq("resp_background"));
  }

  @Test
  void chatStreamingAccountsUsageWithoutAddingAnUnrequestedUsageChunk() {
    server.createContext("/v1/chat/completions", exchange -> {
      JsonNode body = mapper.readTree(exchange.getRequestBody());
      assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
      respond(exchange, 200, "text/event-stream", """
          data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"你好"}}]}

          data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

          data: {"id":"chatcmpl-1","choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}

          data: [DONE]

          """);
    });
    String result = read(create("/chat/completions", "{\"model\":\"public-model\",\"messages\":[],\"stream\":true}", Map.of()));
    assertThat(result).contains("你好", "[DONE]").doesNotContain("\"usage\"");
    verify(ledger).success(eq(record), eq(new TokenUsage(2, 3, 5, null)), anyLong(), eq("chatcmpl-1"));
    String withUsage = read(create("/chat/completions",
        "{\"model\":\"public-model\",\"messages\":[],\"stream\":true,\"stream_options\":{\"include_usage\":true}}", Map.of()));
    assertThat(withUsage).contains("\"usage\"");
  }

  @Test
  void failuresToPersistOwnershipDoNotExposeUnroutableResponseIds() {
    server.createContext("/v1/responses", exchange -> respond(exchange, 200, "application/json",
        "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\"}"));
    doThrow(new IllegalStateException("database unavailable")).when(resources).saveAndFlush(any());
    Exchange exchange = create("/responses", "{\"model\":\"public-model\",\"input\":\"hi\"}", Map.of());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (exchange) {
      assertThatThrownBy(() -> exchange.consume(output::writeBytes)).isInstanceOf(IllegalStateException.class);
    }
    assertThat(output.size()).isZero();
    verify(ledger).failure(eq(record), any());
  }

  @Test
  void abortsStalledStreamsAtTheConfiguredDeadline() throws Exception {
    var release = new java.util.concurrent.CountDownLatch(1);
    properties.setStreamingTimeoutMs(100L);
    server.createContext("/v1/responses", exchange -> {
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try (var body = exchange.getResponseBody()) {
        body.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
        body.flush();
        try {
          release.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
    });
    try {
      org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
        Exchange exchange = create("/responses", "{\"model\":\"public-model\",\"stream\":true}", Map.of());
        assertThatThrownBy(() -> read(exchange)).isInstanceOf(RuntimeException.class);
      });
      verify(ledger).cancelled(record);
    } finally {
      release.countDown();
    }
  }

  private Exchange create(final String path, final String payload, final Map<String, String> headers) {
    return gateway.create(session, "definition-1", path, payload, headers).orElseThrow();
  }

  private String read(final Exchange exchange) {
    try (exchange) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      exchange.consume(output::writeBytes);
      return output.toString(StandardCharsets.UTF_8);
    }
  }

  private void respond(final HttpExchange exchange, final int status, final String type,
      final String body) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", type);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
