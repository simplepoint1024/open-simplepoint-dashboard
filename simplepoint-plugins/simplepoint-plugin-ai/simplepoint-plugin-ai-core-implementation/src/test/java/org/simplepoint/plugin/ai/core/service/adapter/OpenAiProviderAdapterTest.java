package org.simplepoint.plugin.ai.core.service.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderType;
import org.simplepoint.plugin.ai.core.api.model.AiProviderVendor;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredModel;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ProviderConnection;
import org.simplepoint.plugin.ai.core.service.support.AiDiscoveredPricingParser;
import org.simplepoint.plugin.ai.core.service.support.AiModelTypeDetector;

class OpenAiProviderAdapterTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void discoversFromIndependentCatalogUrlAndImportsMetadataAndPricing() throws Exception {
    AtomicReference<String> requestedPath = new AtomicReference<>();
    startServer(exchange -> {
      requestedPath.set(exchange.getRequestURI().getPath());
      respond(exchange, """
          {"models":[{
            "id":"embedding-model-without-name-inference",
            "displayName":"Embedding One",
            "task":"embedding",
            "pricing":{"prompt":"0.00000025","currency":"eur"}
          }]}
          """);
    });
    OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(
        new ObjectMapper(),
        new AiModelTypeDetector(),
        new AiDiscoveredPricingParser(),
        new AiProperties()
    );
    String discoveryUrl = "http://127.0.0.1:" + server.getAddress().getPort()
        + "/vendor/catalog";
    ProviderConnection connection = new ProviderConnection(
        "provider-1",
        AiProviderType.OPENAI_COMPATIBLE,
        AiProviderVendor.CUSTOM,
        "https://generation.invalid/v1",
        discoveryUrl,
        null,
        true,
        5
    );

    List<DiscoveredModel> models = adapter.discoverModels(connection);

    assertEquals("/vendor/catalog", requestedPath.get());
    assertEquals(1, models.size());
    assertEquals(AiModelType.EMBEDDING, models.getFirst().modelType());
    assertEquals("EUR", models.getFirst().pricing().currency());
    assertEquals(new BigDecimal("0.25000000"), models.getFirst().pricing().inputTokenPrice());
    assertNull(models.getFirst().pricing().outputTokenPrice());
  }

  @Test
  void discoversAllDashScopePagesWithTypesAndPrices() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    startServer(exchange -> {
      requests.incrementAndGet();
      boolean secondPage = exchange.getRequestURI().getRawQuery() != null
          && exchange.getRequestURI().getRawQuery().contains("page_no=2");
      String model = secondPage ? """
          {"model":"qwen-image-max","name":"Qwen Image Max","capabilities":["IG"],
           "prices":[{"range_name":"Default","prices":[
             {"type":"image_number","price":"0.075","price_unit":"每张"}
           ]}]}
          """ : """
          {"model":"qwen3-max","name":"Qwen 3 Max","capabilities":["TG","Reasoning"],
           "prices":[{"range_name":"Default","prices":[
             {"type":"input_token","price":"2","price_unit":"每百万tokens"},
             {"type":"output_token","price":"8","price_unit":"每百万tokens"}
           ]}]}
          """;
      respond(exchange, """
          {"success":true,"output":{"total":2,"page_no":%d,"page_size":1,"models":[%s]}}
          """.formatted(secondPage ? 2 : 1, model));
    });
    OpenAiProviderAdapter adapter = new OpenAiProviderAdapter(
        new ObjectMapper(),
        new AiModelTypeDetector(),
        new AiDiscoveredPricingParser(),
        new AiProperties()
    );
    String discoveryUrl = "http://127.0.0.1:" + server.getAddress().getPort()
        + "/api/v1/models";
    ProviderConnection connection = new ProviderConnection(
        "provider-1",
        AiProviderType.OPENAI_COMPATIBLE,
        AiProviderVendor.ALIBABA_QWEN,
        "https://generation.invalid/v1",
        discoveryUrl,
        "sk-test",
        true,
        5
    );

    List<DiscoveredModel> models = adapter.discoverModels(connection);

    assertEquals(2, requests.get());
    assertEquals(List.of("qwen3-max", "qwen-image-max"),
        models.stream().map(DiscoveredModel::modelId).toList());
    assertEquals(AiModelType.LLM, models.get(0).modelType());
    assertEquals(AiModelType.IMAGE, models.get(1).modelType());
    assertEquals("CNY", models.get(0).pricing().currency());
    assertEquals(new BigDecimal("2.00000000"), models.get(0).pricing().inputTokenPrice());
    assertEquals(new BigDecimal("0.07500000"), models.get(1).pricing().requestPrice());
  }

  private void startServer(final ExchangeHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", handler::handle);
    server.start();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"OPENAI_COMPATIBLE", "ANTHROPIC"})
  void numericCatalogPricesRemainExactBeforeDomainNormalization(String protocol) throws Exception {
    startServer(exchange -> respond(exchange, """
        {"data":[{"id":"exact-model","pricing":{"input_per_million":99999999999.99999999,
          "output_per_million":0.123456785,"currency":"USD"}}],"has_more":false}
        """));
    var mapper = new ObjectMapper();
    org.simplepoint.plugin.ai.core.api.spi.AiProviderAdapter adapter = protocol.equals("ANTHROPIC")
        ? new AnthropicProviderAdapter(mapper, new AiModelTypeDetector(), new AiDiscoveredPricingParser(), new AiProperties())
        : new OpenAiProviderAdapter(mapper, new AiModelTypeDetector(), new AiDiscoveredPricingParser(), new AiProperties());
    var connection = new ProviderConnection("exact-provider", AiProviderType.valueOf(protocol), AiProviderVendor.CUSTOM,
        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", null, "isolated-key", true, 5);
    var model = adapter.discoverModels(connection).getFirst();
    assertEquals(new BigDecimal("99999999999.99999999"), model.pricing().inputTokenPrice());
    assertEquals(new BigDecimal("0.12345679"), model.pricing().outputTokenPrice(), "Existing domain HALF_UP normalization remains unchanged");
    org.junit.jupiter.api.Assertions.assertFalse(mapper.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS),
        "Catalog decoding must not reconfigure the shared request mapper");
    org.junit.jupiter.api.Assertions.assertTrue(mapper.readTree(model.metadataJson()).path("pricing").path("input_per_million").isNumber(),
        "Remote metadata keeps numeric JSON types");
  }

  private static void respond(final HttpExchange exchange, final String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @FunctionalInterface
  private interface ExchangeHandler {

    void handle(HttpExchange exchange) throws IOException;
  }
}
