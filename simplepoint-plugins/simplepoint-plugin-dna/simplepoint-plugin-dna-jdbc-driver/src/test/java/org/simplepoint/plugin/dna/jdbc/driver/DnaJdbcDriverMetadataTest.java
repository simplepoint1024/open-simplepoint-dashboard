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
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class DnaJdbcDriverMetadataTest {

  @Test
  void shouldPassNullPatternsToBackendWhenCallerOmitsThem() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper()
        .findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    List<DnaJdbcModels.SocketRequest> requests = new CopyOnWriteArrayList<>();
    var executor = Executors.newSingleThreadExecutor();

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      Future<?> serverFuture = executor.submit(() -> {
        try (
            Socket socket = serverSocket.accept();
            DataInputStream inputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream outputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))
        ) {
          while (true) {
            int length = inputStream.readInt();
            byte[] payload = inputStream.readNBytes(length);
            DnaJdbcModels.SocketRequest request = objectMapper.readValue(payload, DnaJdbcModels.SocketRequest.class);
            requests.add(request);
            DnaJdbcModels.SocketResponse response = switch (request.action()) {
              case "CONNECT" -> new DnaJdbcModels.SocketResponse(
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
                      null
                  ),
                  null,
                  null,
                  null,
                  null
              );
              case "TABLES" -> new DnaJdbcModels.SocketResponse(
                  true,
                  null,
                  null,
                  new DnaJdbcModels.TabularResult(
                      List.of(
                          new DnaJdbcModels.ColumnDef("TABLE_CAT", "VARCHAR", java.sql.Types.VARCHAR),
                          new DnaJdbcModels.ColumnDef("TABLE_SCHEM", "VARCHAR", java.sql.Types.VARCHAR),
                          new DnaJdbcModels.ColumnDef("TABLE_NAME", "VARCHAR", java.sql.Types.VARCHAR),
                          new DnaJdbcModels.ColumnDef("TABLE_TYPE", "VARCHAR", java.sql.Types.VARCHAR)
                      ),
                      List.of(List.of("PG", "reporting", "orders", "TABLE"))
                  ),
                  null,
                  null,
                  null
              );
              case "CLOSE" -> new DnaJdbcModels.SocketResponse(true, null, null, null, null, null, null);
              default -> throw new IllegalStateException("unexpected action: " + request.action());
            };
            byte[] responsePayload = objectMapper.writeValueAsBytes(response);
            outputStream.writeInt(responsePayload.length);
            outputStream.write(responsePayload);
            outputStream.flush();
            if ("CLOSE".equals(request.action())) {
              return null;
            }
          }
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      });

      Properties properties = new Properties();
      properties.setProperty("user", "alice@example.com");
      properties.setProperty("password", "secret");

      try (Connection connection = new DnaJdbcDriver().connect(
          "jdbc:simplepoint:dna://127.0.0.1:" + serverSocket.getLocalPort(),
          properties
      )) {
        connection.setCatalog("PG");
        connection.setSchema("reporting");

        // Per JDBC spec, getTables(null, null, ...) should request ALL catalogs/schemas
        try (ResultSet resultSet = connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
          assertThat(resultSet.next()).isTrue();
          assertThat(resultSet.getString("TABLE_CAT")).isEqualTo("PG");
          assertThat(resultSet.getString("TABLE_SCHEM")).isEqualTo("reporting");
          assertThat(resultSet.getString("TABLE_NAME")).isEqualTo("orders");
        }

        assertThat(connection.getCatalog()).isEqualTo("PG");
        assertThat(connection.getSchema()).isEqualTo("reporting");
      }

      serverFuture.get();
      DnaJdbcModels.SocketRequest tablesRequest = requests.stream()
          .filter(request -> "TABLES".equals(request.action()))
          .findFirst()
          .orElseThrow();
      // null patterns should be passed through as null, not substituted with currentCatalog/currentSchema
      assertThat(tablesRequest.catalogPattern()).isNull();
      assertThat(tablesRequest.schemaPattern()).isNull();
    } finally {
      executor.shutdownNow();
    }
  }
}
