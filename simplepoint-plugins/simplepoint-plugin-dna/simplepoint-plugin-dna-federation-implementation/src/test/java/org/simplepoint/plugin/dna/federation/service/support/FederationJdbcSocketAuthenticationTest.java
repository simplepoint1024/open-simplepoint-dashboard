package org.simplepoint.plugin.dna.federation.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.federation.api.service.FederationJdbcDriverService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException.Code;
import org.springframework.test.util.ReflectionTestUtils;

class FederationJdbcSocketAuthenticationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CONNECT =
      """
      {"action":"CONNECT","loginSubject":"alice@example.test","password":" secret ",
       "oneTimeCode":"012345","tenantId":"tenant-a","catalogCode":"ds1","contextId":"ctx"}
      """;

  @Test
  void socketPreservesFactorsAndCleansUpProofWhenInitialAuthorizationFails() throws Exception {
    var driver = mock(FederationJdbcDriverService.class);
    var session = mock(FederationJdbcDriverService.DriverSession.class);
    when(driver.openSession(any())).thenReturn(session);
    when(driver.ping(session, "ctx"))
        .thenThrow(new CredentialAuthenticationException(Code.SESSION_INVALID));
    try (var server = new Server(driver)) {
      var response = server.request(CONNECT);
      assertThat(response.path("success").asBoolean()).isFalse();
      assertThat(response.path("sqlState").asText()).isEqualTo("28000");
      assertThat(response.path("errorCode").asText())
          .isEqualTo("IDENTITY_CREDENTIAL:SESSION_INVALID");
      assertThat(response.path("messageKey").asText())
          .isEqualTo("identity.credentials.sessionInvalid");
      assertThat(response.toString()).doesNotContain(" secret ", "012345");
      verify(driver)
          .openSession(
              new FederationJdbcDriverModels.DriverRequest(
                  "alice@example.test", " secret ", "ds1", "tenant-a", "ctx", "012345"));
      verify(session, timeout(1000)).close();
    }
  }

  @Test
  void credentialFailuresHaveStableSqlStatesAndSafeMessages() throws Exception {
    for (var code : Code.values()) {
      var driver = mock(FederationJdbcDriverService.class);
      when(driver.openSession(any())).thenThrow(new CredentialAuthenticationException(code));
      try (var server = new Server(driver)) {
        var response = server.request(CONNECT);
        assertThat(response.path("sqlState").asText())
            .isEqualTo(code == Code.UNAVAILABLE ? "08004" : "28000");
        assertThat(response.path("errorCode").asText()).isEqualTo("IDENTITY_CREDENTIAL:" + code);
        assertThat(response.toString()).doesNotContain(" secret ", "012345", "alice@example.test");
      }
    }
  }

  private static final class Server implements AutoCloseable {
    private final FederationJdbcSocketServer server;
    private final int port;

    private Server(FederationJdbcDriverService driver) {
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

    private JsonNode request(String message) throws Exception {
      try (var socket = new Socket("127.0.0.1", port)) {
        socket.setSoTimeout(5000);
        var out = new DataOutputStream(socket.getOutputStream());
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
        var in = new DataInputStream(socket.getInputStream());
        return JSON.readTree(in.readNBytes(in.readInt()));
      }
    }

    @Override
    public void close() throws Exception {
      server.destroy();
    }
  }
}
