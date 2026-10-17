package org.simplepoint.plugin.workflow.service;

import java.util.Set;
import java.util.function.Consumer;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Source-level standard loops are bounded and cannot provide trusted execution metadata. */
final class BpmnStandardLoopPolicy {
  private static final String BPMN = BpmnDefinitionValidator.BPMN_NAMESPACE;

  static void validate(Document document, Consumer<String> validateExpression) {
    var all = document.getElementsByTagName("*");
    for (int index = 0; index < all.getLength(); index++) {
      Element element = (Element) all.item(index);
      if (BpmnExecutionCompiler.NAMESPACE.equals(element.getNamespaceURI())) {
        throw issue("compilerReserved", "流程包含仅允许服务器生成的执行元数据", element.getAttribute("id"));
      }
      for (int attribute = 0; attribute < element.getAttributes().getLength(); attribute++) {
        if (BpmnExecutionCompiler.NAMESPACE.equals(
            element.getAttributes().item(attribute).getNamespaceURI())) {
          throw issue("compilerReserved", "流程包含仅允许服务器生成的执行元数据", element.getAttribute("id"));
        }
      }
    }
    var loops = document.getElementsByTagNameNS(BPMN, "standardLoopCharacteristics");
    for (int index = 0; index < loops.getLength(); index++) {
      Element loop = (Element) loops.item(index);
      Element owner = (Element) loop.getParentNode();
      String id = owner.getAttribute("id");
      int loopCount = 0;
      for (Node child = owner.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (BPMN.equals(child.getNamespaceURI())
            && Set.of("standardLoopCharacteristics", "multiInstanceLoopCharacteristics")
                .contains(child.getLocalName())) {
          loopCount++;
        }
      }
      if (!BPMN.equals(owner.getNamespaceURI())
          || !Set.of("userTask", "subProcess").contains(owner.getLocalName())
          || loopCount != 1) {
        throw issue("loopOwner", "循环只能配置在人工任务或子流程上，且不能与其他循环方式重复配置", id);
      }
      String testBefore = loop.getAttribute("testBefore");
      if (loop.hasAttribute("testBefore") && !Set.of("true", "false").contains(testBefore)) {
        throw issue("loopTest", "请选择执行前或执行后判断循环条件", id);
      }
      try {
        int maximum = Integer.parseInt(loop.getAttribute("loopMaximum"));
        if (maximum < 1 || maximum > BpmnMultiInstancePolicy.MAX_INSTANCES) {
          throw new NumberFormatException();
        }
      } catch (NumberFormatException exception) {
        throw issue("loopMaximum", "循环最大次数必须为 1 至 50 的整数", id);
      }
      String condition = null;
      for (Node child = loop.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (BPMN.equals(child.getNamespaceURI()) && "loopCondition".equals(child.getLocalName())) {
          if (condition != null) {
            throw issue("loopCondition", "循环条件必须为有效的布尔表达式，且只能配置一次", id);
          }
          condition = child.getTextContent().trim();
          if (condition.isEmpty()) {
            throw issue("loopCondition", "循环条件必须为有效的布尔表达式，且只能配置一次", id);
          }
        }
      }
      if (condition != null) {
        if (!condition.matches("(?s)[#$]\\{.+}")
            || condition.matches(
                "[#$]\\{\\s*(?:null|-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"
                    + "|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')\\s*}")) {
          throw issue("loopCondition", "循环条件必须为有效的布尔表达式，且只能配置一次", id);
        }
        try {
          validateExpression.accept(condition);
        } catch (WorkflowException exception) {
          throw issue("loopCondition", "循环条件必须为有效的布尔表达式，且只能配置一次", id);
        }
      }
    }
  }

  private static WorkflowModelingException issue(String rule, String message, String id) {
    return new WorkflowModelingException(rule, message, id);
  }

  private BpmnStandardLoopPolicy() {}
}
