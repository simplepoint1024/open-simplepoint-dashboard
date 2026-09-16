package org.simplepoint.plugin.ai.runtime.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class AiRuntimeRestExceptionHandlerTest {

  @Test
  void doesNotExposeAnUnstableRuntimeBusinessReason() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("POST");
    when(request.getRequestURI()).thenReturn("/workbench/runtime/pools");
    AiRuntimeRestExceptionHandler handler =
        new AiRuntimeRestExceptionHandler();

    ResponseEntity<Map<String, Object>> response = handler.status(
        new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "OCI image failed MCP admission:\nprocess exited"
        ),
        request
    );

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry(
        "errorCode",
        "AI_RUNTIME_REQUEST_REJECTED"
    );
    assertThat(response.getBody().toString())
        .doesNotContain("process exited")
        .doesNotContain("OCI image failed MCP admission");
    assertThat(response.getBody()).containsEntry(
        "path",
        "/workbench/runtime/pools"
    );
  }

  @Test
  void preservesStableRuntimeErrorCode() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("POST");
    when(request.getRequestURI()).thenReturn("/workbench/runtime/pools");

    ResponseEntity<Map<String, Object>> response =
        new AiRuntimeRestExceptionHandler().status(
            new ResponseStatusException(
                HttpStatus.CONFLICT,
                "AI_RUNTIME_POOL_OPERATION_CONFLICT",
                new IllegalStateException("private-container-detail")
            ),
            request
        );

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).containsEntry(
        "errorCode",
        "AI_RUNTIME_POOL_OPERATION_CONFLICT"
    );
    assertThat(response.getBody().toString())
        .doesNotContain("private-container-detail");
  }
}
