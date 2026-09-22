package org.simplepoint.plugin.ai.core.service.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.simplepoint.plugin.ai.core.api.entity.AiGatewayResource;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiProviderDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService;
import org.simplepoint.plugin.ai.core.api.service.AiGatewayAccessService.GatewaySession;
import org.simplepoint.plugin.ai.core.api.service.AiGenerationService;
import org.simplepoint.plugin.ai.core.repository.AiGatewayResourceRepository;
import org.simplepoint.plugin.ai.core.rest.controller.AiCompatibleGatewayController;
import org.simplepoint.plugin.ai.core.rest.gateway.AiCompatibilityMapper;
import org.simplepoint.plugin.ai.core.rest.gateway.OpenAiResponsesProtocol;
import org.simplepoint.plugin.ai.core.service.security.AiCredentialCipher;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Opt-in real Codex process against the actual controller and native transport with a local model fixture. */
class NativeOpenAiClientTest {
  @TempDir
  Path temporary;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @EnabledIfEnvironmentVariable(named = "SIMPLEPOINT_CODEX_BINARY", matches = ".+")
  void realCodexExecutesToolAndReturnsItsResultThroughGateway() throws Exception {
    HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    HttpServer gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger turns = new AtomicInteger();
    List<JsonNode> requests = new ArrayList<>();
    upstream.createContext("/v1/responses", exchange -> {
      JsonNode request = mapper.readTree(exchange.getRequestBody());
      requests.add(request);
      int turn = turns.incrementAndGet();
      JsonNode response = mapper.createObjectNode().put("id", "resp_" + turn)
          .put("object", "response").put("created_at", 1700000000)
          .put("status", "completed").put("model", "gateway-model");
      var output = ((com.fasterxml.jackson.databind.node.ObjectNode) response).putArray("output");
      if (turn == 1) {
        String tool = shellTool(request.path("tools"));
        if (tool == null) {
          reply(exchange, 400, "application/json", "{\"error\":{\"message\":\"No shell tool exposed\"}}");
          return;
        }
        String commandKey = "exec_command".equals(tool) ? "cmd" : "command";
        String arguments = mapper.createObjectNode().put(commandKey, "printf gateway-tool-ok").toString();
        output.addObject().put("type", "function_call").put("id", "fc_1")
            .put("call_id", "call_1").put("name", tool).put("arguments", arguments).put("status", "completed");
      } else {
        output.addObject().put("type", "message").put("id", "msg_2")
            .put("role", "assistant").put("status", "completed").put("phase", "final_answer")
            .putArray("content").addObject().put("type", "output_text").put("text", "gateway-client-ok")
            .putArray("annotations");
      }
      ((com.fasterxml.jackson.databind.node.ObjectNode) response).putObject("usage")
          .put("input_tokens", 10).put("output_tokens", 5).put("total_tokens", 15);
      StringBuilder sse = new StringBuilder();
      var created = response.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) created).put("status", "in_progress").putArray("output");
      sse.append(event("response.created", mapper.createObjectNode().set("response", created)));
      for (int i = 0; i < output.size(); i++) {
        sse.append(event("response.output_item.added", mapper.createObjectNode().put("output_index", i).set("item", output.get(i))));
        sse.append(event("response.output_item.done", mapper.createObjectNode().put("output_index", i).set("item", output.get(i))));
      }
      sse.append(event("response.completed", mapper.createObjectNode().set("response", response)));
      reply(exchange, 200, "text/event-stream", sse.toString());
    });
    MockMvc mvc = controller(upstream.getAddress().getPort());
    gateway.createContext("/v1/", exchange -> {
      try {
        var request = request(org.springframework.http.HttpMethod.valueOf(exchange.getRequestMethod()),
            exchange.getRequestURI()).content(exchange.getRequestBody().readAllBytes());
        exchange.getRequestHeaders().forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        var result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
          result.getAsyncResult(10_000);
          result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        reply(exchange, result.getResponse().getStatus(), result.getResponse().getContentType(),
            result.getResponse().getContentAsString(StandardCharsets.UTF_8));
      } catch (Exception ex) {
        reply(exchange, 500, "application/json", "{\"error\":{\"message\":\"Test gateway failed\"}}");
      }
    });
    upstream.start();
    gateway.start();
    Process process = null;
    try {
      Path configHome = Files.createDirectory(temporary.resolve("codex"));
      Path work = Files.createDirectory(temporary.resolve("work"));
      Files.writeString(configHome.resolve("config.toml"), """
          model = "gateway-model"
          model_provider = "simplepoint"
          approval_policy = "never"
          web_search = "disabled"
          [model_providers.simplepoint]
          name = "SimplePoint integration fixture"
          base_url = "http://127.0.0.1:%d/v1"
          env_key = "SIMPLEPOINT_TEST_API_KEY"
          wire_api = "responses"
          supports_websockets = false
          request_max_retries = 0
          stream_max_retries = 0
          """.formatted(gateway.getAddress().getPort()));
      Path log = temporary.resolve("codex.log");
      ProcessBuilder builder = new ProcessBuilder(System.getenv("SIMPLEPOINT_CODEX_BINARY"),
          "exec", "--ephemeral", "--skip-git-repo-check", "--sandbox", "read-only",
          "-C", work.toString(), "Run printf gateway-tool-ok and then report gateway-client-ok.")
          .redirectErrorStream(true).redirectOutput(log.toFile());
      builder.environment().put("CODEX_HOME", configHome.toString());
      builder.environment().put("SIMPLEPOINT_TEST_API_KEY", "test-platform-key");
      process = builder.start();
      process.getOutputStream().close();
      boolean exited = process.waitFor(45, TimeUnit.SECONDS);
      assertThat(exited).as("Codex finished: %s", Files.readString(log)).isTrue();
      String transcript = Files.readString(log);
      assertThat(process.exitValue()).as(transcript).isZero();
      assertThat(transcript).contains("gateway-client-ok");
      assertThat(turns.get()).isEqualTo(2);
      List<String> toolOutputs = new ArrayList<>();
      requests.get(1).path("input").forEach(item -> {
        if ("function_call_output".equals(item.path("type").asText())) {
          toolOutputs.add(item.path("output").asText());
        }
      });
      assertThat(toolOutputs).anySatisfy(output -> assertThat(output)
          .contains("gateway-tool-ok").doesNotContain("failed", "denied"));
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
      upstream.stop(0);
      gateway.stop(0);
    }
  }

  private MockMvc controller(final int upstreamPort) {
    final var models = mock(AiModelDefinitionRepository.class);
    final var providers = mock(AiProviderDefinitionRepository.class);
    final var cipher = mock(AiCredentialCipher.class);
    final var scope = mock(AiScopeAccessPolicy.class);
    final var resources = mock(AiGatewayResourceRepository.class);
    Map<String, AiGatewayResource> bindings = new ConcurrentHashMap<>();
    when(resources.findById(anyString())).thenAnswer(call -> Optional.ofNullable(bindings.get(call.getArgument(0))));
    when(resources.saveAndFlush(any())).thenAnswer(call -> {
      AiGatewayResource value = call.getArgument(0);
      bindings.put(value.getId(), value);
      return value;
    });
    AiModelDefinition model = new AiModelDefinition();
    model.setId("model-1");
    model.setProviderId("provider-1");
    model.setModelId("gateway-model");
    model.setScopeType(AiResourceScope.SYSTEM);
    model.setModelType(AiModelType.LLM);
    model.setEnabled(true);
    model.setAvailable(true);
    AiProviderDefinition provider = new AiProviderDefinition();
    provider.setId("provider-1");
    provider.setProviderType(AiProviderType.OPENAI);
    provider.setScopeType(AiResourceScope.SYSTEM);
    provider.setEnabled(true);
    provider.setAllowPrivateNetwork(true);
    provider.setBaseUrl("http://127.0.0.1:" + upstreamPort + "/v1");
    provider.setCredentialCiphertext("encrypted");
    when(models.findActiveById("model-1")).thenReturn(Optional.of(model));
    when(providers.findActiveById("provider-1")).thenReturn(Optional.of(provider));
    when(cipher.decrypt("encrypted")).thenReturn("test-upstream-key");
    when(scope.canUseResource(any(), any())).thenReturn(true);
    var access = mock(AiGatewayAccessService.class);
    when(access.authenticate(eq("test-platform-key"), anyString())).thenReturn(
        new GatewaySession("key-1", "test", AiResourceScope.SYSTEM, null));
    when(access.resolveModelDefinitionId(any(), eq("gateway-model"))).thenReturn("model-1");
    when(access.withSession(any(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
    var properties = new AiProperties();
    var nativeGateway = new NativeOpenAiGateway(models, providers, cipher, scope, resources,
        mock(AiInvocationLedger.class), mapper, properties);
    return MockMvcBuilders.standaloneSetup(new AiCompatibleGatewayController(access,
        mock(AiGenerationService.class), new AiCompatibilityMapper(mapper),
        new OpenAiResponsesProtocol(mapper), Runnable::run, properties, nativeGateway)).build();
  }

  private String shellTool(final JsonNode tools) {
    for (JsonNode tool : tools) {
      String name = tool.path("name").asText();
      if (List.of("exec_command", "shell_command").contains(name)) {
        return name;
      }
      if (tool.has("tools")) {
        String nested = shellTool(tool.get("tools"));
        if (nested != null) {
          return nested;
        }
      }
    }
    return null;
  }

  private String event(final String type, final com.fasterxml.jackson.databind.node.ObjectNode data) {
    data.put("type", type);
    data.put("sequence_number", 1);
    return "event: " + type + "\ndata: " + data + "\n\n";
  }

  private void reply(final HttpExchange exchange, final int status, final String contentType,
      final String body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType == null ? "application/json" : contentType);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
