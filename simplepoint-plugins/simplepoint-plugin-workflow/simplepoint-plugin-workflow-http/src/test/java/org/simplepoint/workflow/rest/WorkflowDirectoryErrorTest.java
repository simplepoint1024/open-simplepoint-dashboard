package org.simplepoint.workflow.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplepoint.plugin.workflow.api.WorkflowException;

class WorkflowDirectoryErrorTest {
  @ParameterizedTest
  @EnumSource(
      value = WorkflowException.Code.class,
      names = {"DIRECTORY_PREPARING", "DIRECTORY_UNAVAILABLE"})
  void signalsBoundedReadRetryWithoutChangingCommandFailureSemantics(WorkflowException.Code code) {
    var response =
        new WorkflowExceptionHandler()
            .workflow(new WorkflowException(code, "Directory unavailable"));
    assertEquals(503, response.getStatusCode().value());
    assertEquals("workflow.error." + code, response.getBody().get("messageKey"));
    assertEquals(true, response.getBody().get("retryable"));
    assertEquals(
        code == WorkflowException.Code.DIRECTORY_PREPARING ? "2" : "5",
        response.getHeaders().getFirst("Retry-After"));
    var conflict =
        new WorkflowExceptionHandler()
            .workflow(new WorkflowException(WorkflowException.Code.CONFLICT, "Conflict"));
    assertEquals(409, conflict.getStatusCode().value());
    assertFalse(conflict.getBody().containsKey("retryable"));
  }
}
