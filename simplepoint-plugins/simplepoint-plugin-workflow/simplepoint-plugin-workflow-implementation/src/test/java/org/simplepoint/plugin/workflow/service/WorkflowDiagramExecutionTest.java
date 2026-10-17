package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;

/**
 * Descriptive BPMN must preserve native tasks, identity and authored source, not create a second
 * runtime.
 */
class WorkflowDiagramExecutionTest extends WorkflowDirectoryFixture {
  @ParameterizedTest
  @ValueSource(strings = {"", "name=\"document\"", "name=\"initiator\""})
  void poolLanesAndDescriptiveArtifactsPublishAndRunThroughThePersonalInbox(String dataName) {
    String lanes =
        """
        <bpmn:laneSet id="lanes"><bpmn:lane id="finance" name="另一部门">
          <bpmn:flowNodeRef>start</bpmn:flowNodeRef><bpmn:flowNodeRef>review</bpmn:flowNodeRef><bpmn:flowNodeRef>end</bpmn:flowNodeRef>
        </bpmn:lane></bpmn:laneSet>
        """;
    String xml =
        BpmnDiagramPolicyTest.xml(
                """
        <bpmn:dataObject id="document" %s isCollection="true"/>
        <bpmn:dataObjectReference id="document_ref" name="附件清单" dataObjectRef="document"/>
        <bpmn:dataStoreReference id="archive_ref" name="业务归档（说明）"/>
        <bpmn:group id="group" categoryValueRef="category_value"/>
        <bpmn:textAnnotation id="note" textFormat="text/plain"><bpmn:text>  ${notExecuted} &amp; &lt;script&gt;
        第二行  </bpmn:text></bpmn:textAnnotation>
        <bpmn:association id="note_link" sourceRef="review" targetRef="note"/>
        """
                    .formatted(dataName),
                """
        <bpmn:collaboration id="collab"><bpmn:participant id="pool" name="审批流程" processRef="diagramReview"/></bpmn:collaboration>
        <bpmn:category id="category"><bpmn:categoryValue id="category_value" value="财务分类"/></bpmn:category>
        """)
            .replace("<bpmn:startEvent", lanes + "<bpmn:startEvent")
            .trim();
    String instanceId =
        transaction.execute(
            status -> {
              var draft =
                  service.createDefinition(
                      new CreateDefinitionCommand("diagramReview", "图形审批", null, null));
              service.updateDraft(
                  draft.definition().id(),
                  new UpdateDraftCommand(1, "图形审批", null, null, xml, null));
              service.publish(draft.definition().id(), new PublishDefinitionCommand(2, null));
              return service
                  .start(
                      draft.definition().id(),
                      new StartProcessCommand(1, null, "图形审批", Map.of(), null))
                  .id();
            });
    var task =
        engine.getTaskService().createTaskQuery().processInstanceId(instanceId).singleResult();
    assertThat(task.getAssignee()).isEqualTo("actor");
    assertThat(engine.getRuntimeService().getVariables(instanceId))
        .doesNotContainKeys("document", "notExecuted", "archive_ref");
    transaction.executeWithoutResult(
        status -> {
          assertThat(service.pendingTasks(null, 0, 20).totalElements()).isEqualTo(1);
          assertThat(service.diagram(instanceId).bpmnXml()).isEqualTo(xml);
          service.complete(task.getId(), new CompleteTaskCommand(Map.of(), "说明图形不改变办理范围"));
        });
    transaction.executeWithoutResult(
        status -> {
          assertThat(service.diagram(instanceId).instance().status()).isEqualTo("COMPLETED");
          assertThat(service.completedTasks(null, 0, 20).totalElements()).isEqualTo(1);
          assertThat(service.nodeHistory(instanceId, "review", 0, 20).content())
              .singleElement()
              .satisfies(
                  row -> {
                    assertThat(row.actorName()).isEqualTo("张三");
                    assertThat(row.comment()).isEqualTo("说明图形不改变办理范围");
                  });
        });
  }

  @Test
  void oldDataExecutionRequiresExplicitRepublishWithoutReinterpretingRunningInstances() {
    String source =
        BpmnDiagramPolicyTest.xml("<bpmn:dataObject id=\"data\" name=\"legacyData\"/>", "").trim();
    String definition =
        transaction.execute(
            status -> {
              var draft =
                  service.createDefinition(
                      new CreateDefinitionCommand("diagramReview", "历史图形", null, null));
              service.updateDraft(
                  draft.definition().id(),
                  new UpdateDraftCommand(1, "历史图形", null, null, source, null));
              service.publish(draft.definition().id(), new PublishDefinitionCommand(2, null));
              return draft.definition().id();
            });
    var repository = engine.getRepositoryService();
    var legacy =
        repository
            .createDeployment()
            .tenantId("tenant")
            .addString("diagramReview.bpmn20.xml", source)
            .deploy();
    String process =
        repository
            .createProcessDefinitionQuery()
            .deploymentId(legacy.getId())
            .singleResult()
            .getId();
    String running =
        transaction.execute(
            status ->
                engine
                    .getRuntimeService()
                    .startProcessInstanceById(process, Map.of("initiator", "actor"))
                    .getId());
    assertThat(engine.getRuntimeService().getVariables(running)).containsKey("legacyData");
    // Test-only seeding of an immutable pre-compiler deployment. Production never updates versions.
    transaction.executeWithoutResult(
        status ->
            em.createNativeQuery(
                    "update simpoint_workflow_definition_versions set"
                        + " flowable_deployment_id=:deployment,"
                        + " flowable_process_definition_id=:process where"
                        + " definition_id=:definition")
                .setParameter("deployment", legacy.getId())
                .setParameter("process", process)
                .setParameter("definition", definition)
                .executeUpdate());
    for (Runnable action :
        List.<Runnable>of(
            () -> transaction.execute(status -> service.startForm(definition, 1)),
            () ->
                transaction.execute(
                    status ->
                        service.start(
                            definition, new StartProcessCommand(1, null, "历史", Map.of(), null))))) {
      assertThatThrownBy(action::run)
          .isInstanceOfSatisfying(
              WorkflowModelingException.class,
              error ->
                  assertThat(error.getMessageKey())
                      .isEqualTo("workflow.modeling.diagramRepublish"));
    }
    transaction.executeWithoutResult(
        status -> {
          assertThat(service.publish(definition, new PublishDefinitionCommand(2, null)).version())
              .isEqualTo(2);
          assertThat(service.publish(definition, new PublishDefinitionCommand(2, null)).version())
              .isEqualTo(2);
          assertThat(service.version(definition, 1).bpmnXml()).isEqualTo(source);
          assertThat(service.version(definition, 2).bpmnXml()).isEqualTo(source);
        });
    ((org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl)
            engine.getProcessEngineConfiguration())
        .getProcessDefinitionCache()
        .clear();
    String current =
        transaction.execute(
            status ->
                service
                    .start(definition, new StartProcessCommand(2, null, "新版本", Map.of(), null))
                    .id());
    assertThat(engine.getRuntimeService().getVariables(current)).doesNotContainKey("legacyData");
    assertThat(engine.getRuntimeService().getVariables(running)).containsKey("legacyData");
    var legacyTask =
        engine.getTaskService().createTaskQuery().processInstanceId(running).singleResult();
    transaction.executeWithoutResult(
        status -> engine.getTaskService().complete(legacyTask.getId()));
    assertThat(
            engine
                .getRuntimeService()
                .createProcessInstanceQuery()
                .processInstanceId(running)
                .count())
        .isZero();
    assertThatThrownBy(() -> transaction.execute(status -> service.startForm(definition, 1)))
        .isInstanceOf(WorkflowModelingException.class);
  }
}
