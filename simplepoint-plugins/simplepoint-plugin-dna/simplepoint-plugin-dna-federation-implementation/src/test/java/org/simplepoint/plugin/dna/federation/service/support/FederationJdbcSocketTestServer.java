package org.simplepoint.plugin.dna.federation.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.simplepoint.plugin.dna.federation.api.service.FederationJdbcDriverService;
import org.springframework.test.util.ReflectionTestUtils;

/** Real framed socket transport shared by authentication and domain-error contract tests. */
final class FederationJdbcSocketTestServer implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final FederationJdbcSocketServer server;
  private final int port;

  FederationJdbcSocketTestServer(FederationJdbcDriverService driver) {
    server = new FederationJdbcSocketServer(driver);
    ReflectionTestUtils.setField(server, "enabled", true);
    ReflectionTestUtils.setField(server, "host", "127.0.0.1");
    ReflectionTestUtils.setField(server, "port", 0);
    ReflectionTestUtils.setField(server, "backlog", 10);
    ReflectionTestUtils.setField(server, "maxConnections", 4);
    ReflectionTestUtils.setField(server, "maxConcurrentQueries", 4);
    ReflectionTestUtils.setField(server, "idleTimeoutMs", 5000);
    server.start();
    port = ((ServerSocket) ReflectionTestUtils.getField(server, "serverSocket")).getLocalPort();
  }

  JsonNode request(String... messages) throws Exception {
    if (messages.length == 0) {
      throw new IllegalArgumentException("At least one frame is required");
    }
    try (var socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      var out = new DataOutputStream(socket.getOutputStream());
      var in = new DataInputStream(socket.getInputStream());
      JsonNode response = null;
      for (int index = 0; index < messages.length; index++) {
        byte[] data = messages[index].getBytes(StandardCharsets.UTF_8);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
        int length = in.readInt();
        if (length <= 0 || length > 16 * 1024 * 1024) {
          throw new IllegalStateException("Invalid response frame size");
        }
        response = JSON.readTree(in.readNBytes(length));
        if (index < messages.length - 1 && !response.path("success").asBoolean()) {
          throw new IllegalStateException("Prerequisite socket frame failed");
        }
      }
      return response;
    }
  }

  @Override
  public void close() throws Exception {
    server.destroy();
  }
}
