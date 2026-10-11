package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;

class WorkflowStarterTemplateTest extends WorkflowEngineFixture {
  @Test
  void everyReservedNodeFlowAndDiagramIdentifierCanBeUsedAsAProcessKey() {
    for (String key :
        List.of(
            "start",
            "review",
            "end",
            "flow_start_review",
            "flow_review_end",
            "start_di",
            "review_di",
            "end_di",
            "flow_start_review_di",
            "flow_review_end_di",
            "node_review")) {
      var instance =
          transaction.execute(
              status -> {
                var definition =
                    service.createDefinition(
                        new CreateDefinitionCommand(key, "审批 <review> & end", null, null));
                service.publish(
                    definition.definition().id(), new PublishDefinitionCommand(1, null));
                return service.start(
                    definition.definition().id(),
                    new StartProcessCommand(1, "Template " + key, null, Map.of(), List.of()));
              });
      var task =
          engine.getTaskService().createTaskQuery().processInstanceId(instance.id()).singleResult();
      assertThat(task).as("executable starter for process %s", key).isNotNull();
      assertThat(task.getAssignee()).isEqualTo("actor");
      var completed =
          transaction.execute(
              status ->
                  service.complete(task.getId(), new CompleteTaskCommand(Map.of(), "Verified")));
      assertThat(completed.status()).isEqualTo("COMPLETED");
    }
  }
}
