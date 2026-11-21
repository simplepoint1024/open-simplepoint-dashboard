package org.simplepoint.plugin.dna.federation.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.federation.api.service.FederationJdbcDriverService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException;
import org.simplepoint.plugin.rbac.core.api.authentication.CredentialAuthenticationException.Code;

class FederationJdbcSocketAuthenticationTest {
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
    try (var server = new FederationJdbcSocketTestServer(driver)) {
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
      try (var server = new FederationJdbcSocketTestServer(driver)) {
        var response = server.request(CONNECT);
        assertThat(response.path("sqlState").asText())
            .isEqualTo(code == Code.UNAVAILABLE ? "08004" : "28000");
        assertThat(response.path("errorCode").asText()).isEqualTo("IDENTITY_CREDENTIAL:" + code);
        assertThat(response.toString()).doesNotContain(" secret ", "012345", "alice@example.test");
      }
    }
  }
}
