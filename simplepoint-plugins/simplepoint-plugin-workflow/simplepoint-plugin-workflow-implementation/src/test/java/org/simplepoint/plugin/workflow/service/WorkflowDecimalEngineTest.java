package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.*;

/** Real Flowable persistence and gateway evaluation preserve exact business amounts. */
class WorkflowDecimalEngineTest extends WorkflowEngineFixture {
  @Test
  void storesExactNumbersAndSelectsTheCorrectBranchBeyondJavascriptIntegerPrecision() {
    String exact = "9007199254740993.12345678901234567890";
    String definition = transaction.execute(status -> {
      var draft = service.createDefinition(new CreateDefinitionCommand("decimalApproval", "金额审批", null, null));
      var contract = new WorkflowFormContract(1, List.of(new WorkflowFormContract.Variable(
          "amount", "金额", "decimal", "both", true, null)), null, Map.of());
      String xml = """
          <?xml version="1.0" encoding="UTF-8"?>
          <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://simplepoint.org/workflow">
            <process id="decimalApproval" name="金额审批" isExecutable="true">
              <startEvent id="start"/><sequenceFlow id="startFlow" sourceRef="start" targetRef="route"/>
              <exclusiveGateway id="route" default="smallFlow"/>
              <sequenceFlow id="largeFlow" sourceRef="route" targetRef="large">
                <conditionExpression xsi:type="tFormalExpression"><![CDATA[${amount > 9007199254740993}]]></conditionExpression>
              </sequenceFlow>
              <sequenceFlow id="smallFlow" sourceRef="route" targetRef="small"/>
              <userTask id="large" name="大额审批" flowable:assignee="${initiator}"/>
              <userTask id="small" name="小额审批" flowable:assignee="${initiator}"/>
              <sequenceFlow id="largeEnd" sourceRef="large" targetRef="end"/>
              <sequenceFlow id="smallEnd" sourceRef="small" targetRef="end"/><endEvent id="end"/>
            </process>
          </definitions>
          """;
      service.updateDraft(draft.definition().id(), new UpdateDraftCommand(1, "金额审批", null, null, xml, contract));
      service.publish(draft.definition().id(), new PublishDefinitionCommand(2, null));
      return draft.definition().id();
    });
    var instance = transaction.<ProcessInstanceSummary>execute(status -> service.start(definition,
        new StartProcessCommand(1, "decimal-case", "金额审批", Map.of("amount", exact), List.of(), "decimal-start")));
    var equal = transaction.<ProcessInstanceSummary>execute(status -> service.start(definition,
        new StartProcessCommand(1, "equal-case", "等于阈值", Map.of("amount", "9007199254740993.00"), List.of(), "equal-start")));
    assertThat(engine.getTaskService().createTaskQuery().processInstanceId(equal.id()).singleResult().getTaskDefinitionKey()).isEqualTo("small");
    var task = engine.getTaskService().createTaskQuery().processInstanceId(instance.id()).singleResult();
    assertThat(task.getTaskDefinitionKey()).isEqualTo("large");
    assertThat(engine.getRuntimeService().getVariable(instance.id(), "amount")).isEqualTo(new java.math.BigDecimal(exact));
    assertThat(service.taskForm(task.getId()).values()).containsEntry("amount", exact);
    transaction.executeWithoutResult(status -> service.complete(task.getId(), new CompleteTaskCommand(
        Map.of("amount", "9007199254740994.00000000000000000001"), "精确数值验证", "decimal-complete")));
    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(instance.id())
        .variableName("amount").singleResult().getValue()).isEqualTo(new java.math.BigDecimal("9007199254740994.00000000000000000001"));
  }
}
