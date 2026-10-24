package org.simplepoint.app.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simplepoint.service.router.exception.ServiceRouteNotFoundException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

class ApplicationFormExceptionHandlerTest {
  @Test
  void typedTransportFailuresReturn503WithoutLeakingRemoteDiagnostics() throws Exception {
    for (RuntimeException failure :
        List.of(
            new ServiceRouteNotFoundException("private-service", "private-version"),
            new ResourceAccessException(
                "submitted-secret-value", new IOException("private-endpoint")),
            new RestClientException(
                "private-response-details",
                new java.net.SocketTimeoutException("private-endpoint")))) {
      var mvc =
          MockMvcBuilders.standaloneSetup(new UnavailableController(failure))
              .setControllerAdvice(new ApplicationFormExceptionHandler())
              .build();
      var response =
          mvc.perform(get("/transport-test"))
              .andExpect(status().isServiceUnavailable())
              .andExpect(jsonPath("$.code").value("RUNTIME_UNAVAILABLE"))
              .andExpect(jsonPath("$.messageKey").value("app.error.RUNTIME_UNAVAILABLE"))
              .andReturn()
              .getResponse();
      assertThat(response.getContentAsString())
          .doesNotContain("private-", "submitted-secret-value");
    }
  }

  @Test
  void malformedRemoteResponseIsA502WithNoSubmittedValues() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new UnavailableController(
                    new RestClientException("private-response-and-submitted-values")))
            .setControllerAdvice(new ApplicationFormExceptionHandler())
            .build();
    var response =
        mvc.perform(get("/transport-test"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("RECORD_OPERATION_FAILED"))
            .andExpect(jsonPath("$.messageKey").value("app.error.RECORD_OPERATION_FAILED"))
            .andReturn()
            .getResponse();
    assertThat(response.getContentAsString())
        .doesNotContain("private-response-and-submitted-values");
  }

  @RestController
  static class UnavailableController {
    private final RuntimeException failure;

    UnavailableController(RuntimeException failure) {
      this.failure = failure;
    }

    @GetMapping("/transport-test")
    String read() {
      throw failure;
    }
  }

  @Test
  void everyDomainFailureKeepsItsCodeDiagnosticAndNamespacedTranslationKey() {
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
