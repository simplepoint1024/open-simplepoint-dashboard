package org.simplepoint.installation.host;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.composition.ModuleEventPlan;

class BrokerInstallationTest {
  @Test
  void managementCredentialsRequireAnExplicitTrustedOrigin() {
    for (String uri : new String[] {"https:///missing-host", "https://broker:0", "https://broker:65536",
        "http://external-broker", "https://user:secret@broker", "https://broker/api", "https://broker?token=x"}) {
      assertThrows(IllegalArgumentException.class, () -> new BrokerInstallation(properties(uri)));
    }
    assertDoesNotThrow(() -> new BrokerInstallation(properties("https://broker.internal")));
    assertDoesNotThrow(() -> new BrokerInstallation(properties("http://127.0.0.1:15672")));
  }

  @Test
  void oversizedReadStopsBeforeAnyProvisioning() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var writes = new java.util.concurrent.atomic.AtomicInteger();
    server.createContext("/", exchange -> {
      if (!exchange.getRequestMethod().equals("GET")) {
        writes.incrementAndGet();
      }
      try (exchange) {
        byte[] oversized = new byte[1048577];
        java.util.Arrays.fill(oversized, (byte) ' ');
        exchange.sendResponseHeaders(200, oversized.length);
        exchange.getResponseBody().write(oversized);
      }
    });
    server.start();
    try {
      var installer = new BrokerInstallation(properties("http://127.0.0.1:" + server.getAddress().getPort()));
      var failure = assertThrows(IllegalStateException.class,
          () -> installer.prepare(new ModuleEventPlan(Map.of()), Map.of()));
      assertEquals("Broker response exceeds limit", failure.getMessage());
      assertEquals(0, writes.get());
    } finally {
      server.stop(0);
    }
  }

  private Properties properties(String endpoint) {
    var properties = new Properties();
    properties.setProperty("installation.broker.management-uri", endpoint);
    properties.setProperty("installation.broker.username", "installer");
    properties.setProperty("installation.broker.password", "fixture-only");
    properties.setProperty("spring.rabbitmq.virtual-host", "/fixture");
    return properties;
  }
}
