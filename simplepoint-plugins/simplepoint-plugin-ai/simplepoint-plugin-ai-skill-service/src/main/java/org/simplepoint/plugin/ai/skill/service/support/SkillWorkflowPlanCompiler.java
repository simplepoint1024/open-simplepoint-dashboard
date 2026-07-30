package org.simplepoint.plugin.ai.skill.service.support;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Compiles the bounded Skill workflow grammar into an immutable execution plan.
 */
@Component
public class SkillWorkflowPlanCompiler {

  private static final int MAXIMUM_NODES = 128;

  private static final int MAXIMUM_BRANCHES = 16;

  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

  private static final Pattern CODE =
      Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,63}$");

  private final SkillWorkflowTemplateResolver templateResolver;

  private final SkillWorkflowConditionEvaluator conditionEvaluator;

  /**
   * Creates the workflow plan compiler.
   */
  public SkillWorkflowPlanCompiler(
      final SkillWorkflowTemplateResolver templateResolver,
      final SkillWorkflowConditionEvaluator conditionEvaluator
  ) {
    this.templateResolver = templateResolver;
    this.conditionEvaluator = conditionEvaluator;
  }

  /**
   * Compiles a Tool-only workflow. Retained for legacy callers and tests.
   */
  public WorkflowPlan compile(
      final Map<String, Object> workflow,
      final Set<String> toolAliases
  ) {
    return compile(
        workflow,
        new WorkflowBindings(toolAliases, Set.of(), Set.of())
    );
  }

  /**
   * Compiles and validates a workflow against immutable MCP bindings.
   */
  public WorkflowPlan compile(
      final Map<String, Object> workflow,
      final WorkflowBindings bindings
  ) {
    if (bindings == null) {
      throw new IllegalArgumentException(
          "Skill workflow bindings must not be null"
      );
    }
    assertAllowedKeys(workflow, Set.of("steps", "output"), "Skill workflow");
    List<Map<String, Object>> sourceSteps = mapList(
        workflow.get("steps"),
        "Skill workflow steps"
    );
    if (sourceSteps.isEmpty()) {
      throw new IllegalArgumentException(
          "Skill workflow must contain at least one step"
      );
    }
    Set<String> ids = new HashSet<>();
    List<String> available = new ArrayList<>();
    NodeCounter counter = new NodeCounter();
    Sequence sequence = compileSequence(
        sourceSteps,
        bindings,
        ids,
        available,
        counter,
        true,
        "Skill workflow steps"
    );
    if (sequence.executableSteps().isEmpty()) {
      throw new IllegalArgumentException(
          "Skill workflow must contain an executable MCP step"
      );
    }
    Object output = workflow.get("output");
    if (output != null) {
      templateResolver.validateTemplate(
          output,
          available,
          "Workflow output"
      );
    }
    return new WorkflowPlan(
        sequence.nodes(),
        output,
        sequence.maximumToolCalls(),
        sequence.executableSteps()
    );
  }

  private Sequence compileSequence(
      final List<Map<String, Object>> sourceSteps,
      final WorkflowBindings bindings,
      final Set<String> ids,
      final List<String> available,
      final NodeCounter counter,
      final boolean allowControlNodes,
      final String label
  ) {
    List<WorkflowNode> nodes = new ArrayList<>();
    List<ExecutableNode> executableSteps = new ArrayList<>();
    int maximumToolCalls = 0;
    for (int index = 0; index < sourceSteps.size(); index++) {
      Map<String, Object> source = sourceSteps.get(index);
      String itemLabel = label + "[" + index + "]";
      String id = requiredIdentifier(source.get("id"), itemLabel + " ID");
      String type = requiredText(source.get("type"), itemLabel + " type")
          .toLowerCase(Locale.ROOT);
      if (!ids.add(id)) {
        throw new IllegalArgumentException("Duplicate workflow step ID: " + id);
      }
      counter.increment();
      switch (type) {
        case "tool" -> {
          ToolNode tool = compileTool(
              source,
              id,
              bindings.toolAliases(),
              available,
              itemLabel
          );
          nodes.add(tool);
          executableSteps.add(tool);
          maximumToolCalls++;
          available.add(id);
        }
        case "prompt" -> {
          PromptNode prompt = compilePrompt(
              source,
              id,
              bindings.promptAliases(),
              available,
              itemLabel
          );
          nodes.add(prompt);
          executableSteps.add(prompt);
          available.add(id);
        }
        case "resource" -> {
          ResourceNode resource = compileResource(
              source,
              id,
              bindings.resourceAliases(),
              available,
              itemLabel
          );
          nodes.add(resource);
          executableSteps.add(resource);
          available.add(id);
        }
        case "condition" -> {
          assertControlAllowed(allowControlNodes, type);
          ConditionNode condition = compileCondition(
              source,
              id,
              bindings,
              ids,
              available,
              counter,
              itemLabel
          );
          nodes.add(condition);
          executableSteps.addAll(condition.whenTrue());
          executableSteps.addAll(condition.whenFalse());
          maximumToolCalls += Math.max(
              toolCalls(condition.whenTrue()),
              toolCalls(condition.whenFalse())
          );
          available.add(id);
          condition.whenTrue().forEach(step -> available.add(step.id()));
          condition.whenFalse().forEach(step -> available.add(step.id()));
        }
        case "parallel" -> {
          assertControlAllowed(allowControlNodes, type);
          ParallelNode parallel = compileParallel(
              source,
              id,
              bindings,
              ids,
              available,
              counter,
              itemLabel
          );
          nodes.add(parallel);
          for (ParallelBranch branch : parallel.branches()) {
            executableSteps.addAll(branch.steps());
            maximumToolCalls += toolCalls(branch.steps());
          }
          available.add(id);
          parallel.branches().forEach(branch ->
              branch.steps().forEach(step -> available.add(step.id())));
        }
        default -> throw new IllegalArgumentException(
            "Unsupported workflow step type: " + type
        );
      }
    }
    return new Sequence(
        List.copyOf(nodes),
        List.copyOf(executableSteps),
        maximumToolCalls
    );
  }

  private ToolNode compileTool(
      final Map<String, Object> source,
      final String id,
      final Set<String> aliases,
      final List<String> available,
      final String label
  ) {
    assertAllowedKeys(
        source,
        Set.of("arguments", "id", "tool", "type"),
        label
    );
    String alias = requiredBoundAlias(
        source.get("tool"),
        aliases,
        "Tool",
        label
    );
    Object arguments = optionalObjectTemplate(
        source.get("arguments"),
        available,
        "Workflow step " + id + " arguments"
    );
    return new ToolNode(id, alias, arguments);
  }

  private PromptNode compilePrompt(
      final Map<String, Object> source,
      final String id,
      final Set<String> aliases,
      final List<String> available,
      final String label
  ) {
    assertAllowedKeys(
        source,
        Set.of("arguments", "id", "prompt", "type"),
        label
    );
    String alias = requiredBoundAlias(
        source.get("prompt"),
        aliases,
        "Prompt",
        label
    );
    Object arguments = optionalObjectTemplate(
        source.get("arguments"),
        available,
        "Workflow step " + id + " arguments"
    );
    return new PromptNode(id, alias, arguments);
  }

  private ResourceNode compileResource(
      final Map<String, Object> source,
      final String id,
      final Set<String> aliases,
      final List<String> available,
      final String label
  ) {
    assertAllowedKeys(
        source,
        Set.of("id", "resource", "type", "uri"),
        label
    );
    String alias = requiredBoundAlias(
        source.get("resource"),
        aliases,
        "Resource",
        label
    );
    Object uri = source.get("uri");
    if (uri != null) {
      templateResolver.validateTemplate(
          uri,
          available,
          "Workflow step " + id + " URI"
      );
    }
    return new ResourceNode(id, alias, uri);
  }

  private ConditionNode compileCondition(
      final Map<String, Object> source,
      final String id,
      final WorkflowBindings bindings,
      final Set<String> ids,
      final List<String> available,
      final NodeCounter counter,
      final String label
  ) {
    assertAllowedKeys(
        source,
        Set.of("condition", "else", "id", "then", "type"),
        label
    );
    Object predicate = source.get("condition");
    if (predicate == null) {
      throw new IllegalArgumentException(
          "Workflow condition node must define condition"
      );
    }
    conditionEvaluator.validate(
        predicate,
        available,
        "Workflow condition " + id
    );
    Sequence whenTrue = compileSequence(
        optionalMapList(source.get("then"), label + ".then"),
        bindings,
        ids,
        new ArrayList<>(available),
        counter,
        false,
        label + ".then"
    );
    Sequence whenFalse = compileSequence(
        optionalMapList(source.get("else"), label + ".else"),
        bindings,
        ids,
        new ArrayList<>(available),
        counter,
        false,
        label + ".else"
    );
    return new ConditionNode(
        id,
        predicate,
        executableNodes(whenTrue),
        executableNodes(whenFalse)
    );
  }

  private ParallelNode compileParallel(
      final Map<String, Object> source,
      final String id,
      final WorkflowBindings bindings,
      final Set<String> ids,
      final List<String> available,
      final NodeCounter counter,
      final String label
  ) {
    assertAllowedKeys(source, Set.of("branches", "id", "type"), label);
    List<Map<String, Object>> sourceBranches = mapList(
        source.get("branches"),
        label + ".branches"
    );
    if (sourceBranches.size() < 2
        || sourceBranches.size() > MAXIMUM_BRANCHES) {
      throw new IllegalArgumentException(
          "Workflow parallel node must contain between 2 and "
              + MAXIMUM_BRANCHES + " branches"
      );
    }
    Set<String> branchIds = new LinkedHashSet<>();
    List<ParallelBranch> branches = new ArrayList<>();
    for (int index = 0; index < sourceBranches.size(); index++) {
      Map<String, Object> branch = sourceBranches.get(index);
      String branchLabel = label + ".branches[" + index + "]";
      assertAllowedKeys(branch, Set.of("id", "steps"), branchLabel);
      String branchId = requiredCode(
          branch.get("id"),
          branchLabel + " ID"
      );
      if (!branchIds.add(branchId)) {
        throw new IllegalArgumentException(
            "Duplicate parallel branch ID: " + branchId
        );
      }
      Sequence sequence = compileSequence(
          mapList(branch.get("steps"), branchLabel + ".steps"),
          bindings,
          ids,
          new ArrayList<>(available),
          counter,
          false,
          branchLabel + ".steps"
      );
      if (sequence.executableSteps().isEmpty()) {
        throw new IllegalArgumentException(
            "Workflow parallel branch must contain an MCP step"
        );
      }
      branches.add(new ParallelBranch(
          branchId,
          executableNodes(sequence)
      ));
    }
    return new ParallelNode(id, List.copyOf(branches));
  }

  private Object optionalObjectTemplate(
      final Object value,
      final List<String> available,
      final String label
  ) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Map<?, ?>)) {
      throw new IllegalArgumentException(label + " must be an object");
    }
    templateResolver.validateTemplate(value, available, label);
    return value;
  }

  private static String requiredBoundAlias(
      final Object value,
      final Set<String> aliases,
      final String type,
      final String label
  ) {
    String alias = requiredCode(value, label + " " + type + " alias");
    if (!aliases.contains(alias)) {
      throw new IllegalArgumentException(
          "Workflow references an unbound " + type + " alias: " + alias
      );
    }
    return alias;
  }

  private static int toolCalls(final List<ExecutableNode> steps) {
    return (int) steps.stream().filter(ToolNode.class::isInstance).count();
  }

  private static List<ExecutableNode> executableNodes(
      final Sequence sequence
  ) {
    return sequence.nodes().stream()
        .map(ExecutableNode.class::cast)
        .toList();
  }

  private static void assertControlAllowed(
      final boolean allowed,
      final String type
  ) {
    if (!allowed) {
      throw new IllegalArgumentException(
          "Nested workflow control node is not supported: " + type
      );
    }
  }

  private static void assertAllowedKeys(
      final Map<String, Object> value,
      final Set<String> allowed,
      final String label
  ) {
    for (String key : value.keySet()) {
      if (!allowed.contains(key)) {
        throw new IllegalArgumentException(
            label + " contains unsupported field: " + key
        );
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> mapList(
      final Object value,
      final String label
  ) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(label + " must be an array");
    }
    List<Map<String, Object>> result = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException(
            label + " must contain objects"
        );
      }
      result.add((Map<String, Object>) map);
    }
    return result;
  }

  private static List<Map<String, Object>> optionalMapList(
      final Object value,
      final String label
  ) {
    return value == null ? List.of() : mapList(value, label);
  }

  private static String requiredIdentifier(
      final Object value,
      final String label
  ) {
    return requiredPattern(value, label, IDENTIFIER);
  }

  private static String requiredCode(
      final Object value,
      final String label
  ) {
    return requiredPattern(value, label, CODE);
  }

  private static String requiredPattern(
      final Object value,
      final String label,
      final Pattern pattern
  ) {
    String text = requiredText(value, label);
    if (!pattern.matcher(text).matches()) {
      throw new IllegalArgumentException(label + " is invalid");
    }
    return text;
  }

  private static String requiredText(
      final Object value,
      final String label
  ) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
    return text.trim();
  }

  /**
   * Immutable aliases available to one Skill version.
   */
  public record WorkflowBindings(
      Set<String> toolAliases,
      Set<String> promptAliases,
      Set<String> resourceAliases
  ) {

    /**
     * Normalizes nullable sets into immutable empty collections.
     */
    public WorkflowBindings {
      toolAliases = toolAliases == null ? Set.of() : Set.copyOf(toolAliases);
      promptAliases = promptAliases == null
          ? Set.of() : Set.copyOf(promptAliases);
      resourceAliases = resourceAliases == null
          ? Set.of() : Set.copyOf(resourceAliases);
    }
  }

  /**
   * Immutable compiled workflow plan.
   */
  public record WorkflowPlan(
      List<WorkflowNode> nodes,
      Object outputTemplate,
      int maximumToolCalls,
      List<ExecutableNode> executableSteps
  ) {
  }

  /**
   * A bounded declarative workflow node.
   */
  public sealed interface WorkflowNode
      permits ConditionNode, ExecutableNode, ParallelNode {

    /**
     * Returns the globally unique node identifier.
     */
    String id();
  }

  /**
   * A durable MCP operation represented by one persisted leaf checkpoint.
   */
  public sealed interface ExecutableNode extends WorkflowNode
      permits PromptNode, ResourceNode, ToolNode {
  }

  /**
   * One exact immutable MCP Tool call.
   */
  public record ToolNode(
      String id,
      String toolAlias,
      Object argumentsTemplate
  ) implements ExecutableNode {
  }

  /**
   * One exact immutable MCP Prompt render.
   */
  public record PromptNode(
      String id,
      String promptAlias,
      Object argumentsTemplate
  ) implements ExecutableNode {
  }

  /**
   * One immutable MCP Resource or Resource Template read.
   */
  public record ResourceNode(
      String id,
      String resourceAlias,
      Object uriTemplate
  ) implements ExecutableNode {
  }

  /**
   * A condition selecting one of two sequential MCP branches.
   */
  public record ConditionNode(
      String id,
      Object condition,
      List<ExecutableNode> whenTrue,
      List<ExecutableNode> whenFalse
  ) implements WorkflowNode {
  }

  /**
   * A bounded fork/join of independent sequential MCP branches.
   */
  public record ParallelNode(
      String id,
      List<ParallelBranch> branches
  ) implements WorkflowNode {
  }

  /**
   * One sequential branch inside a parallel node.
   */
  public record ParallelBranch(
      String id,
      List<ExecutableNode> steps
  ) {
  }

  private record Sequence(
      List<WorkflowNode> nodes,
      List<ExecutableNode> executableSteps,
      int maximumToolCalls
  ) {
  }

  private static final class NodeCounter {

    private int count;

    private void increment() {
      count++;
      if (count > MAXIMUM_NODES) {
        throw new IllegalArgumentException(
            "Skill workflow has too many nodes"
        );
      }
    }
  }
}
