package org.simplepoint.workflow.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;
import org.springframework.http.HttpStatus;

class WorkflowModelingErrorTest {
  private final WorkflowExceptionHandler handler = new WorkflowExceptionHandler();

  @Test
  void modelingErrorsExposeStableTranslationKeysAndCanvasElementsWithoutVariableValues() {
    var response = handler.workflow(new WorkflowModelingException("multiCount", "次数不正确", "review"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .containsEntry("code", "WORKFLOW_VALIDATION")
        .containsEntry("messageKey", "workflow.modeling.multiCount")
        .containsEntry("elementId", "review")
        .containsOnlyKeys("code", "message", "messageKey", "elementId", "timestamp");
  }

  @Test
  void existingDomainErrorsRetainTheirStatusAndEnvelopeWithoutPretendingToTargetNodes() {
    var response =
        handler.workflow(new WorkflowException(WorkflowException.Code.FORBIDDEN, "Not allowed"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody())
        .containsEntry("code", "WORKFLOW_FORBIDDEN")
        .containsEntry("messageKey", "workflow.error.FORBIDDEN")
        .containsOnlyKeys("code", "message", "messageKey", "timestamp");
  }
}
