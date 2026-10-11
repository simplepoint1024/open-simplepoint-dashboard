package org.simplepoint.plugin.workflow.service;

import java.io.StringReader;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.flowable.common.engine.impl.de.odysseus.el.util.SimpleContext;
import org.flowable.common.engine.impl.el.FlowableExpressionFactory;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowException.Code;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Parses BPMN securely and enforces the execution surface supported by the platform. */
@Component
public class BpmnDefinitionValidator {

  static final String BPMN_NAMESPACE = "http://www.omg.org/spec/BPMN/20100524/MODEL";
  static final String FLOWABLE_NAMESPACE = "http://flowable.org/bpmn";
  private static final int MAX_XML_LENGTH = 2_000_000;
  private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,79}");
  private static final Pattern UNSAFE_EXPRESSION = Pattern.compile(
      "(?i)(?:@|\\bnew\\s|\\bclass\\b|\\bT\\s*\\(|\\bRuntime\\b|\\bProcessBuilder\\b|"
          + "\\bgetClass\\b|\\bforName\\b|[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(|[\\]\\)]\\s*\\("
          + "|\\b(?:execution|task|processEngine|processEngineConfiguration|delegateTask)\\b)"
  );
  private static final Pattern QUOTED_TEXT = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'");
  private static final Pattern REFLECTIVE_INDEX = Pattern.compile("(?i)\\[\\s*['\"](?:class|getClass|forName)['\"]\\s*\\]");
  private static final Pattern REFLECTIVE_LITERAL = Pattern.compile("(?i)['\"](?:class|getClass|forName)['\"]");
  private static final Pattern NON_TEMPORAL_LITERAL = Pattern.compile(
      "\\$\\{\\s*(?:true|false|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)\\s*\\}"
  );
  private static final Set<String> PROHIBITED_ELEMENTS = Set.of(
      "scriptTask", "serviceTask", "businessRuleTask", "sendTask", "receiveTask",
      "callActivity", "manualTask"
  );
  private static final List<String> PROHIBITED_FLOWABLE_ATTRIBUTES = List.of(
      "class", "delegateExpression", "expression", "type", "resultVariable"
  );
  private final FlowableExpressionFactory expressionFactory = new FlowableExpressionFactory();
  private final WorkflowPriorityLiteralPreview priorityPreview = new WorkflowPriorityLiteralPreview();

  /** Validates XML and returns the stable process identity found in it. */
  public ValidatedProcess validate(final String xml, final String expectedKey) {
    if (xml == null || xml.isBlank()) {
      throw invalid("BPMN XML 不能为空");
    }
    if (xml.length() > MAX_XML_LENGTH) {
      throw invalid("BPMN XML 不能超过 2 MB");
    }
    Document document = parse(xml);
    NodeList processes = document.getElementsByTagNameNS(BPMN_NAMESPACE, "process");
    if (processes.getLength() != 1) {
      throw invalid("一个流程定义必须且只能包含一个 BPMN process");
    }
    Element process = (Element) processes.item(0);
    String key = required(process.getAttribute("id"), "BPMN process id 不能为空");
    if (!KEY.matcher(key).matches()) {
      throw invalid("流程标识必须以字母开头，只能包含字母、数字、下划线和短横线");
    }
    if (expectedKey != null && !expectedKey.equals(key)) {
      throw invalid("BPMN process id 必须与流程标识 " + expectedKey + " 一致");
    }
    if ("false".equalsIgnoreCase(process.getAttribute("isExecutable"))) {
      throw invalid("流程必须设置为可执行");
    }
    rejectFlowableExtensionElements(document);
    inspectElements(document);
    requireStartAndEnd(document);
    Set<String> taskIds = new java.util.LinkedHashSet<>();
    NodeList tasks = document.getElementsByTagNameNS(BPMN_NAMESPACE, "userTask");
    for (int index = 0; index < tasks.getLength(); index++) {
      String id = required(((Element) tasks.item(index)).getAttribute("id"), "用户任务 id 不能为空");
      if (!taskIds.add(id)) {
        throw invalid("用户任务 id 不能重复");
      }
    }
    return new ValidatedProcess(key, normalized(process.getAttribute("name")), Set.copyOf(taskIds));
  }

  private Document parse(final String xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    } catch (WorkflowException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new WorkflowException(Code.VALIDATION, "BPMN XML 格式不正确", exception);
    }
  }

  private void inspectElements(final Document document) {
    NodeList nodes = document.getElementsByTagNameNS(BPMN_NAMESPACE, "*");
    for (int index = 0; index < nodes.getLength(); index++) {
      Node node = nodes.item(index);
      if (!(node instanceof Element element)) {
        continue;
      }
      String localName = element.getLocalName();
      if (PROHIBITED_ELEMENTS.contains(localName)) {
        throw invalid("当前平台不允许执行 " + localName + " 节点");
      }
      for (String attribute : PROHIBITED_FLOWABLE_ATTRIBUTES) {
        if (hasText(element.getAttributeNS(FLOWABLE_NAMESPACE, attribute))) {
          throw invalid("流程包含不受控执行属性 flowable:" + attribute);
        }
      }
      if ("userTask".equals(localName)) {
        validateUserTask(element);
      } else if ("conditionExpression".equals(localName)) {
        validateCondition(element.getTextContent());
      }
    }
  }

  private void rejectFlowableExtensionElements(final Document document) {
    NodeList extensions = document.getElementsByTagNameNS(FLOWABLE_NAMESPACE, "*");
    if (extensions.getLength() > 0) {
      Node extension = extensions.item(0);
      throw invalid("流程包含不受控 Flowable 扩展元素 flowable:" + extension.getLocalName());
    }
  }

  private void validateUserTask(final Element element) {
    boolean assigned = hasText(element.getAttributeNS(FLOWABLE_NAMESPACE, "assignee"))
        || hasText(element.getAttributeNS(FLOWABLE_NAMESPACE, "candidateUsers"))
        || hasText(element.getAttributeNS(FLOWABLE_NAMESPACE, "candidateGroups"));
    if (!assigned) {
      throw invalid("用户任务 " + element.getAttribute("id") + " 必须设置办理人或候选范围");
    }
    validateAssignment(element.getAttributeNS(FLOWABLE_NAMESPACE, "assignee"), false, false);
    validateAssignment(element.getAttributeNS(FLOWABLE_NAMESPACE, "candidateUsers"), true, false);
    validateAssignment(element.getAttributeNS(FLOWABLE_NAMESPACE, "candidateGroups"), true, true);
    String dueDate = element.getAttributeNS(FLOWABLE_NAMESPACE, "dueDate");
    validateCondition(dueDate);
    // Only an exact numeric/boolean scalar is rejected here; variable and temporal expressions are never evaluated.
    if (NON_TEMPORAL_LITERAL.matcher(dueDate.trim()).matches()) {
      throw invalid("到期时间常量必须是日期或时长文本，不能使用布尔值或数值");
    }
    validateCondition(element.getAttributeNS(FLOWABLE_NAMESPACE, "priority"));
    String priority = element.getAttributeNS(FLOWABLE_NAMESPACE, "priority");
    if (priorityPreview.isLiteral(priority) && priorityPreview.preview(priority).issue() != null) {
      throw invalid("优先级常量必须是引擎可接受的数值或整数文本，不能使用布尔值或非法整数");
    }
  }

  private void validateAssignment(final String value, final boolean multiple, final boolean roles) {
    if (!hasText(value)) {
      return;
    }
    if (value.contains("\n") || value.contains("\r")) {
      throw invalid("办理人配置格式不正确");
    }
    if (value.contains("${") || value.contains("#{")) {
      validateCondition(value);
      return;
    }
    String[] ids = value.split(",", -1);
    if (ids.length > (multiple ? 50 : 1)) {
      throw invalid("候选用户或角色不能超过 50 项，办理人只能选择一个用户");
    }
    for (String id : ids) {
      String identifier = id.trim();
      // Role.authority is not an ASCII-only ID in the platform's RBAC contract.
      // Fixed authorities are resolved against this tenant again before a runtime commit.
      boolean valid = roles ? !identifier.isBlank() && identifier.length() <= 100
          && identifier.codePoints().noneMatch(Character::isISOControl)
          : identifier.matches("[A-Za-z0-9_][A-Za-z0-9_.:@-]{0,99}");
      if (!valid) {
        throw invalid("办理人或候选范围包含无效标识");
      }
    }
  }

  private void validateCondition(final String expression) {
    if (!hasText(expression)) {
      return;
    }
    String value = expression.trim();
    if (value.length() > 500) {
      throw invalid("条件或人员表达式包含不允许的调用");
    }
    String code = QUOTED_TEXT.matcher(value).replaceAll(match -> {
      int before = match.start() - 1;
      int after = match.end();
      while (before >= 0 && Character.isWhitespace(value.charAt(before))) {
        before--;
      }
      while (after < value.length() && Character.isWhitespace(value.charAt(after))) {
        after++;
      }
      boolean indexed = before >= 0 && value.charAt(before) == '[' && after < value.length() && value.charAt(after) == ']';
      return indexed && REFLECTIVE_LITERAL.matcher(match.group()).matches() ? match.group() : " ";
    });
    if (UNSAFE_EXPRESSION.matcher(code).find() || REFLECTIVE_INDEX.matcher(code).find()) {
      throw invalid("条件或人员表达式包含不允许的调用");
    }
    if (value.contains("${") || value.contains("#{")) {
      try {
        // Parse only: no variable lookup, expression evaluation or application bean context.
        expressionFactory.createValueExpression(new SimpleContext(), value, Object.class);
      } catch (RuntimeException exception) {
        throw new WorkflowException(Code.VALIDATION, "条件或人员表达式格式不正确", exception);
      }
    }
  }

  private void requireStartAndEnd(final Document document) {
    if (document.getElementsByTagNameNS(BPMN_NAMESPACE, "startEvent").getLength() == 0
        || document.getElementsByTagNameNS(BPMN_NAMESPACE, "endEvent").getLength() == 0) {
      throw invalid("流程必须包含开始事件和结束事件");
    }
  }

  private static String required(final String value, final String message) {
    String normalized = normalized(value);
    if (normalized == null) {
      throw invalid(message);
    }
    return normalized;
  }

  private static String normalized(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static boolean hasText(final String value) {
    return value != null && !value.isBlank();
  }

  private static WorkflowException invalid(final String message) {
    return new WorkflowException(Code.VALIDATION, message);
  }

  /** Parsed process attributes needed by publication. */
  public record ValidatedProcess(String key, String name, Set<String> userTaskIds) {
  }
}
