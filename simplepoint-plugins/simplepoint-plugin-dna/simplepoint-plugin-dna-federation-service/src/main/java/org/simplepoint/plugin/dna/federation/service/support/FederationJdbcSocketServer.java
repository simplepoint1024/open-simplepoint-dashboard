package org.simplepoint.plugin.dna.federation.service.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.simplepoint.plugin.dna.federation.api.service.FederationJdbcDriverService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Dedicated TCP socket server used by the DNA JDBC driver.
 */
@Component
public class FederationJdbcSocketServer implements DisposableBean {

  private static final Logger LOGGER = LoggerFactory.getLogger(FederationJdbcSocketServer.class);

  private static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;

  private final FederationJdbcDriverService driverService;

  private final ObjectMapper objectMapper;

  private final ExecutorService acceptExecutor;

  private final ExecutorService connectionExecutor;

  private final AtomicBoolean started;

  @Value("${simplepoint.dna.jdbc.socket.enabled:true}")
  private boolean enabled;

  @Value("${simplepoint.dna.jdbc.socket.host:0.0.0.0}")
  private String host;

  @Value("${simplepoint.dna.jdbc.socket.port:15432}")
  private int port;

  @Value("${simplepoint.dna.jdbc.socket.backlog:50}")
  private int backlog;

  @Value("${simplepoint.dna.jdbc.socket.max-connections:200}")
  private int maxConnections;

  @Value("${simplepoint.dna.jdbc.socket.idle-timeout:300000}")
  private int idleTimeoutMs;

  private volatile boolean running;

  private volatile ServerSocket serverSocket;

  /**
   * Creates the JDBC socket server bound to the DNA JDBC driver service facade.
   *
   * @param driverService JDBC driver service facade
   */
  public FederationJdbcSocketServer(final FederationJdbcDriverService driverService) {
    this.driverService = driverService;
    this.objectMapper = new ObjectMapper()
        .findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    this.acceptExecutor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "dna-jdbc-socket-accept");
      thread.setDaemon(true);
      return thread;
    });
    this.connectionExecutor = new ThreadPoolExecutor(
        4,
        200,
        60L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(64),
        runnable -> {
          Thread thread = new Thread(runnable, "dna-jdbc-socket-client");
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.CallerRunsPolicy()
    );
    this.started = new AtomicBoolean(false);
  }

  /**
   * Starts the JDBC socket listener after the DNA application is ready.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (!enabled || !started.compareAndSet(false, true)) {
      return;
    }
    if (connectionExecutor instanceof ThreadPoolExecutor pool) {
      pool.setMaximumPoolSize(maxConnections);
    }
    try {
      ServerSocket socket = new ServerSocket();
      socket.bind(new InetSocketAddress(host, port), backlog);
      this.serverSocket = socket;
      this.running = true;
      this.acceptExecutor.execute(this::acceptLoop);
      LOGGER.info("DNA JDBC socket server listening on {}:{}", host, port);
    } catch (IOException ex) {
      this.started.set(false);
      throw new IllegalStateException("启动 DNA JDBC Socket 服务失败", ex);
    }
  }

  private void acceptLoop() {
    while (running) {
      try {
        Socket socket = serverSocket.accept();
        connectionExecutor.execute(() -> handleConnection(socket));
      } catch (SocketException ex) {
        if (running) {
          LOGGER.error("DNA JDBC Socket accept 失败", ex);
        }
        return;
      } catch (IOException ex) {
        LOGGER.error("DNA JDBC Socket accept 失败", ex);
      }
    }
  }

  private void handleConnection(final Socket socket) {
    try (
        Socket client = socket;
        DataInputStream inputStream = new DataInputStream(new BufferedInputStream(client.getInputStream()));
        DataOutputStream outputStream = new DataOutputStream(new BufferedOutputStream(client.getOutputStream()))
    ) {
      client.setSoTimeout(idleTimeoutMs);
      ConnectionSession session = null;
      try {
        while (running && !client.isClosed()) {
          SocketRequest request = readRequest(inputStream);
          if (request == null) {
            return;
          }
          SocketResponse response;
          try {
            RequestOutcome outcome = handleRequest(session, request);
            closeReplacedSession(session, outcome.session());
            session = outcome.session();
            response = outcome.response();
          } catch (AccessDeniedException | IllegalArgumentException | IllegalStateException ex) {
            response = SocketResponse.error(rootMessage(ex));
          } catch (RuntimeException ex) {
            LOGGER.warn("DNA JDBC Socket request failed: {}", rootMessage(ex));
            response = SocketResponse.error(rootMessage(ex));
          }
          writeResponse(outputStream, response);
          if ("CLOSE".equalsIgnoreCase(request.action())) {
            return;
          }
        }
      } finally {
        closeSession(session);
      }
    } catch (IOException ex) {
      LOGGER.debug("DNA JDBC Socket connection closed: {}", rootMessage(ex));
    }
  }

  private RequestOutcome handleRequest(final ConnectionSession session, final SocketRequest request) {
    String action = normalizeAction(request.action());
    if ("CONNECT".equals(action)) {
      FederationJdbcDriverModels.DriverRequest driverRequest = new FederationJdbcDriverModels.DriverRequest(
          request.loginSubject(),
          request.password(),
          request.catalogCode(),
          request.tenantId(),
          request.contextId()
      );
      FederationJdbcDriverService.DriverSession driverSession = driverService.openSession(driverRequest);
      FederationJdbcDriverModels.PingResult pingResult = driverService.ping(driverSession, request.contextId());
      ConnectionSession nextSession = new ConnectionSession(driverSession, pingResult.contextId());
      return new RequestOutcome(nextSession, SocketResponse.ping(pingResult));
    }
      ConnectionSession requiredSession = requireSession(session);
      String contextId = resolveContextId(requiredSession, request.contextId());
      return switch (action) {
      case "PING" -> {
        FederationJdbcDriverModels.PingResult pingResult = driverService.ping(requiredSession.driverSession(), contextId);
        yield new RequestOutcome(requiredSession.withContextId(pingResult.contextId()), SocketResponse.ping(pingResult));
      }
      case "CATALOGS" -> new RequestOutcome(
          requiredSession,
          SocketResponse.tabular(driverService.catalogs(requiredSession.driverSession(), contextId))
      );
      case "SCHEMAS" -> new RequestOutcome(
          requiredSession,
          SocketResponse.tabular(driverService.schemas(
              requiredSession.driverSession(),
              contextId,
              request.catalogPattern(),
              request.schemaPattern()
          ))
      );
      case "TABLE_TYPES" -> new RequestOutcome(
          requiredSession,
          SocketResponse.tabular(driverService.tableTypes(requiredSession.driverSession(), contextId))
      );
      case "TABLES" -> new RequestOutcome(
          requiredSession,
          SocketResponse.tabular(driverService.tables(
              requiredSession.driverSession(),
              contextId,
              request.catalogPattern(),
              request.schemaPattern(),
              request.tablePattern(),
              request.types()
          ))
      );
      case "COLUMNS" -> new RequestOutcome(
          requiredSession,
          SocketResponse.tabular(driverService.columns(
              requiredSession.driverSession(),
              contextId,
              request.catalogPattern(),
              request.schemaPattern(),
              request.tablePattern(),
              request.columnPattern()
          ))
      );
      case "PRIMARY_KEYS" -> new RequestOutcome(
          requiredSession,
          SocketResponse.tabular(driverService.primaryKeys(
              requiredSession.driverSession(),
              contextId,
              request.catalogPattern(),
              request.schemaPattern(),
              request.tablePattern()
          ))
      );
      case "INDEX_INFO" -> new RequestOutcome(
          requiredSession,
          SocketResponse.tabular(driverService.indexInfo(
              requiredSession.driverSession(),
              contextId,
              request.catalogPattern(),
              request.schemaPattern(),
              request.tablePattern(),
              Boolean.TRUE.equals(request.unique()),
              !Boolean.FALSE.equals(request.approximate())
          ))
      );
      case "IMPORTED_KEYS" -> new RequestOutcome(
          requiredSession,
          SocketResponse.tabular(driverService.importedKeys(
              requiredSession.driverSession(),
              contextId,
              request.catalogPattern(),
              request.schemaPattern(),
              request.tablePattern()
          ))
      );
      case "EXPORTED_KEYS" -> new RequestOutcome(
          requiredSession,
          SocketResponse.tabular(driverService.exportedKeys(
              requiredSession.driverSession(),
              contextId,
              request.catalogPattern(),
              request.schemaPattern(),
              request.tablePattern()
          ))
      );
      case "TYPE_INFO" -> new RequestOutcome(
          requiredSession,
          SocketResponse.tabular(driverService.typeInfo(requiredSession.driverSession(), contextId))
      );
      case "QUERY" -> new RequestOutcome(
          requiredSession,
          SocketResponse.query(driverService.query(
              requiredSession.driverSession(),
              contextId,
              new FederationJdbcDriverModels.QueryRequest(request.sql(), request.defaultSchema(), request.catalogCode())
          ))
      );
      case "EXECUTE_UPDATE" -> new RequestOutcome(
          requiredSession,
          SocketResponse.update(driverService.executeUpdate(
              requiredSession.driverSession(),
              contextId,
              new FederationJdbcDriverModels.QueryRequest(request.sql(), request.defaultSchema(), request.catalogCode())
          ))
      );
      case "EXECUTE_DDL" -> new RequestOutcome(
          requiredSession,
          SocketResponse.update(driverService.executeDdl(
              requiredSession.driverSession(),
              contextId,
              new FederationJdbcDriverModels.QueryRequest(request.sql(), request.defaultSchema(), request.catalogCode())
          ))
      );
      case "CLOSE" -> new RequestOutcome(requiredSession, SocketResponse.ok());
      case "FLUSH_CACHE" -> {
        driverService.flushCache(requiredSession.driverSession());
        yield new RequestOutcome(requiredSession, SocketResponse.ok());
      }
      default -> throw new IllegalArgumentException("不支持的 DNA JDBC Socket 操作: " + action);
    };
  }

  private SocketRequest readRequest(final DataInputStream inputStream) throws IOException {
    int length;
    try {
      length = inputStream.readInt();
    } catch (EOFException ex) {
      return null;
    }
    if (length <= 0 || length > MAX_FRAME_SIZE) {
      throw new IOException("DNA JDBC Socket 请求长度不合法: " + length);
    }
    byte[] payload = inputStream.readNBytes(length);
    if (payload.length != length) {
      throw new EOFException("DNA JDBC Socket 请求已中断");
    }
    return objectMapper.readValue(payload, SocketRequest.class);
  }

  private void writeResponse(
      final DataOutputStream outputStream,
      final SocketResponse response
  ) throws IOException {
    byte[] payload = objectMapper.writeValueAsBytes(response);
    outputStream.writeInt(payload.length);
    outputStream.write(payload);
    outputStream.flush();
  }

  private static String normalizeAction(final String action) {
    if (action == null || action.isBlank()) {
      throw new IllegalArgumentException("DNA JDBC Socket 请求缺少 action");
    }
    return action.trim().toUpperCase(java.util.Locale.ROOT);
  }

  private static ConnectionSession requireSession(final ConnectionSession session) {
    if (session == null) {
      throw new IllegalStateException("DNA JDBC Socket 连接尚未认证，请先发送 CONNECT");
    }
    return session;
  }

  private static String resolveContextId(final ConnectionSession session, final String requestContextId) {
    return requestContextId == null || requestContextId.isBlank() ? session.contextId() : requestContextId;
  }

  private void closeReplacedSession(final ConnectionSession previous, final ConnectionSession next) {
    if (previous == null) {
      return;
    }
    if (next != null && previous.driverSession() == next.driverSession()) {
      return;
    }
    closeSession(previous);
  }

  private void closeSession(final ConnectionSession session) {
    if (session == null) {
      return;
    }
    try {
      session.driverSession().close();
    } catch (RuntimeException ex) {
      LOGGER.debug("DNA JDBC Socket session cleanup failed: {}", rootMessage(ex));
    }
  }

  private static String rootMessage(final Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
  }

  /**
   * Stops the JDBC socket listener and releases background executors.
   */
  @Override
  public void destroy() throws Exception {
    running = false;
    ServerSocket socket = serverSocket;
    if (socket != null && !socket.isClosed()) {
      socket.close();
    }
    acceptExecutor.shutdownNow();
    connectionExecutor.shutdownNow();
  }

  private record ConnectionSession(
      FederationJdbcDriverService.DriverSession driverSession,
      String contextId
  ) {

    private ConnectionSession withContextId(final String resolvedContextId) {
      return resolvedContextId == null || resolvedContextId.isBlank()
          ? this
          : new ConnectionSession(driverSession, resolvedContextId);
    }
  }

  private record RequestOutcome(ConnectionSession session, SocketResponse response) {
  }

  private record SocketRequest(
      String action,
      String loginSubject,
      String password,
      String catalogCode,
      String tenantId,
      String contextId,
      String schema,
      String catalogPattern,
      String schemaPattern,
      String tablePattern,
      String columnPattern,
      List<String> types,
      String sql,
      String defaultSchema,
      Boolean unique,
      Boolean approximate
  ) {
  }

  private record SocketResponse(
      Boolean success,
      String errorMessage,
      FederationJdbcDriverModels.PingResult pingResult,
      FederationJdbcDriverModels.TabularResult tabularResult,
      FederationQueryModels.SqlQueryResult queryResult,
      FederationQueryModels.SqlUpdateResult updateResult
  ) {

    private static SocketResponse ok() {
      return new SocketResponse(true, null, null, null, null, null);
    }

    private static SocketResponse error(final String errorMessage) {
      return new SocketResponse(false, errorMessage, null, null, null, null);
    }

    private static SocketResponse ping(final FederationJdbcDriverModels.PingResult pingResult) {
      return new SocketResponse(true, null, pingResult, null, null, null);
    }

    private static SocketResponse tabular(final FederationJdbcDriverModels.TabularResult tabularResult) {
      return new SocketResponse(true, null, null, tabularResult, null, null);
    }

    private static SocketResponse query(final FederationQueryModels.SqlQueryResult queryResult) {
      return new SocketResponse(true, null, null, null, queryResult, null);
    }

    private static SocketResponse update(final FederationQueryModels.SqlUpdateResult updateResult) {
      return new SocketResponse(true, null, null, null, null, updateResult);
    }
  }
}
