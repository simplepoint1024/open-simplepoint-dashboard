package org.simplepoint.plugin.dna.federation.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;
import org.simplepoint.plugin.dna.federation.api.service.FederationJdbcDriverService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels.PingResult;

class FederationJdbcSocketWriteErrorTest {
  private static final String CONNECT =
      """
      {"action":"CONNECT","loginSubject":"writer","catalogCode":"ds","contextId":"ctx"}
      """;
  private static final String UPDATE =
      """
      {"action":"EXECUTE_UPDATE","catalogCode":"ds","contextId":"ctx",
       "sql":"DELETE FROM private_table"}
      """;

  @ParameterizedTest
  @EnumSource(Code.class)
  void writeErrorsKeepLocalizationAndDoNotExposeDeepDiagnostics(Code code) throws Exception {
    var driver = mock(FederationJdbcDriverService.class);
    var session = mock(FederationJdbcDriverService.DriverSession.class);
    when(driver.openSession(any())).thenReturn(session);
    when(driver.ping(session, "ctx"))
        .thenReturn(new PingResult("ds", "tenant", "ctx", "user", "writer", "DNA", "1", "ds"));
    var failure = new FederationSqlWriteException(code);
    failure.initCause(new IllegalStateException("private-physical-query-and-values"));
    failure.addSuppressed(new IllegalStateException("private-audit-diagnostic"));
    when(driver.executeUpdate(eq(session), eq("ctx"), any())).thenThrow(failure);
    try (var server = new FederationJdbcSocketTestServer(driver)) {
      for (boolean batch : new boolean[] {false, true}) {
        String frame =
            batch
                ? "{\"action\":\"BATCH\",\"contextId\":\"ctx\",\"batch\":[" + UPDATE + "]}"
                : UPDATE;
        var response = server.request(CONNECT, frame);
        assertThat(response.toString())
            .doesNotContain("private_table", "private-physical", "private-audit");
        var error = batch ? response.path("batchResults").get(0) : response;
        assertThat(error.path("success").asBoolean()).isFalse();
        assertThat(error.path("errorCode").asText()).isEqualTo(code.name());
        assertThat(error.path("messageKey").asText()).isEqualTo(failure.messageKey());
        assertThat(error.path("errorMessage").asText()).isEqualTo(failure.getMessage());
        assertThat(error.path("sqlState").asText())
            .isEqualTo(
                switch (code) {
                  case SQL_WRITE_PROTECTED -> "42501";
                  case SQL_WRITE_OWNERSHIP_UNAVAILABLE -> "55000";
                  default -> "42000";
                });
      }
    }
  }
}
