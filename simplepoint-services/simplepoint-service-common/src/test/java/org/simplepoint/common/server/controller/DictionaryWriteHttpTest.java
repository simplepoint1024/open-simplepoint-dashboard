package org.simplepoint.common.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationException;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.error.DictionaryWriteException;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.simplepoint.plugin.rbac.tenant.rest.controller.DictionaryController;
import org.simplepoint.plugin.rbac.tenant.rest.controller.DictionaryExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DictionaryWriteHttpTest {
  @ParameterizedTest
  @EnumSource(DictionaryWriteException.Code.class)
  void errorsExposeStableNamespacedKeysWithoutDiagnosticCauses(DictionaryWriteException.Code code)
      throws Exception {
    var service = mock(DictionaryService.class);
    var error = new DictionaryWriteException(code);
    error.initCause(new IllegalStateException("internal SQL and secret diagnostic"));
    when(service.create(any(Dictionary.class))).thenThrow(error);
    var mvc =
        MockMvcBuilders.standaloneSetup(new DictionaryController(service))
            .setControllerAdvice(new DictionaryExceptionHandler())
            .build();
    mvc.perform(
            post("/dictionaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"example\",\"name\":\"Name\"}"))
        .andExpect(status().is(code == DictionaryWriteException.Code.CONTEXT_CONFLICT ? 409 : 503))
        .andExpect(jsonPath("$.code").value("DICTIONARY_WRITE:" + code))
        .andExpect(jsonPath("$.messageKey").value(error.messageKey()))
        .andExpect(jsonPath("$.message").doesNotExist())
        .andExpect(jsonPath("$.cause").doesNotExist());
  }

  @ParameterizedTest
  @EnumSource(DictionaryRegistrationException.Code.class)
  void registrationFailuresHaveStablePublicEnvelopes(DictionaryRegistrationException.Code code)
      throws Exception {
    var service = mock(DictionaryService.class);
    var error = new DictionaryRegistrationException(code);
    error.initCause(new IllegalStateException("internal SQL"));
    when(service.create(any(Dictionary.class))).thenThrow(error);
    var mvc =
        MockMvcBuilders.standaloneSetup(new DictionaryController(service))
            .setControllerAdvice(new DictionaryExceptionHandler())
            .build();
    int expectedStatus =
        switch (code) {
          case ACCESS_DENIED -> 403;
          case INVALID_DECLARATION -> 400;
          case CATALOG_UNAVAILABLE -> 503;
          default -> 409;
        };
    mvc.perform(
            post("/dictionaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"example\",\"name\":\"Name\"}"))
        .andExpect(status().is(expectedStatus))
        .andExpect(jsonPath("$.code").value(error.remoteErrorCode()))
        .andExpect(jsonPath("$.messageKey").value(error.messageKey()))
        .andExpect(jsonPath("$.cause").doesNotExist());
  }
}
