package org.simplepoint.plugin.ai.runtime.service.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeOperationException;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;

class HttpAiRuntimeNodeOperationsTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void shouldRetainBoundedRuntimeErrorDetail() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/v1/images/prepare", exchange -> {
      byte[] response = """
          {"status":400,"message":"image digest does not match\\nrequest"}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(400, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    AiRuntimeProperties properties = new AiRuntimeProperties();
    properties.setDispatchTimeout(Duration.ofSeconds(5));
    properties.setMtlsEnabled(false);
    properties.setDispatchInternalHeader("X-Runtime-Token");
    properties.setDispatchInternalToken("test-token");
    HttpAiRuntimeNodeOperations operations =
        new HttpAiRuntimeNodeOperations(properties, new ObjectMapper());

    RuntimeNodeOperationException exception = assertThrows(
        RuntimeNodeOperationException.class,
        () -> operations.prepare(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "example/image@sha256:" + "a".repeat(64)
        )
    );

    assertEquals(400, exception.getStatusCode());
    assertEquals(
        "Tool Runtime returned HTTP 400: image digest does not match request",
        exception.getMessage()
    );
  }
}
