package org.simplepoint.plugin.dna.federation.rest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;
import org.simplepoint.plugin.dna.federation.api.service.FederationSqlConsoleService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FederationSqlWriteExceptionHandlerTest {
  @ParameterizedTest
  @EnumSource(Code.class)
  void writeRejectionsUseTypedLocalizedEnvelopeOnBothMounts(Code code) throws Exception {
    var service = mock(FederationSqlConsoleService.class);
    var failure = new FederationSqlWriteException(code);
    failure.addSuppressed(new IllegalStateException("private SQL audit diagnostic"));
    when(service.smartExecute(any())).thenThrow(failure);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new FederationSqlConsoleController(service, new ObjectMapper()))
            .setControllerAdvice(new FederationSqlWriteExceptionHandler())
            .build();
    for (var prefix :
        java.util.List.of(
            DnaFederationPaths.SQL_CONSOLE, DnaFederationPaths.PLATFORM_SQL_CONSOLE)) {
      mvc.perform(
              post(prefix + "/execute")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"catalogCode\":\"ds\",\"sql\":\"DELETE FROM secret\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(code.name()))
          .andExpect(
              jsonPath("$.messageKey").value("dna.federation.sql-console.error." + code.name()))
          .andExpect(jsonPath("$.timestamp").exists())
          .andExpect(jsonPath("$.message").doesNotExist())
          .andExpect(jsonPath("$.cause").doesNotExist())
          .andExpect(jsonPath("$.suppressed").doesNotExist());
    }
  }
}
