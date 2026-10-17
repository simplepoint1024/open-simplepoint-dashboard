package org.simplepoint.plugin.workflow.service;

import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Validate the engine's compatibility surface, not merely the literal flowable XML prefix. */
final class BpmnExecutionSurfacePolicy {
  private static final String BPMN = BpmnDefinitionValidator.BPMN_NAMESPACE;
  private static final Set<String> DI =
      Set.of(
          "http://www.omg.org/spec/BPMN/20100524/DI",
          "http://www.omg.org/spec/DD/20100524/DI",
          "http://www.omg.org/spec/DD/20100524/DC");
  private static final Set<String> COMPATIBILITY_NAMESPACES =
      Set.of("http://activiti.org/bpmn", "http://camunda.org/schema/1.0/bpmn");
  private static final Map<String, Set<String>> FLOWABLE_ATTRIBUTES =
      Map.of(
          "userTask",
              Set.of("assignee", "candidateUsers", "candidateGroups", "dueDate", "priority"),
          "multiInstanceLoopCharacteristics",
              Set.of("collection", "elementVariable", "elementIndexVariable"));

  private BpmnExecutionSurfacePolicy() {}

  static void validate(Document document) {
    var nodes = document.getElementsByTagName("*");
    for (int index = 0; index < nodes.getLength(); index++) {
      var element = (Element) nodes.item(index);
      if (!BPMN.equals(element.getNamespaceURI())
          && (element.getNamespaceURI() == null
              || !DI.contains(element.getNamespaceURI())
              || !insideDiagram(element))) {
        fail(element, "executionNamespace", "流程包含当前平台未支持的元素命名空间，请使用标准 BPMN 与平台配置");
      }
      // Flowable dispatches extension parsers by local name, including arbitrary or absent
      // namespaces.
      if ("extensionElements".equals(element.getLocalName())) {
        var children = element.getChildNodes();
        for (int child = 0; child < children.getLength(); child++) {
          if (children.item(child) instanceof Element) {
            fail(element, "executionExtension", "流程不支持自定义扩展元素或执行监听器，请使用平台提供的配置");
          }
        }
      }
      var attributes = element.getAttributes();
      for (int attributeIndex = 0; attributeIndex < attributes.getLength(); attributeIndex++) {
        var attribute = attributes.item(attributeIndex);
        String namespace = attribute.getNamespaceURI();
        if (namespace != null && COMPATIBILITY_NAMESPACES.contains(namespace)) {
          fail(element, "executionAttribute", "流程包含未受控的引擎属性，请使用平台提供的配置");
        }
        if (!BpmnDefinitionValidator.FLOWABLE_NAMESPACE.equals(namespace)) {
          continue;
        }
        String name = attribute.getLocalName();
        boolean async =
            "async".equals(name) && Set.of("true", "false").contains(attribute.getNodeValue());
        if (!BPMN.equals(element.getNamespaceURI())
            || !(async
                || FLOWABLE_ATTRIBUTES
                    .getOrDefault(element.getLocalName(), Set.of())
                    .contains(name))) {
          fail(element, "executionAttribute", "流程包含未受控的引擎属性，请使用平台提供的配置");
        }
      }
    }
  }

  private static boolean insideDiagram(Element element) {
    Node parent = element;
    while (parent instanceof Element current) {
      if ("http://www.omg.org/spec/BPMN/20100524/DI".equals(current.getNamespaceURI())
          && "BPMNDiagram".equals(current.getLocalName())) {
        return true;
      }
      parent = parent.getParentNode();
    }
    return false;
  }

  private static void fail(Element element, String rule, String message) {
    Element owner = element;
    while (!owner.hasAttribute("id") && owner.getParentNode() instanceof Element parent) {
      owner = parent;
    }
    throw new WorkflowModelingException(rule, message, owner.getAttribute("id"));
  }
}
