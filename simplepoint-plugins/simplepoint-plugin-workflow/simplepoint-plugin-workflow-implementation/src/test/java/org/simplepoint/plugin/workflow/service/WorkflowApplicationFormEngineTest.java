package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.FormWorkflowSubmissionCheck;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormWorkflowPort;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.api.service.WorkflowFormProvider;

/** Real engine, transaction and business-link constraints, with the remote APP authority as the only stub. */
class WorkflowApplicationFormEngineTest extends WorkflowEngineFixture {
  private final ApplicationFormWorkflowPort forms = mock(ApplicationFormWorkflowPort.class);

  @Override
  protected List<WorkflowFormProvider> formProviders() {
    return List.of(new ApplicationFormWorkflowProvider(forms));
  }

  @Test
  void bindingDirectoryTaskSnapshotAndImmutableFactsShareOneVersionedBusinessIdentity() {
    String definition = publishBound();
    verify(forms).validateBinding(any());
    var choices = transaction.execute(status -> service.boundDefinitions("simplepoint-app", "form", 1, "", 0, 20));
    assertThat(choices.totalElements()).isEqualTo(1);
    assertThat(choices.content().getFirst().id()).isEqualTo(definition);
    assertThat(transaction.execute(status -> service.boundDefinitions("simplepoint-app", "other", 1, "", 0, 20)).totalElements()).isZero();
    String business = "app-form:" + "a".repeat(64);
    var command = new StartProcessCommand(1, business, "采购申请", Map.of("amount", "9007199254740993.01"), List.of(), "start");
    var instance = start(definition, command);
    var replay = start(definition, command);
    assertThat(replay).isEqualTo(instance);
    assertThatThrownBy(() -> start(definition, new StartProcessCommand(1, business, "重复业务", command.variables(), List.of(), "other-key")))
        .isInstanceOf(WorkflowException.class);
    var task = engine.getTaskService().createTaskQuery().processInstanceId(instance.id()).singleResult();
    var runtime = transaction.execute(status -> service.taskForm(task.getId()));
    assertThat(runtime.binding().provider()).isEqualTo("simplepoint-app");
    assertThat(runtime.values()).doesNotContainKey("amount");
    assertThat(runtime.protectedVariables()).containsExactly("amount");
    assertThatThrownBy(() -> transaction.execute(status -> service.complete(task.getId(), new CompleteTaskCommand(
        Map.of("amount", "1"), "篡改金额", "bad")))).isInstanceOf(WorkflowException.class);
    assertThat(engine.getTaskService().createTaskQuery().taskId(task.getId()).count()).isEqualTo(1);
    var completed = transaction.execute(status -> service.complete(task.getId(), new CompleteTaskCommand(Map.of(), "同意", "approve")));
    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(instance.id())
        .variableName("amount").singleResult().getValue()).isEqualTo(new java.math.BigDecimal("9007199254740993.01"));
  }

  @Test
  void revokedBusinessPageAuthorityPreventsTheTaskTransactionFromCommitting() {
    String definition = publishBound();
    doAnswer(invocation -> {
      FormWorkflowSubmissionCheck check = invocation.getArgument(0);
      if ("task".equals(check.stage())) {
        throw new ApplicationFormException(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "Page access revoked");
      }
      return null;
    }).when(forms).validateSubmission(any());
    var instance = start(definition, new StartProcessCommand(1, "app-form:" + "b".repeat(64), "权限验证", Map.of("amount", "10.00"), List.of(), "start"));
    String task = engine.getTaskService().createTaskQuery().processInstanceId(instance.id()).singleResult().getId();
    assertThatThrownBy(() -> transaction.execute(status -> service.complete(task,
        new CompleteTaskCommand(Map.of(), "同意", "approve")))).isInstanceOfSatisfying(WorkflowException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(WorkflowException.Code.FORBIDDEN));
    assertThat(engine.getTaskService().createTaskQuery().taskId(task).count()).isEqualTo(1);
  }

  private org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary start(
      String definition, StartProcessCommand command) {
    return WorkflowBusinessSubmissionScope.call(() -> transaction.execute(status -> service.start(definition, command)));
  }

  @Test
  void publicWorkflowStartCannotRaceOrReplaceAnAcceptedAppIntent() {
    String definition = publishBound();
    assertThatThrownBy(() -> transaction.execute(status -> service.start(definition,
        new StartProcessCommand(1, "app-form:" + "c".repeat(64), "伪造业务入口",
            Map.of("amount", "1.00"), List.of(), "forged"))))
        .isInstanceOfSatisfying(WorkflowException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(WorkflowException.Code.FORBIDDEN));
    assertThat(engine.getRuntimeService().createProcessInstanceQuery().count()).isZero();
    assertThat(WorkflowBusinessSubmissionScope.active()).isFalse();
  }

  private String publishBound() {
    return transaction.execute(status -> {
      var draft = service.createDefinition(new CreateDefinitionCommand("formApproval", "表单审批", null, null));
      var binding = new WorkflowFormContract.Binding("simplepoint-app", "form", 1, Map.of("total", "amount"), "main");
      var contract = new WorkflowFormContract(1, List.of(new WorkflowFormContract.Variable(
          "amount", "金额", "decimal", "both", true, null)), binding, Map.of());
      service.updateDraft(draft.definition().id(), new UpdateDraftCommand(1, "表单审批", null, null, draft.bpmnXml(), contract));
      service.publish(draft.definition().id(), new PublishDefinitionCommand(2, null));
      return draft.definition().id();
    });
  }
}
