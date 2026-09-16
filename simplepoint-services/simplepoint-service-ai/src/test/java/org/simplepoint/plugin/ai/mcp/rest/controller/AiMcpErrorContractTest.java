package org.simplepoint.plugin.ai.mcp.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpInvocationService;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpPublicationRuntimeService;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpPublicationService;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpTaskService;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AiMcpErrorContractTest {

  private static final String PRIVATE_DETAIL =
      "private-upstream-authentication-detail";

  @Test
  void serverDefinitionErrorUsesStableCodeOnly() {
    AiMcpServerDefinitionService service =
        mock(AiMcpServerDefinitionService.class);
    when(service.listTools("server-a")).thenThrow(
        new IllegalArgumentException(PRIVATE_DETAIL)
    );

    var response = new AiMcpServerDefinitionController(service)
        .tools("server-a");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isEqualTo(Map.of(
        "errorCode", "AI_MCP_SERVER_REQUEST_INVALID"
    ));
    assertThat(String.valueOf(response.getBody()))
        .doesNotContain(PRIVATE_DETAIL);
  }

  @Test
  void publicationErrorUsesStableCodeOnly() {
    AiMcpPublicationService service = mock(AiMcpPublicationService.class);
    when(service.findActiveById("publication-a")).thenThrow(
        new IllegalStateException(PRIVATE_DETAIL)
    );
    var controller = new AiMcpPublicationController(
        service,
        mock(AiMcpPublicationRuntimeService.class)
    );

    var response = controller.find("publication-a");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isEqualTo(Map.of(
        "errorCode", "AI_MCP_PUBLICATION_OPERATION_CONFLICT"
    ));
    assertThat(String.valueOf(response.getBody()))
        .doesNotContain(PRIVATE_DETAIL);
  }

  @Test
  void invocationStatusReasonDoesNotExposeServiceMessage() {
    AiMcpInvocationService service = mock(AiMcpInvocationService.class);
    when(service.findAll(any(), any(Pageable.class))).thenThrow(
        new IllegalArgumentException(PRIVATE_DETAIL)
    );
    var controller = new AiMcpInvocationController(service);

    assertThatThrownBy(() -> controller.findAll(null, Pageable.unpaged()))
        .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
          assertThat(exception.getReason())
              .isEqualTo("AI_MCP_INVOCATION_REQUEST_INVALID");
          assertThat(exception.getReason()).doesNotContain(PRIVATE_DETAIL);
        });
  }

  @Test
  void internalTaskHandlerReturnsOnlyStableErrorCode() {
    var controller = new AiMcpPublicationInternalController(
        mock(AiMcpPublicationRuntimeService.class),
        mock(AiMcpTaskService.class)
    );

    Map<String, String> body = controller.invalidTaskRequest(
        new IllegalArgumentException(PRIVATE_DETAIL)
    );

    assertThat(body).containsOnly(
        Map.entry("errorCode", "AI_MCP_TASK_REQUEST_INVALID")
    );
    assertThat(body.toString()).doesNotContain(PRIVATE_DETAIL);
  }
}
