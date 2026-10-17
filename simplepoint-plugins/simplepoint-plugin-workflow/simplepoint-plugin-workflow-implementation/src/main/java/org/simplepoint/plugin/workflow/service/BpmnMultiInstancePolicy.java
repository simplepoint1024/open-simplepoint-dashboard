package org.simplepoint.plugin.workflow.service;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;
import org.simplepoint.plugin.workflow.api.constants.WorkflowVariableNames;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Validates native BPMN multi-instance configuration without evaluating tenant expressions. */
final class BpmnMultiInstancePolicy {
  static final int MAX_INSTANCES = 50;
  private static final String BPMN = BpmnDefinitionValidator.BPMN_NAMESPACE;
  private static final String FLOWABLE = BpmnDefinitionValidator.FLOWABLE_NAMESPACE;

  static void validate(
      Document document, Consumer<String> expression, WorkflowFormContract contract) {
    var loops = document.getElementsByTagNameNS(BPMN, "multiInstanceLoopCharacteristics");
    Set<Node> owners = new HashSet<>();
    for (int index = 0; index < loops.getLength(); index++) {
      Element loop = (Element) loops.item(index);
      Element owner = (Element) loop.getParentNode();
      String id = owner.getAttribute("id");
      if (!Set.of("userTask", "subProcess").contains(owner.getLocalName()) || !owners.add(owner)) {
        throw issue("multiOwner", "多实例只能配置在人工任务或子流程上，且不能重复配置", id);
      }
      String sequential = loop.getAttribute("isSequential");
      if (!sequential.isEmpty() && !Set.of("true", "false").contains(sequential)) {
        throw issue("multiMode", "多实例执行方式必须为串行或并行", id);
      }
      String collection = loop.getAttributeNS(FLOWABLE, "collection").trim();
      String cardinality = child(loop, "loopCardinality", id);
      if (collection.isEmpty() == cardinality.isEmpty()) {
        throw issue("multiSource", "多实例必须且只能选择办理集合或执行次数", id);
      }
      validateExpression(expression, collection, id);
      validateExpression(expression, cardinality, id);
      final String collectionName =
          collection.replaceFirst("^[#$]\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}$", "$1");
      if (!collection.isEmpty()
          && !collection.startsWith("${")
          && !collection.startsWith("#{")
          && !collection.matches("[A-Za-z_][A-Za-z0-9_]*")) {
        throw issue("multiCollection", "办理集合必须引用已声明的多用户或多角色变量", id);
      }
      if (!cardinality.isEmpty()
          && !cardinality.startsWith("${")
          && !cardinality.startsWith("#{")) {
        try {
          int count = Integer.parseInt(cardinality);
          if (count < 1 || count > MAX_INSTANCES) {
            throw new NumberFormatException();
          }
        } catch (NumberFormatException exception) {
          throw issue("multiCount", "多实例执行次数必须为 1 至 50 的整数", id);
        }
      }
      String item = loop.getAttributeNS(FLOWABLE, "elementVariable").trim();
      String counter = loop.getAttributeNS(FLOWABLE, "elementIndexVariable").trim();
      if (collection.isEmpty() && !item.isEmpty()) {
        throw issue("multiItem", "当前项变量需要办理集合，且不能使用系统或重复变量名", id);
      }
      Set<String> local = new HashSet<>();
      for (String name : new String[] {item, counter}) {
        if (name.isEmpty()) {
          continue;
        }
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,79}")
            || WorkflowVariableNames.isReserved(name)
            || !local.add(name)
            || name.equals(collectionName)
            || contract != null
                && contract.variables().stream()
                    .anyMatch(variable -> name.equals(variable.name()))) {
          throw issue("multiItem", "当前项变量需要办理集合，且不能使用系统或重复变量名", id);
        }
        for (Node parent = owner.getParentNode();
            parent instanceof Element;
            parent = parent.getParentNode()) {
          for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element ancestor
                && BPMN.equals(ancestor.getNamespaceURI())
                && "multiInstanceLoopCharacteristics".equals(ancestor.getLocalName())
                && (name.equals(ancestor.getAttributeNS(FLOWABLE, "elementVariable"))
                    || name.equals(ancestor.getAttributeNS(FLOWABLE, "elementIndexVariable")))) {
              throw issue("multiItem", "当前项变量需要办理集合，且不能使用系统或重复变量名", id);
            }
          }
        }
      }
      if (!collection.isEmpty() && contract != null && !contract.variables().isEmpty()) {
        String name = collectionName;
        if (name.matches("[A-Za-z_][A-Za-z0-9_]*")
            && contract.variables().stream()
                .noneMatch(
                    variable ->
                        name.equals(variable.name())
                            && Set.of("users", "roles").contains(variable.type()))) {
          throw issue("multiCollection", "办理集合必须引用已声明的多用户或多角色变量", id);
        }
      }
      String completion = child(loop, "completionCondition", id);
      if (!completion.isEmpty()) {
        if (!completion.startsWith("${") && !completion.startsWith("#{")) {
          throw issue("multiCompletion", "多实例结束条件必须为有效的布尔表达式", id);
        }
        validateExpression(expression, completion, id);
        if (completion.matches(
            "[#$]\\{\\s*(?:null|-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"
                + "|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')\\s*}")) {
          throw issue("multiCompletion", "多实例结束条件必须为有效的布尔表达式", id);
        }
      }
    }
    validateAssignmentScopes(document, contract);
  }

  private static void validateAssignmentScopes(Document document, WorkflowFormContract contract) {
    if (contract == null || contract.variables().isEmpty()) {
      return;
    }
    var tasks = document.getElementsByTagNameNS(BPMN, "userTask");
    for (int index = 0; index < tasks.getLength(); index++) {
      Element task = (Element) tasks.item(index);
      Set<String> names = new HashSet<>(Set.of("initiator", "true", "false", "null"));
      contract.variables().forEach(variable -> names.add(variable.name()));
      for (Node owner = task; owner instanceof Element; owner = owner.getParentNode()) {
        for (Node node = owner.getFirstChild(); node != null; node = node.getNextSibling()) {
          if (node instanceof Element loop
              && BPMN.equals(loop.getNamespaceURI())
              && "multiInstanceLoopCharacteristics".equals(loop.getLocalName())) {
            names.add(loop.getAttributeNS(FLOWABLE, "elementVariable"));
            names.add(loop.getAttributeNS(FLOWABLE, "elementIndexVariable"));
          }
        }
      }
      for (String attribute : Set.of("assignee", "candidateUsers", "candidateGroups")) {
        String value = task.getAttributeNS(FLOWABLE, attribute).trim();
        if (!value.matches("[#$]\\{\\s*[A-Za-z_][A-Za-z0-9_]*\\s*}")) {
          continue;
        }
        String name = value.substring(2, value.length() - 1).trim();
        if (!names.contains(name)) {
          throw issue(
              "assignmentVariable",
              "办理范围引用了未声明或不在当前节点作用域内的变量，请检查多实例配置与办理人设置",
              task.getAttribute("id"));
        }
      }
    }
  }

  private static void validateExpression(Consumer<String> validate, String value, String id) {
    try {
      validate.accept(value);
    } catch (WorkflowException exception) {
      throw issue("multiExpression", "多实例表达式格式无效或包含不允许的调用", id);
    }
  }

  private static String child(Element loop, String name, String id) {
    String value = null;
    for (Node node = loop.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element
          && BPMN.equals(element.getNamespaceURI())
          && name.equals(element.getLocalName())) {
        if (value != null) {
          throw issue("multiSource", "多实例必须且只能选择办理集合或执行次数", id);
        }
        value = element.getTextContent().trim();
      }
    }
    return value == null ? "" : value;
  }

  private static WorkflowModelingException issue(String key, String message, String id) {
    return new WorkflowModelingException(key, message, id);
  }

  private BpmnMultiInstancePolicy() {}
}
