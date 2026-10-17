package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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

/**
 * Modeling acceptance uses the actual publish, transaction, engine and personal read boundaries.
 */
class WorkflowModelingExecutionTest extends WorkflowDirectoryFixture {
  @Test
  void parallelGatewayWaitsForBothApprovalsAndKeepsBothInPersonalHistory() {
    String instance = start("parallelReview", branches("parallelGateway", ""), Map.of());
    var tasks = tasks(instance);
    assertThat(tasks).hasSize(2);
    assertThat(read(() -> service.pendingTasks(null, 0, 20)).totalElements()).isEqualTo(2);
    complete(tasks.getFirst(), "First branch");
    assertThat(read(() -> service.diagram(instance)).instance().status()).isEqualTo("RUNNING");
    assertThat(tasks(instance)).hasSize(1);
    complete(tasks.getLast(), "Second branch");
    assertThat(read(() -> service.diagram(instance)).instance().status()).isEqualTo("COMPLETED");
    assertThat(read(() -> service.completedTasks(null, 0, 20)).totalElements()).isEqualTo(2);
  }

  @Test
  void inclusiveGatewayJoinsOnlyActivatedBranchesWithoutWaitingForAnUnselectedBranch() {
    String body =
        branches(
            "inclusiveGateway",
            """
            <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">
              ${extra}
            </bpmn:conditionExpression>
            """);
    String instance = start("inclusiveSingle", body, Map.of("extra", false));
    assertThat(tasks(instance)).extracting(Task::getTaskDefinitionKey).containsExactly("review");
    complete(tasks(instance).getFirst(), "Only selected branch");
    assertThat(read(() -> service.diagram(instance)).instance().status()).isEqualTo("COMPLETED");
    String both = start("inclusiveBoth", body, Map.of("extra", true));
    assertThat(tasks(both)).hasSize(2);
    complete(tasks(both).getFirst(), "First selected branch");
    assertThat(read(() -> service.diagram(both)).instance().status()).isEqualTo("RUNNING");
    complete(tasks(both).getFirst(), "Last selected branch");
    assertThat(read(() -> service.diagram(both)).instance().status()).isEqualTo("COMPLETED");
  }

  @Test
  void nestedSubprocessKeepsTheSamePersonalTaskAndProgressBoundaries() {
    String body =
        """
        <bpmn:startEvent id="start"/>
        <bpmn:sequenceFlow id="enter" sourceRef="start" targetRef="outer"/>
        <bpmn:subProcess id="outer" name="部门审批">
          <bpmn:startEvent id="outer_start"/>
          <bpmn:sequenceFlow id="outer_enter" sourceRef="outer_start" targetRef="inner"/>
          <bpmn:subProcess id="inner" name="嵌套审批">
            <bpmn:startEvent id="inner_start"/>
            <bpmn:sequenceFlow id="inner_enter" sourceRef="inner_start" targetRef="review"/>
            <bpmn:userTask id="review" name="内部审批" flowable:assignee="${initiator}"/>
            <bpmn:sequenceFlow id="inner_exit" sourceRef="review" targetRef="inner_end"/>
            <bpmn:endEvent id="inner_end"/>
          </bpmn:subProcess>
          <bpmn:sequenceFlow id="outer_exit" sourceRef="inner" targetRef="outer_end"/>
          <bpmn:endEvent id="outer_end"/>
        </bpmn:subProcess>
        <bpmn:sequenceFlow id="exit" sourceRef="outer" targetRef="end"/>
        <bpmn:endEvent id="end"/>
        """;
    String instance = start("nestedReview", body, Map.of());
    assertThat(read(() -> service.pendingTasks(null, 0, 20)).totalElements()).isEqualTo(1);
    assertThat(read(() -> service.diagram(instance)).activeActivityIds()).contains("review");
    complete(tasks(instance).getFirst(), "Nested approval");
    var diagram = read(() -> service.diagram(instance));
    assertThat(diagram.instance().status()).isEqualTo("COMPLETED");
    assertThat(diagram.activeActivityIds()).isEmpty();
    assertThat(read(() -> service.nodeHistory(instance, "review", 0, 20)).content())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.actorName()).isEqualTo("张三");
              assertThat(row.comment()).isEqualTo("Nested approval");
            });
  }

  @Test
  void sequentialMultiInstanceCreatesTasksInOrderAndKeepsDistinctOccurrences() {
    String instance =
        start(
            "sequentialReview",
            multiInstance(true),
            Map.of("reviewers", List.of("actor", "actor")));
    Task first = tasks(instance).getFirst();
    assertThat(tasks(instance)).hasSize(1);
    complete(first, "First occurrence");
    assertThat(tasks(instance)).hasSize(1);
    assertThat(tasks(instance).getFirst().getId()).isNotEqualTo(first.getId());
    complete(tasks(instance).getFirst(), "Second occurrence");
    assertThat(read(() -> service.diagram(instance)).instance().status()).isEqualTo("COMPLETED");
    assertThat(read(() -> service.nodeHistory(instance, "review", 0, 20)).content())
        .hasSize(2)
        .extracting(row -> row.taskId())
        .doesNotHaveDuplicates();
  }

  @Test
  void parallelMultiInstanceListsAllCurrentOccurrencesAndWaitsForTheirCompletion() {
    String instance =
        start("multiReview", multiInstance(false), Map.of("reviewers", List.of("actor", "actor")));
    var pending = tasks(instance);
    assertThat(pending).hasSize(2);
    assertThat(read(() -> service.nodeHistory(instance, "review", 0, 20, true)).totalElements())
        .isEqualTo(2);
    complete(pending.getFirst(), "First concurrent occurrence");
    assertThat(read(() -> service.nodeHistory(instance, "review", 0, 20, true)).totalElements())
        .isEqualTo(1);
    assertThat(read(() -> service.diagram(instance)).activeActivityIds()).contains("review");
    complete(pending.getLast(), "Second concurrent occurrence");
    assertThat(read(() -> service.diagram(instance)).instance().status()).isEqualTo("COMPLETED");
    assertThat(read(() -> service.completedTasks(null, 0, 20)).totalElements()).isEqualTo(2);
  }

  @Test
  void withdrawalTerminatesEveryParallelOccurrenceWithoutRewritingCompletedWork() {
    String instance =
        start(
            "withdrawMulti", multiInstance(false), Map.of("reviewers", List.of("actor", "actor")));
    complete(tasks(instance).getFirst(), "Already approved");
    transaction.executeWithoutResult(status -> service.withdraw(instance, "No longer needed"));
    assertThat(tasks(instance)).isEmpty();
    var history = read(() -> service.nodeHistory(instance, "review", 0, 20));
    assertThat(history.content())
        .extracting(row -> row.status())
        .containsExactlyInAnyOrder("COMPLETED", "WITHDRAWN");
  }

  @Test
  void earlyParallelCompletionCancelsRemainingTasksAndKeepsTheirPersonalHistory() {
    String body =
        multiInstance(false)
            .replace(
                "flowable:elementVariable=\"reviewer\"/>",
                """
                flowable:elementVariable="reviewer">
                  <bpmn:completionCondition>
                    ${nrOfCompletedInstances >= 1}
                  </bpmn:completionCondition>
                </bpmn:multiInstanceLoopCharacteristics>
                """);
    String instance = start("anyReview", body, Map.of("reviewers", List.of("actor", "actor")));
    complete(tasks(instance).getFirst(), "Any one is sufficient");
    assertThat(tasks(instance)).isEmpty();
    assertThat(read(() -> service.diagram(instance)).instance().status()).isEqualTo("COMPLETED");
    assertThat(read(() -> service.nodeHistory(instance, "review", 0, 20)).content())
        .extracting(row -> row.status())
        .containsExactlyInAnyOrder("COMPLETED", "CANCELED");
    assertThat(read(() -> service.completedTasks(null, 0, 20)).totalElements()).isEqualTo(2);
  }

  @Test
  void eachCollectionItemAssignsTheActualTenantMemberAndDoesNotOverwriteTheProcessCollection() {
    String instance =
        start(
            "memberReview",
            multiInstance(false),
            Map.of("reviewers", List.of("actor", "reviewer")));
    assertThat(tasks(instance))
        .extracting(Task::getAssignee)
        .containsExactlyInAnyOrder("actor", "reviewer");
    assertThat(engine.getRuntimeService().getVariable(instance, "reviewers"))
        .isEqualTo(List.of("actor", "reviewer"));
    assertThat(read(() -> service.nodeHistory(instance, "review", 0, 20, true)).content())
        .extracting(row -> row.assigneeName())
        .containsExactlyInAnyOrder("张三", "李四");
  }

  @Test
  void emptyOrOversizedRuntimeCollectionsRollbackWithoutSkippingAnApprovalOrLeavingNotifications() {
    for (var reviewers : List.of(List.<String>of(), java.util.Collections.nCopies(51, "actor"))) {
      assertThatThrownBy(
              () ->
                  start("invalidCollection", multiInstance(false), Map.of("reviewers", reviewers)))
          .isInstanceOfSatisfying(
              WorkflowModelingException.class,
              error -> {
                assertThat(error.getMessageKey()).isEqualTo("workflow.modeling.multiCount");
                assertThat(error.getElementId()).isEqualTo("review");
              });
      assertNoRuntimeResidue();
    }
  }

  @Test
  void dynamicCardinalityNeverTruncatesDecimalsOrWrapsLargeIntegers() {
    String body = countedInstance("${amount}");
    for (Object amount : List.of(0, 51, 1.5, 4294967297L, true, "1.5")) {
      assertThatThrownBy(() -> start("invalidCount", body, Map.of("amount", amount)))
          .isInstanceOfSatisfying(
              WorkflowModelingException.class,
              error -> assertThat(error.getMessageKey()).isEqualTo("workflow.modeling.multiCount"));
      assertNoRuntimeResidue();
    }
    String instance = start("validCount", body, Map.of("amount", 2));
    assertThat(tasks(instance)).hasSize(2);
  }

  private void assertNoRuntimeResidue() {
    assertThat(engine.getRuntimeService().createProcessInstanceQuery().count()).isZero();
    assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().count()).isZero();
    assertThat(engine.getTaskService().createTaskQuery().count()).isZero();
    assertThat(count(WorkflowInstanceEntity.class)).isZero();
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isZero();
  }

  @Test
  void unresolvedAndWrongTypeRuntimeCollectionsReturnNodeSpecificSafeErrors() {
    for (Map<String, Object> variables :
        List.of(Map.<String, Object>of(), Map.<String, Object>of("reviewers", "actor"))) {
      assertThatThrownBy(() -> start("badCollection", multiInstance(false), variables))
          .isInstanceOfSatisfying(
              WorkflowModelingException.class,
              error -> {
                assertThat(error.getMessageKey()).isEqualTo("workflow.modeling.multiCollection");
                assertThat(error.getElementId()).isEqualTo("review");
              });
      assertNoRuntimeResidue();
    }
  }

  @Test
  void nonBooleanRuntimeCompletionRollsBackTheApprovalAndCanBeCorrectedWithoutLosingTheTask() {
    String body =
        multiInstance(false)
            .replace(
                "flowable:elementVariable=\"reviewer\"/>",
                """
                flowable:elementVariable="reviewer">
                  <bpmn:completionCondition>${finish}</bpmn:completionCondition>
                </bpmn:multiInstanceLoopCharacteristics>
                """);
    String instance =
        start(
            "invalidCompletion", body, Map.of("reviewers", List.of("actor", "actor"), "finish", 1));
    Task first = tasks(instance).getFirst();
    long notifications = count(WorkflowNotificationOutboxEntity.class);
    assertThatThrownBy(() -> complete(first, "Must roll back"))
        .isInstanceOfSatisfying(
            WorkflowModelingException.class,
            error ->
                assertThat(error.getMessageKey()).isEqualTo("workflow.modeling.multiCompletion"));
    assertThat(tasks(instance)).hasSize(2);
    assertThat(read(() -> service.completedTasks(null, 0, 20)).totalElements()).isZero();
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isEqualTo(notifications);
    transaction.executeWithoutResult(
        status ->
            service.complete(
                first.getId(),
                new CompleteTaskCommand(Map.of("finish", true), "Corrected completion")));
    assertThat(tasks(instance)).isEmpty();
    assertThat(read(() -> service.diagram(instance)).instance().status()).isEqualTo("COMPLETED");
  }

  @Test
  void nestedParallelExpansionHasTransactionBudgetsAndRollsBackEveryCreatedChild() {
    String inner =
        countedInstance("50")
            .replace("id=\"start\"", "id=\"innerStart\"")
            .replace("sourceRef=\"start\"", "sourceRef=\"innerStart\"")
            .replace("id=\"end\"", "id=\"innerEnd\"")
            .replace("targetRef=\"end\"", "targetRef=\"innerEnd\"");
    String body =
        """
        <bpmn:startEvent id="start"/>
        <bpmn:sequenceFlow id="outerEnter" sourceRef="start" targetRef="outer"/>
        <bpmn:subProcess id="outer">
          <bpmn:multiInstanceLoopCharacteristics>
            <bpmn:loopCardinality>50</bpmn:loopCardinality>
          </bpmn:multiInstanceLoopCharacteristics>
          %s
        </bpmn:subProcess>
        <bpmn:sequenceFlow id="outerExit" sourceRef="outer" targetRef="end"/>
        <bpmn:endEvent id="end"/>
        """
            .formatted(inner);
    assertThatThrownBy(() -> start("boundedNested", body, Map.of()))
        .isInstanceOfSatisfying(
            WorkflowModelingException.class,
            error -> assertThat(error.getMessageKey()).isEqualTo("workflow.modeling.multiBudget"));
    assertNoRuntimeResidue();
  }

  private String countedInstance(String count) {
    return multiInstance(false)
        .replace("${reviewer}", "${initiator}")
        .replace(
            "flowable:collection=\"reviewers\" flowable:elementVariable=\"reviewer\"/>",
            "><bpmn:loopCardinality>"
                + count
                + "</bpmn:loopCardinality></bpmn:multiInstanceLoopCharacteristics>");
  }

  private String branches(String gateway, String secondCondition) {
    return """
           <bpmn:startEvent id="start"/>
           <bpmn:sequenceFlow id="enter" sourceRef="start" targetRef="split"/>
           <bpmn:%s id="split"/>
           <bpmn:sequenceFlow id="first" sourceRef="split" targetRef="review"/>
           <bpmn:sequenceFlow id="second" sourceRef="split" targetRef="other">
             %s
           </bpmn:sequenceFlow>
           <bpmn:userTask id="review" name="First approval" flowable:assignee="${initiator}"/>
           <bpmn:userTask id="other" name="Second approval" flowable:assignee="${initiator}"/>
           <bpmn:sequenceFlow id="first_join" sourceRef="review" targetRef="join"/>
           <bpmn:sequenceFlow id="second_join" sourceRef="other" targetRef="join"/>
           <bpmn:%s id="join"/>
           <bpmn:sequenceFlow id="exit" sourceRef="join" targetRef="end"/>
           <bpmn:endEvent id="end"/>
           """
        .formatted(gateway, secondCondition, gateway);
  }

  private String multiInstance(boolean sequential) {
    return """
           <bpmn:startEvent id="start"/>
           <bpmn:sequenceFlow id="enter" sourceRef="start" targetRef="review"/>
           <bpmn:userTask id="review" name="会签" flowable:assignee="${reviewer}">
             <bpmn:multiInstanceLoopCharacteristics isSequential="%s"
                 flowable:collection="reviewers" flowable:elementVariable="reviewer"/>
           </bpmn:userTask>
           <bpmn:sequenceFlow id="exit" sourceRef="review" targetRef="end"/>
           <bpmn:endEvent id="end"/>
           """
        .formatted(sequential);
  }

  private String start(String key, String body, Map<String, Object> variables) {
    return read(
        () -> {
          var draft = service.createDefinition(new CreateDefinitionCommand(key, key, null, null));
          String xml =
              """
              <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  targetNamespace="http://simplepoint.org/workflow">
                <bpmn:process id="%s" isExecutable="true">%s</bpmn:process>
              </bpmn:definitions>
              """
                  .formatted(key, body);
          service.updateDraft(
              draft.definition().id(), new UpdateDraftCommand(1, key, null, null, xml, null));
          service.publish(draft.definition().id(), new PublishDefinitionCommand(2, null));
          return service
              .start(
                  draft.definition().id(), new StartProcessCommand(1, null, key, variables, null))
              .id();
        });
  }

  private List<Task> tasks(String instance) {
    return engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(instance)
        .orderByTaskId()
        .asc()
        .list();
  }

  private void complete(Task task, String comment) {
    transaction.executeWithoutResult(
        status -> service.complete(task.getId(), new CompleteTaskCommand(Map.of(), comment)));
  }

  private <T> T read(Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }
}
