package org.simplepoint.dna.model.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplepoint.plugin.dna.federation.api.error.DnaModelErrorCode;
import org.simplepoint.plugin.dna.federation.api.error.DnaModelException;

class DnaModelExceptionHandlerTest {
  @ParameterizedTest
  @EnumSource(DnaModelErrorCode.class)
  void allDomainDiagnosticsKeepHttpSemanticsWithoutExposingValues(DnaModelErrorCode code) {
    var result =
        new DnaModelExceptionHandler()
            .modelFailure(new DnaModelException(code, "Private SQL or connection diagnostics"));
    assertThat(result.getStatusCode().value())
        .isEqualTo(
            switch (code) {
              case MODEL_INVALID -> 400;
              case MODEL_NOT_FOUND -> 404;
              case MODEL_VERSION_CONFLICT -> 409;
            });
    assertThat(result.getBody().code()).isEqualTo(code.name());
    assertThat(result.getBody().messageKey()).isEqualTo("dna.models.error." + code.name());
    assertThat(result.getBody().toString()).doesNotContain("Private");
  }
}
