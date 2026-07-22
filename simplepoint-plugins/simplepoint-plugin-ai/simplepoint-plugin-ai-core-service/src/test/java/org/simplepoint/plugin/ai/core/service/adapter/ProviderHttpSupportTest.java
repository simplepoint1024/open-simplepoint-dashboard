package org.simplepoint.plugin.ai.core.service.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.exception.AiProviderRequestException;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;

class ProviderHttpSupportTest {

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void blocksLoopbackAndPrivateDestinationsByDefault() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderHttpSupport.validateDestination(URI.create("http://127.0.0.1/v1"), false)
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderHttpSupport.validateDestination(URI.create("http://10.0.0.8/v1"), false)
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderHttpSupport.validateDestination(URI.create("http://[fd00::8]/v1"), false)
    );
  }

  @Test
  void allowsPrivateDestinationOnlyWhenExplicitlyEnabled() {
    assertDoesNotThrow(
        () -> ProviderHttpSupport.validateDestination(URI.create("http://127.0.0.1/v1"), true)
    );
  }

  @Test
  void blocksAdditionalReservedIpv4Ranges() throws Exception {
    assertTrue(ProviderHttpSupport.isRestrictedAddress(InetAddress.getByName("100.64.0.1")));
    assertTrue(ProviderHttpSupport.isRestrictedAddress(InetAddress.getByName("198.18.0.1")));
    assertTrue(ProviderHttpSupport.isRestrictedAddress(InetAddress.getByName("240.0.0.1")));
  }

  @Test
  void rejectsCredentialsEmbeddedInProviderUrl() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderHttpSupport.endpoint("https://user:password@example.com/v1", "/models")
    );
  }

  @Test
  void rejectsOversizedSynchronousResponse() throws Exception {
    startServer(exchange -> respond(exchange, "123456789"));
    AiProperties properties = new AiProperties();
    properties.setProviderMaxResponseBytes(8L);
    ProviderHttpSupport support = new ProviderHttpSupport(properties);

    AiProviderRequestException error = assertThrows(
        AiProviderRequestException.class,
        () -> support.send(request("/response"), true)
    );

    assertEquals(502, error.getProviderStatus());
    assertTrue(error.getMessage().contains("8 字节"));
  }

  @Test
  void rejectsOversizedStreamingLine() throws Exception {
    startServer(exchange -> respond(exchange, "data: 123456789\n\n"));
    AiProperties properties = new AiProperties();
    properties.setProviderMaxStreamLineCharacters(10);
    ProviderHttpSupport support = new ProviderHttpSupport(properties);

    AiProviderRequestException error = assertThrows(
        AiProviderRequestException.class,
        () -> support.stream(
            request("/stream-line"),
            true,
            line -> {},
            new AiStreamCancellation()
        )
    );

    assertTrue(error.getMessage().contains("单行超过 10 个字符"));
  }

  @Test
  void cancellationClosesActiveProviderStream() throws Exception {
    CountDownLatch firstLineWritten = new CountDownLatch(1);
    CountDownLatch releaseServer = new CountDownLatch(1);
    startServer(exchange -> {
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
      exchange.getResponseBody().flush();
      firstLineWritten.countDown();
      try {
        releaseServer.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    ProviderHttpSupport support = new ProviderHttpSupport(new AiProperties());
    AiStreamCancellation cancellation = new AiStreamCancellation();
    CountDownLatch firstLineConsumed = new CountDownLatch(1);
    final CompletableFuture<Void> stream = CompletableFuture.runAsync(() -> support.stream(
        request("/cancel"),
        true,
        line -> firstLineConsumed.countDown(),
        cancellation
    ));

    assertTrue(firstLineWritten.await(2, TimeUnit.SECONDS));
    assertTrue(firstLineConsumed.await(2, TimeUnit.SECONDS));
    cancellation.cancel();
    releaseServer.countDown();
    CompletionException error = assertThrows(CompletionException.class, stream::join);

    assertTrue(error.getCause() instanceof CancellationException);
  }

  private void startServer(final ExchangeHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> handler.handle(exchange));
    server.start();
  }

  private HttpRequest request(final String path) {
    return HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
        .GET()
        .build();
  }

  private static void respond(final HttpExchange exchange, final String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @FunctionalInterface
  private interface ExchangeHandler {

    void handle(HttpExchange exchange) throws IOException;
  }
}
