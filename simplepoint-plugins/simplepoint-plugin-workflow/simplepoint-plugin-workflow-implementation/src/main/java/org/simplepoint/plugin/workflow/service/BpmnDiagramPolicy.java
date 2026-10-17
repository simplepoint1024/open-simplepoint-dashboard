package org.simplepoint.plugin.workflow.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Diagram semantics never imply assignment, authorization or external data execution. */
final class BpmnDiagramPolicy {
  private static final String NS = BpmnDefinitionValidator.BPMN_NAMESPACE;
  private static final BpmnModelingContract CONTRACT = BpmnModelingContract.INSTANCE;

  private BpmnDiagramPolicy() {}

  static void validate(Document document) {
    var nodes = document.getElementsByTagNameNS(NS, "*");
    List<Element> elements = new ArrayList<>();
    Map<String, Element> byId = new HashMap<>();
    for (int index = 0; index < nodes.getLength(); index++) {
      var element = (Element) nodes.item(index);
      elements.add(element);
      if (!element.getAttribute("id").isEmpty()) {
        if (byId.putIfAbsent(element.getAttribute("id"), element) != null) {
          fail(element, "diagramDuplicateId", "流程元素标识不能重复，请为重复元素设置唯一标识");
        }
      }
    }
    Element process = (Element) document.getElementsByTagNameNS(NS, "process").item(0);
    int participants = 0;
    Map<Node, Set<String>> memberships = new HashMap<>();
    for (var element : elements) {
      String type = element.getLocalName();
      if ("participant".equals(type)) {
        participants++;
        if (participants > CONTRACT.maxParticipants()
            || process == null
            || byId.get(element.getAttribute("processRef")) != process
            || !(element.getParentNode() instanceof Element parent)
            || !NS.equals(parent.getNamespaceURI())
            || !"collaboration".equals(parent.getLocalName())) {
          fail(element, "diagramParticipant", "泳池必须唯一，并引用当前可执行流程；不支持外部参与者");
        }
      }
      if (CONTRACT.unsupportedCollaborationElements().contains(type)) {
        fail(element, "diagramCollaboration", "当前流程不支持跨泳池消息流或参与者多实例");
      }
      if ("textAnnotation".equals(type)
          && element.hasAttribute("textFormat")
          && !CONTRACT.annotationFormat().equals(element.getAttribute("textFormat"))) {
        fail(element, "diagramTextFormat", "文字注释仅支持纯文本，请将文本格式改为 text/plain");
      }
      switch (type) {
        case "group" -> reference(element, "categoryValueRef", "categoryValue", false, byId);
        case "dataObjectReference" -> reference(element, "dataObjectRef", "dataObject", true, byId);
        case "dataStoreReference" -> reference(element, "dataStoreRef", "dataStore", false, byId);
        case "lane" -> lane(element, byId, memberships);
        default -> {}
      }
    }
  }

  private static void reference(
      Element element, String attribute, String type, boolean required, Map<String, Element> byId) {
    String id = element.getAttribute(attribute);
    if (id.isEmpty() && !required) {
      return;
    }
    var target = byId.get(id);
    if (target == null
        || !type.equals(target.getLocalName())
        || ("dataObject".equals(type) && scope(target) != scope(element))) {
      fail(element, "diagramReference", "图形引用的对象不存在或类型不正确，请修复关联");
    }
  }

  private static void lane(
      Element lane, Map<String, Element> byId, Map<Node, Set<String>> memberships) {
    Element owner = scope(lane);
    if (owner == null) {
      fail(lane, "diagramLane", "泳道只能引用所属流程或子流程内的活动节点，且同级泳道不能重复归属");
    }
    var seen = memberships.computeIfAbsent(lane.getParentNode(), ignored -> new HashSet<>());
    var children = lane.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (children.item(index) instanceof Element child
          && NS.equals(child.getNamespaceURI())
          && "flowNodeRef".equals(child.getLocalName())) {
        String id = child.getTextContent().trim();
        var target = byId.get(id);
        if (target == null
            || !target
                .getLocalName()
                .matches("(?:.+Task|task|.+Event|.+Gateway|subProcess|callActivity)")
            || scope(target) != owner
            || !seen.add(id)) {
          fail(lane, "diagramLane", "泳道只能引用所属流程或子流程内的活动节点，且同级泳道不能重复归属");
        }
      }
    }
  }

  private static Element scope(Element element) {
    Node parent = element.getParentNode();
    while (parent instanceof Element container) {
      if (NS.equals(container.getNamespaceURI())
          && List.of("process", "subProcess").contains(container.getLocalName())) {
        return container;
      }
      parent = container.getParentNode();
    }
    return null;
  }

  private static void fail(Element element, String rule, String message) {
    String id = element.getAttribute("id");
    if (id.isBlank() && element.getParentNode() instanceof Element parent) {
      id = parent.getAttribute("id");
    }
    throw new WorkflowModelingException(rule, message, id);
  }
}
