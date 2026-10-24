package org.simplepoint.plugin.rbac.core.api.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.service.CredentialAuthenticationService;
import org.simplepoint.remoting.RemoteOperation;

class CredentialContractTest {
  @Test
  void proofRoundTripPreservesTimeAndOnlyContainsThePublicIdentity() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var identity =
        new CredentialIdentity("alice", 123, false, Instant.parse("2026-10-20T10:20:30.123Z"));
    var session = new CredentialSession("a".repeat(43), identity);
    var json = mapper.writeValueAsString(session);
    assertThat(mapper.readValue(json, CredentialSession.class)).isEqualTo(session);
    assertThat(json).doesNotContain("password", "twoFactor", "secret", "roles");
    assertThat(session.toString()).doesNotContain(session.handle());
    assertThat(new CredentialRequest("alice", "secret", "012345").toString())
        .doesNotContain("secret", "012345", "alice");
    assertThatThrownBy(
            () ->
                mapper.readValue("{\"handle\":\"bad\",\"identity\":null}", CredentialSession.class))
        .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
  }

  @Test
  void canonicalErrorsCannotEchoRemoteDiagnosticsAndOpenCannotBeAutomaticallyRetried()
      throws Exception {
    var decoder = new CredentialAuthenticationException.Decoder();
    for (var code : CredentialAuthenticationException.Code.values()) {
      var original = new CredentialAuthenticationException(code);
      var decoded =
          (CredentialAuthenticationException)
              decoder.decode(original.remoteErrorCode(), "private-password");
      assertThat(decoded.code()).isEqualTo(code);
      assertThat(decoded.messageKey()).startsWith("identity.credentials.");
      assertThat(decoded.getMessage()).doesNotContain("private-password");
    }
    assertThat(decoder.decode("OTHER:INVALID", "secret")).isNull();
    assertThat(decoder.decode("IDENTITY_CREDENTIAL:UNKNOWN", "secret")).isNull();
    assertThat(
            CredentialAuthenticationService.class
                .getMethod("open", CredentialRequest.class)
                .getAnnotation(RemoteOperation.class))
        .isNull();
    assertThat(
            CredentialAuthenticationService.class
                .getMethod("check", String.class)
                .getAnnotation(RemoteOperation.class)
                .idempotent())
        .isTrue();
  }
}
