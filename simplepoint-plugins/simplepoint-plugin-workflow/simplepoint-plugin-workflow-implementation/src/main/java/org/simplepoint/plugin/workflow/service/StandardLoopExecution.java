package org.simplepoint.plugin.workflow.service;

import java.util.List;
import java.util.Map;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.impl.variable.MapDelegateVariableContainer;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;

/** Trusted compiler metadata is never sourced from process variables or application beans. */
record StandardLoopExecution(boolean testBefore, String condition) {
  static StandardLoopExecution from(Activity activity) {
    var markers =
        activity
            .getExtensionElements()
            .getOrDefault(BpmnExecutionCompiler.STANDARD_LOOP, List.of());
    for (ExtensionElement marker : markers) {
      if (BpmnExecutionCompiler.NAMESPACE.equals(marker.getNamespace())) {
        return new StandardLoopExecution(
            "true".equals(text(marker, "testBefore")), text(marker, "condition"));
      }
    }
    return null;
  }

  /**
   * The condition sees its own completed count (zero before entry), without persisting a shadow.
   */
  boolean continues(DelegateExecution execution, int completedIterations) {
    if (condition == null || condition.isBlank()) {
      return true;
    }
    try {
      Object result =
          CommandContextUtil.getProcessEngineConfiguration()
              .getExpressionManager()
              .createExpression(condition)
              .getValue(
                  new MapDelegateVariableContainer(
                      Map.of("loopCounter", completedIterations), execution));
      if (result instanceof Boolean value) {
        return value;
      }
    } catch (FlowableException exception) {
      // Never expose expression internals or variable contents at the HTTP boundary.
    }
    throw new WorkflowModelingException(
        "loopCondition", "循环条件必须为有效的布尔表达式，且只能配置一次", execution.getCurrentActivityId());
  }

  private static String text(ExtensionElement marker, String name) {
    return marker.getChildElements().getOrDefault(name, List.of()).stream()
        .filter(element -> BpmnExecutionCompiler.NAMESPACE.equals(element.getNamespace()))
        .map(ExtensionElement::getElementText)
        .findFirst()
        .orElse(null);
  }
}
