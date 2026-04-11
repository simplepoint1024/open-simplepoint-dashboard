package org.simplepoint.plugin.dna.jdbc.driver;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class DnaJdbcSocketTransportTest {

  @Test
  void connectsOverTcpSocketProtocol() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper()
        .findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    List<String> actions = new CopyOnWriteArrayList<>();
    var executor = Executors.newSingleThreadExecutor();

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      final Future<?> serverFuture = executor.submit(() -> {
        try (
            Socket socket = serverSocket.accept();
            DataInputStream inputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream outputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))
        ) {
          while (true) {
            int length = inputStream.readInt();
            byte[] payload = inputStream.readNBytes(length);
            DnaJdbcModels.SocketRequest request = objectMapper.readValue(payload, DnaJdbcModels.SocketRequest.class);
            actions.add(request.action());
            DnaJdbcModels.SocketResponse response = switch (request.action()) {
               case "CONNECT", "PING" -> new DnaJdbcModels.SocketResponse(
                   true,
                   null,
                   new DnaJdbcModels.PingResult(
                       null,
                       "tenant-a",
                       "ctx-1",
                       "user-1",
                      "alice@example.com",
                      "SimplePoint DNA Federation",
                      "1.0",
                      "reporting"
                  ),
                  null,
                  null,
                  null
              );
              case "TABLE_TYPES" -> new DnaJdbcModels.SocketResponse(
                  true,
                  null,
                  null,
                  new DnaJdbcModels.TabularResult(
                      List.of(new DnaJdbcModels.ColumnDef("TABLE_TYPE", "VARCHAR", java.sql.Types.VARCHAR)),
                      List.of(List.of("TABLE"))
                  ),
                  null,
                  null
              );
              case "CLOSE" -> new DnaJdbcModels.SocketResponse(true, null, null, null, null, null);
              default -> throw new SQLException("unexpected action: " + request.action());
            };
            byte[] responsePayload = objectMapper.writeValueAsBytes(response);
            outputStream.writeInt(responsePayload.length);
            outputStream.write(responsePayload);
            outputStream.flush();
            if ("CLOSE".equals(request.action())) {
              return null;
            }
          }
        }
      });

      Properties properties = new Properties();
      properties.setProperty("user", "alice@example.com");
      properties.setProperty("password", "secret");
      DnaJdbcModels.ConnectionConfig config = DnaJdbcUrlParser.parse(
          "jdbc:simplepoint:dna://127.0.0.1:" + serverSocket.getLocalPort(),
          properties
      );

      try (DnaJdbcClient client = new DnaJdbcClient(config)) {
        DnaJdbcModels.PingResult pingResult = client.ping();
        DnaJdbcModels.TabularResult tableTypes = client.tableTypes();

        assertThat(pingResult.catalogCode()).isNull();
        assertThat(tableTypes.rows()).containsExactly(List.of("TABLE"));
      }

      serverFuture.get();
      assertThat(actions).containsExactly("CONNECT", "TABLE_TYPES", "CLOSE");
    } finally {
      executor.shutdownNow();
    }
  }
}
