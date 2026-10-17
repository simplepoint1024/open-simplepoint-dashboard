package org.simplepoint.app.form.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;

class ApplicationFormExceptionHandlerTest {
  @Test
  void everyDomainFailureKeepsItsCodeAndDiagnosticWithANamespacedTranslationKey() {
    var handler = new ApplicationFormExceptionHandler();
    for (var code : ApplicationFormErrorCode.values()) {
      var response = handler.formFailure(new ApplicationFormException(code, "safe diagnostic"));
      assertThat(response.getStatusCode().isError()).isTrue();
      assertThat(response.getBody().code()).isEqualTo(code.name());
      assertThat(response.getBody().message()).isEqualTo("safe diagnostic");
      assertThat(response.getBody().messageKey()).isEqualTo("app.error." + code.name());
    }
  }
}
