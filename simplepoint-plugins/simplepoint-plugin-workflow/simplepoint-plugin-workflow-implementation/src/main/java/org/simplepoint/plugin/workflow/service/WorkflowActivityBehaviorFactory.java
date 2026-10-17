package org.simplepoint.plugin.workflow.service;

import java.math.BigDecimal;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import org.flowable.bpmn.model.Activity;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.impl.context.Context;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.bpmn.behavior.AbstractBpmnActivityBehavior;
import org.flowable.engine.impl.bpmn.behavior.ParallelMultiInstanceBehavior;
import org.flowable.engine.impl.bpmn.behavior.SequentialMultiInstanceBehavior;
import org.flowable.engine.impl.bpmn.parser.factory.DefaultActivityBehaviorFactory;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;

/**
 * Keeps native execution semantics, bounding expansion before any multi-instance children exist.
 */
public final class WorkflowActivityBehaviorFactory extends DefaultActivityBehaviorFactory {
  private static final String EXPANSION_KEY = WorkflowActivityBehaviorFactory.class.getName();
  private static final int COMMAND_EXPANSION_LIMIT = 1000;

  @Override
  public SequentialMultiInstanceBehavior createSequentialMultiInstanceBehavior(
      Activity activity, AbstractBpmnActivityBehavior inner) {
    StandardLoopExecution standardLoop = StandardLoopExecution.from(activity);
    return new SequentialMultiInstanceBehavior(activity, inner) {
      @Override
      protected int resolveNrOfInstances(DelegateExecution execution) {
        if (standardLoop != null
            && standardLoop.testBefore()
            && !standardLoop.continues(execution, 0)) {
          return 0; // Explicit while semantics, never an accidental empty approval collection.
        }
        return instances(execution, () -> super.resolveNrOfInstances(execution), usesCollection());
      }

      @Override
      protected int resolveLoopCardinality(DelegateExecution execution) {
        return integerCardinality(execution, loopCardinalityExpression.getValue(execution));
      }

      @Override
      public boolean completionConditionSatisfied(DelegateExecution execution) {
        if (standardLoop != null) {
          // Use this loop's completed count, not a child/ancestor's native zero-based MI index.
          return !standardLoop.continues(
              execution, getLoopVariable(execution, "nrOfCompletedInstances"));
        }
        return completion(execution, () -> super.completionConditionSatisfied(execution));
      }
    };
  }

  @Override
  public ParallelMultiInstanceBehavior createParallelMultiInstanceBehavior(
      Activity activity, AbstractBpmnActivityBehavior inner) {
    return new ParallelMultiInstanceBehavior(activity, inner) {
      @Override
      protected int resolveNrOfInstances(DelegateExecution execution) {
        return instances(execution, () -> super.resolveNrOfInstances(execution), usesCollection());
      }

      @Override
      protected int resolveLoopCardinality(DelegateExecution execution) {
        return integerCardinality(execution, loopCardinalityExpression.getValue(execution));
      }

      @Override
      public boolean completionConditionSatisfied(DelegateExecution execution) {
        return completion(execution, () -> super.completionConditionSatisfied(execution));
      }
    };
  }

  private static int instances(
      DelegateExecution execution, IntSupplier resolve, boolean collection) {
    try {
      return bounded(execution, resolve.getAsInt());
    } catch (FlowableException exception) {
      throw new WorkflowModelingException(
          collection ? "multiCollection" : "multiCount",
          collection ? "办理集合必须引用已声明的多用户或多角色变量" : "多实例执行次数必须为 1 至 50 的整数",
          execution.getCurrentActivityId());
    }
  }

  private static boolean completion(DelegateExecution execution, BooleanSupplier evaluate) {
    try {
      return evaluate.getAsBoolean();
    } catch (FlowableException exception) {
      throw new WorkflowModelingException(
          "multiCompletion", "多实例结束条件必须为有效的布尔表达式", execution.getCurrentActivityId());
    }
  }

  private static int integerCardinality(DelegateExecution execution, Object value) {
    try {
      if (value instanceof Number number) {
        return new BigDecimal(number.toString()).intValueExact();
      }
      if (value instanceof String text) {
        return Integer.parseInt(text);
      }
    } catch (ArithmeticException | NumberFormatException exception) {
      // Native intValue() truncates decimals and wraps oversized values; reject instead.
    }
    throw new WorkflowModelingException(
        "multiCount", "多实例执行次数必须为 1 至 50 的整数", execution.getCurrentActivityId());
  }

  private static int bounded(DelegateExecution execution, int count) {
    if (count < 1 || count > BpmnMultiInstancePolicy.MAX_INSTANCES) {
      throw new WorkflowModelingException(
          "multiCount", "多实例执行次数必须为 1 至 50 的整数", execution.getCurrentActivityId());
    }
    // The engine command owns this counter, including async jobs and nested subprocesses.
    // No request context, global mutable state, tenant variables or user-defined listener is used.
    var context = Context.getCommandContext();
    int total = (context.getAttribute(EXPANSION_KEY) instanceof Integer used ? used : 0) + count;
    if (total > COMMAND_EXPANSION_LIMIT) {
      throw new WorkflowModelingException(
          "multiBudget", "本次流转的多实例展开数量超过安全上限，请减少嵌套或集合大小", execution.getCurrentActivityId());
    }
    context.addAttribute(EXPANSION_KEY, total);
    return count;
  }
}
