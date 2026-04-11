package org.simplepoint.plugin.dna.jdbc.driver;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DnaJdbcSocketTransport implements AutoCloseable {

  private static final int DEFAULT_PORT = 15432;

  private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;

  private static final int DEFAULT_READ_TIMEOUT_MS = 30000;

  private static final Logger LOGGER = Logger.getLogger(DnaJdbcSocketTransport.class.getName());

  private final ObjectMapper objectMapper;

  private final URI baseUri;

  private final String loginSubject;

  private final String password;

  private final String catalogCode;

  private final String tenantId;

  private final String configuredContextId;

  private final String configuredSchema;

  private final Socket socket;

  private final DataInputStream inputStream;

  private final DataOutputStream outputStream;

  private String sessionContextId;

  private boolean connected;

  DnaJdbcSocketTransport(final DnaJdbcModels.ConnectionConfig config) throws SQLException {
    this.objectMapper = new ObjectMapper()
        .findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    this.baseUri = config.baseUri();
    this.loginSubject = config.loginSubject();
    this.password = config.password();
    this.catalogCode = config.catalogCode();
    this.tenantId = config.tenantId();
    this.configuredContextId = config.contextId();
    this.configuredSchema = config.schema();
    int connectTimeout = resolveProperty(config, "connectTimeout", DEFAULT_CONNECT_TIMEOUT_MS);
    int readTimeout = resolveProperty(config, "socketTimeout", DEFAULT_READ_TIMEOUT_MS);
    try {
      this.socket = new Socket();
      this.socket.setKeepAlive(true);
      this.socket.setTcpNoDelay(true);
      this.socket.connect(
          new InetSocketAddress(baseUri.getHost(), resolvePort(baseUri)),
          connectTimeout
      );
      this.socket.setSoTimeout(readTimeout);
      this.inputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
      this.outputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    } catch (IOException ex) {
      throw new SQLException("DNA JDBC Socket 连接失败", "08001", ex);
    }
  }

  DnaJdbcModels.PingResult ping() throws SQLException {
    if (!connected) {
      DnaJdbcModels.SocketResponse response = send(new DnaJdbcModels.SocketRequest(
          "CONNECT",
          loginSubject,
          password,
          catalogCode,
          tenantId,
          configuredContextId,
          configuredSchema,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null
      ));
      DnaJdbcModels.PingResult result = requireSuccess(response).pingResult();
      this.connected = true;
      if (result != null && result.contextId() != null && !result.contextId().isBlank()) {
        this.sessionContextId = result.contextId();
      }
      return result;
    }
    DnaJdbcModels.SocketResponse response = send(new DnaJdbcModels.SocketRequest(
        "PING",
        null,
        null,
        null,
        null,
        sessionContextId,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
    ));
    DnaJdbcModels.PingResult result = requireSuccess(response).pingResult();
    if (result != null && result.contextId() != null && !result.contextId().isBlank()) {
      this.sessionContextId = result.contextId();
    }
    return result;
  }

  DnaJdbcModels.TabularResult catalogs() throws SQLException {
    return requireSuccess(send(new DnaJdbcModels.SocketRequest(
        "CATALOGS",
        null,
        null,
        null,
        null,
        sessionContextId,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
    ))).tabularResult();
  }

  DnaJdbcModels.TabularResult schemas(
      final String catalogPattern,
      final String schemaPattern
  ) throws SQLException {
    return requireSuccess(send(new DnaJdbcModels.SocketRequest(
        "SCHEMAS",
        null,
        null,
        null,
        null,
        sessionContextId,
        null,
        catalogPattern,
        schemaPattern,
        null,
        null,
        null,
        null,
        null,
        null,
        null
    ))).tabularResult();
  }

  DnaJdbcModels.TabularResult tableTypes() throws SQLException {
    return requireSuccess(send(new DnaJdbcModels.SocketRequest(
        "TABLE_TYPES",
        null,
        null,
        null,
        null,
        sessionContextId,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
    ))).tabularResult();
  }

  DnaJdbcModels.TabularResult tables(
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final List<String> types
  ) throws SQLException {
    return requireSuccess(send(new DnaJdbcModels.SocketRequest(
        "TABLES",
        null,
        null,
        null,
        null,
        sessionContextId,
        null,
        catalogPattern,
        schemaPattern,
        tablePattern,
        null,
        types,
        null,
        null,
        null,
        null
    ))).tabularResult();
  }

  DnaJdbcModels.TabularResult columns(
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern,
      final String columnPattern
  ) throws SQLException {
    return requireSuccess(send(new DnaJdbcModels.SocketRequest(
        "COLUMNS",
        null,
        null,
        null,
        null,
        sessionContextId,
        null,
        catalogPattern,
        schemaPattern,
        tablePattern,
        columnPattern,
        null,
        null,
        null,
        null,
        null
    ))).tabularResult();
  }

  DnaJdbcModels.TabularResult primaryKeys(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return requireSuccess(send(new DnaJdbcModels.SocketRequest(
        "PRIMARY_KEYS",
        null,
        null,
        null,
        null,
        sessionContextId,
        null,
        catalog,
        schema,
        table,
        null,
        null,
        null,
        null,
        null,
        null
    ))).tabularResult();
  }

  DnaJdbcModels.TabularResult indexInfo(
      final String catalog,
      final String schema,
      final String table,
      final boolean unique,
      final boolean approximate
  ) throws SQLException {
    return requireSuccess(send(new DnaJdbcModels.SocketRequest(
        "INDEX_INFO",
        null,
        null,
        null,
        null,
        sessionContextId,
        null,
        catalog,
        schema,
        table,
        null,
        null,
        null,
        null,
        unique,
        approximate
    ))).tabularResult();
  }

  DnaJdbcModels.TabularResult importedKeys(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return requireSuccess(send(new DnaJdbcModels.SocketRequest(
        "IMPORTED_KEYS",
        null,
        null,
        null,
        null,
        sessionContextId,
        null,
        catalog,
        schema,
        table,
        null,
        null,
        null,
        null,
        null,
        null
    ))).tabularResult();
  }

  DnaJdbcModels.TabularResult exportedKeys(
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    return requireSuccess(send(new DnaJdbcModels.SocketRequest(
        "EXPORTED_KEYS",
        null,
        null,
        null,
        null,
        sessionContextId,
        null,
        catalog,
        schema,
        table,
        null,
        null,
        null,
        null,
        null,
        null
    ))).tabularResult();
  }

  DnaJdbcModels.TabularResult typeInfo() throws SQLException {
    return requireSuccess(send(new DnaJdbcModels.SocketRequest(
        "TYPE_INFO",
        null,
        null,
        null,
        null,
        sessionContextId,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
    ))).tabularResult();
  }

  DnaJdbcModels.QueryResult query(
      final String catalogCode,
      final String sql,
      final String defaultSchema
  ) throws SQLException {
    return requireSuccess(send(new DnaJdbcModels.SocketRequest(
        "QUERY",
        null,
        null,
        catalogCode,
        null,
        sessionContextId,
        null,
        null,
        null,
        null,
        null,
        null,
        sql,
        defaultSchema,
        null,
        null
    ))).queryResult();
  }

  void flushCache() throws SQLException {
    requireSuccess(send(new DnaJdbcModels.SocketRequest(
        "FLUSH_CACHE",
        null,
        null,
        null,
        null,
        sessionContextId,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
    )));
  }

  String catalogCode() {
    return catalogCode;
  }

  String loginSubject() {
    return loginSubject;
  }

  /**
   * Temporarily adjusts the socket read timeout (e.g. for query timeout enforcement).
   * Pass 0 to reset to the default configured timeout.
   */
  void setSocketTimeout(final int timeoutMs) throws SQLException {
    try {
      socket.setSoTimeout(timeoutMs);
    } catch (IOException ex) {
      throw new SQLException("设置 socket 超时失败", "HY000", ex);
    }
  }

  @Override
  public void close() throws SQLException {
    try {
      if (!socket.isClosed()) {
        try {
          send(new DnaJdbcModels.SocketRequest(
              "CLOSE",
              null,
              null,
              null,
              null,
              sessionContextId,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null
          ));
        } catch (SQLException ex) {
          LOGGER.log(Level.FINE, "Ignoring DNA JDBC Socket CLOSE handshake failure during shutdown", ex);
        }
      }
    } finally {
      try {
        socket.close();
      } catch (IOException ex) {
        throw new SQLException("关闭 DNA JDBC Socket 失败", ex);
      }
    }
  }

  private DnaJdbcModels.SocketResponse send(final DnaJdbcModels.SocketRequest request) throws SQLException {
    try {
      byte[] payload = objectMapper.writeValueAsBytes(request);
      outputStream.writeInt(payload.length);
      outputStream.write(payload);
      outputStream.flush();
      int length = inputStream.readInt();
      if (length < 0) {
        throw new SQLException("DNA JDBC Socket 响应长度不合法", "08006");
      }
      byte[] responseBytes = inputStream.readNBytes(length);
      if (responseBytes.length != length) {
        throw new SQLException("DNA JDBC Socket 响应已中断 (期望 " + length + " 字节, 实际 "
            + responseBytes.length + " 字节)", "08006");
      }
      return objectMapper.readValue(responseBytes, DnaJdbcModels.SocketResponse.class);
    } catch (java.net.SocketTimeoutException ex) {
      throw new SQLException("DNA JDBC Socket 通信超时", "08006", ex);
    } catch (IOException ex) {
      throw new SQLException("DNA JDBC Socket 通信失败: " + ex.getMessage(), "08006", ex);
    }
  }

  private static DnaJdbcModels.SocketResponse requireSuccess(final DnaJdbcModels.SocketResponse response) throws SQLException {
    if (response == null || !Boolean.TRUE.equals(response.success())) {
      throw new SQLException(
          response == null ? "DNA JDBC Socket 返回空响应" : response.errorMessage(),
          "HY000"
      );
    }
    return response;
  }

  private static int resolvePort(final URI uri) {
    return uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;
  }

  private static int resolveProperty(
      final DnaJdbcModels.ConnectionConfig config,
      final String key,
      final int defaultValue
  ) {
    if (config.properties() == null) {
      return defaultValue;
    }
    String value = config.properties().getProperty(key);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed > 0 ? parsed : defaultValue;
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }
}
