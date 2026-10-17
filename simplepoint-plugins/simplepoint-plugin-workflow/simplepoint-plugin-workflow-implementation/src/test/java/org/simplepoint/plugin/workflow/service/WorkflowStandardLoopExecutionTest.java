package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowNotificationOutboxEntity;

class WorkflowStandardLoopExecutionTest extends WorkflowDirectoryFixture {
  @Test
  void conditionAfterExecutionAlwaysPerformsTheFirstIteration() {
    String definition = publishLoop(false, 5, "${repeat}", false);
    String instance = start(definition, Map.of("repeat", false));
    assertThat(tasks(instance)).hasSize(1);
    complete(instance, Map.of());
    assertCompleted(instance, 1);
  }

  @Test
  void conditionBeforeExecutionCanSkipWithoutCreatingApprovalTasksOrTaskHistory() {
    String definition = publishLoop(true, 5, "${repeat}", false);
    String instance = start(definition, Map.of("repeat", false));
    assertCompleted(instance, 0);
    assertThat(read(() -> service.pendingTasks(null, 0, 20)).totalElements()).isZero();
  }

  @Test
  void editedVariablesControlTheNextIterationAndEveryApprovalRetainsTheOriginalNodeId() {
    String definition = publishLoop(true, 5, "${repeat}", false);
    String instance = start(definition, Map.of("repeat", true));
    String first = tasks(instance).getFirst().getId();
    complete(instance, Map.of());
    assertThat(tasks(instance)).hasSize(1);
    assertThat(tasks(instance).getFirst().getId()).isNotEqualTo(first);
    assertThat(read(() -> service.diagram(instance)).activeActivityIds()).contains("review");
    complete(instance, Map.of("repeat", false));
    assertCompleted(instance, 2);
    assertThat(read(() -> service.nodeHistory(instance, "review", 0, 20)).content())
        .extracting(row -> row.actorName())
        .containsOnly("张三");
  }

  @Test
  void maximumBoundsAnAlwaysTrueConditionAndAnOmittedConditionUsesTheSameBound() {
    for (String condition : List.of("${true}", "")) {
      String definition = publishLoop(false, 2, condition, false);
      String instance = start(definition, Map.of());
      complete(instance, Map.of());
      assertThat(tasks(instance)).hasSize(1);
      complete(instance, Map.of());
      assertCompleted(instance, 2);
    }
  }

  @Test
  void repeatedSubprocessesReenterTheirChildrenWithoutChangingThePersonalReadBoundaries() {
    String definition = publishLoop(false, 2, "${repeat}", true);
    String instance = start(definition, Map.of("repeat", true));
    complete(instance, Map.of());
    assertThat(tasks(instance)).hasSize(1);
    assertThat(read(() -> service.pendingTasks(null, 0, 20)).totalElements()).isEqualTo(1);
    complete(instance, Map.of("repeat", false));
    assertCompleted(instance, 2);
  }

  @Test
  void nestedStandardLoopsRetainIndependentBoundsAndFourDistinctApprovalRecords() {
    String definition =
        publishLoop(
            false,
            2,
            "",
            true,
            source ->
                source.replace(
                    "<bpmn:userTask id=\"review\" flowable:assignee=\"${initiator}\"/>",
                    """
                    <bpmn:userTask id="review" flowable:assignee="${initiator}">
                      <bpmn:standardLoopCharacteristics loopMaximum="2"/>
                    </bpmn:userTask>
                    """));
    String instance = start(definition, Map.of());
    for (int iteration = 0; iteration < 4; iteration++) {
      assertThat(tasks(instance)).hasSize(1);
      complete(instance, Map.of());
    }
    assertCompleted(instance, 4);
    assertThat(read(() -> service.completedTasks(null, 0, 20)).totalElements()).isEqualTo(4);
  }

  @Test
  void withdrawalKeepsApprovedIterationsAndTheWithdrawnPendingIterationInHistory() {
    String definition = publishLoop(false, 5, "", false);
    String instance = start(definition, Map.of());
    complete(instance, Map.of());
    transaction.executeWithoutResult(status -> service.withdraw(instance, "Stop repeated review"));
    assertThat(tasks(instance)).isEmpty();
    assertThat(read(() -> service.nodeHistory(instance, "review", 0, 20)).content())
        .extracting(row -> row.status())
        .containsExactlyInAnyOrder("COMPLETED", "WITHDRAWN");
    assertThat(read(() -> service.completedTasks(null, 0, 20)).totalElements()).isEqualTo(2);
  }

  @Test
  void loopCounterIsTheCompletedCountOfThisLoopAtEveryConditionCheck() {
    for (boolean before : List.of(false, true)) {
      String definition = publishLoop(before, 5, "${loopCounter &lt; 2}", false);
      String instance = start(definition, Map.of());
      complete(instance, Map.of());
      complete(instance, Map.of());
      assertCompleted(instance, 2);
    }
  }

  @Test
  void innerCounterConditionsNeverReadTheOuterIterationOrLeakIntoProcessVariables() {
    String definition = publishLoop(true, 5, "${loopCounter &lt; 2}", true, source -> source.replace(
        "<bpmn:userTask id=\"review\" flowable:assignee=\"${initiator}\"/>", """
        <bpmn:userTask id="review" flowable:assignee="${initiator}">
          <bpmn:standardLoopCharacteristics testBefore="true" loopMaximum="5">
            <bpmn:loopCondition>${loopCounter &lt; 2}</bpmn:loopCondition>
          </bpmn:standardLoopCharacteristics>
        </bpmn:userTask>
        """));
    String instance = start(definition, Map.of());
    for (int iteration = 0; iteration < 4; iteration++) {
      assertThat(engine.getRuntimeService().getVariable(instance, "loopCounter")).isNull();
      complete(instance, Map.of());
    }
    assertCompleted(instance, 4);
  }

  @Test
  void nonBooleanBeforeConditionRollsBackWithoutLeavingAnInstanceOrNotifications() {
    String definition = publishLoop(true, 2, "${repeat}", false);
    assertThatThrownBy(() -> start(definition, Map.of("repeat", "true")))
        .isInstanceOfSatisfying(
            WorkflowModelingException.class,
            error -> {
              assertThat(error.getMessageKey()).isEqualTo("workflow.modeling.loopCondition");
              assertThat(error.getElementId()).isEqualTo("review");
            });
    assertThat(count(WorkflowInstanceEntity.class)).isZero();
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isZero();
    assertThat(engine.getTaskService().createTaskQuery().count()).isZero();
  }

  @Test
  void invalidAfterConditionKeepsTheOriginalPendingTaskUntilTheInputIsCorrected() {
    String definition = publishLoop(false, 2, "${repeat}", false);
    String instance = start(definition, Map.of("repeat", 1));
    String task = tasks(instance).getFirst().getId();
    assertThatThrownBy(() -> complete(instance, Map.of()))
        .isInstanceOf(WorkflowModelingException.class);
    assertThat(tasks(instance)).extracting(Task::getId).containsExactly(task);
    assertThat(read(() -> service.completedTasks(null, 0, 20)).totalElements()).isZero();
    complete(instance, Map.of("repeat", false));
    assertCompleted(instance, 1);
  }

  @Test
  void publicationPinsBothSourceAndCompiledResourcesAndSurvivesEngineCacheReload()
      throws Exception {
    String definition = publishLoop(false, 2, "${repeat}", false);
    var version = read(() -> service.version(definition, 1));
    assertThat(version.bpmnXml()).contains("standardLoopCharacteristics").doesNotContain("spc:");
    var process =
        engine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .processDefinitionKey("standardReview")
            .singleResult();
    String deployed;
    try (var resource =
        engine
            .getRepositoryService()
            .getResourceAsStream(process.getDeploymentId(), "standardReview.bpmn20.xml")) {
      deployed = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(deployed)
        .contains("multiInstanceLoopCharacteristics", BpmnExecutionCompiler.NAMESPACE)
        .doesNotContain("standardLoopCharacteristics");
    assertThat(engine.getRepositoryService().getDeploymentResourceNames(process.getDeploymentId()))
        .contains(BpmnExecutionCompiler.MANIFEST_RESOURCE);
    assertThat(
            read(() -> service.publish(definition, new PublishDefinitionCommand(2, null)))
                .version())
        .isEqualTo(1);
    var configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    configuration.getProcessDefinitionCache().clear();
    String instance = start(definition, Map.of("repeat", true));
    complete(instance, Map.of());
    complete(instance, Map.of("repeat", false));
    assertCompleted(instance, 2);
    assertThat(read(() -> service.version(definition, 1)).bpmnXml()).isEqualTo(version.bpmnXml());
  }

  @Test
  void legacyLoopRequiresExplicitRepublishingAndKeepsItsHistoricalVersionUntouched() {
    String definition = publishLoop(false, 2, "", false);
    String source = read(() -> service.version(definition, 1)).bpmnXml();
    var legacy =
        engine
            .getRepositoryService()
            .createDeployment()
            .tenantId("tenant")
            .addString("standardReview.bpmn20.xml", source)
            .deploy();
    String processId =
        engine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .deploymentId(legacy.getId())
            .singleResult()
            .getId();
    final String runningLegacy =
        read(
            () ->
                engine
                    .getRuntimeService()
                    .startProcessInstanceById(processId, Map.of("initiator", "actor"))
                    .getId());
    // Seed a pre-compiler immutable version in this isolated database. Production never updates it.
    transaction.executeWithoutResult(
        status ->
            em.createNativeQuery(
                    "update simpoint_workflow_definition_versions set"
                        + " flowable_deployment_id=:deployment,"
                        + " flowable_process_definition_id=:process where"
                        + " definition_id=:definition")
                .setParameter("deployment", legacy.getId())
                .setParameter("process", processId)
                .setParameter("definition", definition)
                .executeUpdate());
    for (Runnable action :
        List.<Runnable>of(
            () -> start(definition, Map.of()),
            () -> read(() -> service.startForm(definition, 1)))) {
      assertThatThrownBy(action::run)
          .isInstanceOfSatisfying(
              WorkflowModelingException.class,
              error -> {
                assertThat(error.getMessageKey()).isEqualTo("workflow.modeling.loopRepublish");
                assertThat(error.getElementId()).isEqualTo("review");
              });
    }
    assertThat(count(WorkflowInstanceEntity.class)).isZero();
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isZero();
    assertThat(
            read(() -> service.publish(definition, new PublishDefinitionCommand(2, null)))
                .version())
        .isEqualTo(2);
    assertThat(read(() -> service.version(definition, 1)).bpmnXml()).isEqualTo(source);
    assertThat(read(() -> service.version(definition, 2)).bpmnXml()).isEqualTo(source);
    // Engine caches and active historical executions still use the original deployment semantics.
    read(
        () -> {
          engine.getTaskService().complete(tasks(runningLegacy).getFirst().getId());
          return null;
        });
    assertThat(
            engine
                .getRuntimeService()
                .createProcessInstanceQuery()
                .processInstanceId(runningLegacy)
                .count())
        .isZero();
    assertThat(
            engine
                .getHistoryService()
                .createHistoricTaskInstanceQuery()
                .processInstanceId(runningLegacy)
                .count())
        .isEqualTo(1);
    // Selecting the original version stays guarded even after a new version exists.
    assertThatThrownBy(() -> start(definition, Map.of()))
        .isInstanceOf(WorkflowModelingException.class);
    String instance =
        read(
            () ->
                service
                    .start(
                        definition,
                        new StartProcessCommand(2, null, "New loop version", Map.of(), null))
                    .id());
    complete(instance, Map.of());
    complete(instance, Map.of());
    assertCompleted(instance, 2);
  }

  private String publishLoop(boolean before, int maximum, String condition, boolean subprocess) {
    return publishLoop(before, maximum, condition, subprocess, UnaryOperator.identity());
  }

  private String publishLoop(
      boolean before,
      int maximum,
      String condition,
      boolean subprocess,
      UnaryOperator<String> transform) {
    return read(
        () -> {
          String key = "standardReview";
          // Two cases in one test use distinct definition keys, without changing activity
          // identities.
          long existing = engine.getRepositoryService().createProcessDefinitionQuery().count();
          if (existing > 0) {
            key += existing;
          }
          var draft = service.createDefinition(new CreateDefinitionCommand(key, key, null, null));
          String loop =
              "<bpmn:standardLoopCharacteristics testBefore=\""
                  + before
                  + "\" loopMaximum=\""
                  + maximum
                  + "\">"
                  + (condition.isEmpty()
                      ? ""
                      : "<bpmn:loopCondition>" + condition + "</bpmn:loopCondition>")
                  + "</bpmn:standardLoopCharacteristics>";
          String activity =
              subprocess
                  ? """
              <bpmn:subProcess id="repeatScope">
                %s
                <bpmn:startEvent id="innerStart"/>
                <bpmn:sequenceFlow id="innerEnter" sourceRef="innerStart" targetRef="review"/>
                <bpmn:userTask id="review" flowable:assignee="${initiator}"/>
                <bpmn:sequenceFlow id="innerExit" sourceRef="review" targetRef="innerEnd"/>
                <bpmn:endEvent id="innerEnd"/>
              </bpmn:subProcess>
              """
                      .formatted(loop)
                  : "<bpmn:userTask id=\"review\" flowable:assignee=\"${initiator}\">"
                      + loop
                      + "</bpmn:userTask>";
          String target = subprocess ? "repeatScope" : "review";
          String xml =
              """
              <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn"
                  targetNamespace="https://simplepoint.org/workflow">
                <bpmn:process id="%s" isExecutable="true">
                  <bpmn:startEvent id="start"/>
                  <bpmn:sequenceFlow id="enter" sourceRef="start" targetRef="%s"/>
                  %s
                  <bpmn:sequenceFlow id="exit" sourceRef="%s" targetRef="end"/>
                  <bpmn:endEvent id="end"/>
                </bpmn:process>
              </bpmn:definitions>
              """
                  .formatted(key, target, activity, target);
          service.updateDraft(
              draft.definition().id(),
              new UpdateDraftCommand(1, key, null, null, transform.apply(xml), null));
          service.publish(draft.definition().id(), new PublishDefinitionCommand(2, null));
          return draft.definition().id();
        });
  }

  private String start(String definition, Map<String, Object> variables) {
    return read(
        () ->
            service
                .start(
                    definition, new StartProcessCommand(1, null, "Repeat review", variables, null))
                .id());
  }

  private List<Task> tasks(String instance) {
    return engine.getTaskService().createTaskQuery().processInstanceId(instance).list();
  }

  private void complete(String instance, Map<String, Object> variables) {
    String task = tasks(instance).getFirst().getId();
    transaction.executeWithoutResult(
        status -> service.complete(task, new CompleteTaskCommand(variables, "Review iteration")));
  }

  private void assertCompleted(String instance, long historyCount) {
    assertThat(tasks(instance)).isEmpty();
    assertThat(read(() -> service.diagram(instance)).instance().status()).isEqualTo("COMPLETED");
    assertThat(read(() -> service.nodeHistory(instance, "review", 0, 20)).totalElements())
        .isEqualTo(historyCount);
  }

  private <T> T read(Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }
}
