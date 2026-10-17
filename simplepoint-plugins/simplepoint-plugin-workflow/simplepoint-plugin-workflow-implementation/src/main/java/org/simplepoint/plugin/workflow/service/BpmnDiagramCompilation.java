package org.simplepoint.plugin.workflow.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Flowable initializes BPMN data objects as variables, outside the platform's form contract.
 * These platform shapes are documentation, so only the engine projection removes
 * them. The versioned source, graphical references and user-visible diagram remain byte-exact.
 */
final class BpmnDiagramCompilation {
  private static final String BPMN = BpmnDefinitionValidator.BPMN_NAMESPACE;
  private static final String DI = "http://www.omg.org/spec/BPMN/20100524/DI";
  private static final Set<String> DATA =
      Set.of("dataObject", "dataObjectReference", "dataStore", "dataStoreReference");

  private BpmnDiagramCompilation() {}

  static String owner(Document document) {
    var nodes = document.getElementsByTagNameNS(BPMN, "*");
    String first = null;
    for (int index = 0; index < nodes.getLength(); index++) {
      var element = (Element) nodes.item(index);
      if (DATA.contains(element.getLocalName())) {
        if (element.getLocalName().endsWith("Reference")) {
          return element.getAttribute("id");
        }
        if (first == null) {
          first = element.getAttribute("id");
        }
      }
    }
    return first;
  }

  static boolean compile(Document document) {
    var nodes = document.getElementsByTagNameNS(BPMN, "*");
    List<Element> elements = new ArrayList<>();
    Set<Element> removed = new java.util.LinkedHashSet<>();
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < nodes.getLength(); index++) {
      var element = (Element) nodes.item(index);
      elements.add(element);
      if (DATA.contains(element.getLocalName())) {
        removed.add(element);
        if (!element.getAttribute("id").isBlank()) {
          ids.add(element.getAttribute("id"));
        }
      }
    }
    if (removed.isEmpty()) {
      return false;
    }
    for (var element : elements) {
      boolean association =
          "association".equals(element.getLocalName())
              && (ids.contains(element.getAttribute("sourceRef"))
                  || ids.contains(element.getAttribute("targetRef")));
      if (List.of("dataInputAssociation", "dataOutputAssociation")
          .contains(element.getLocalName())) {
        for (String name : List.of("sourceRef", "targetRef")) {
          var references = element.getElementsByTagNameNS(BPMN, name);
          for (int index = 0; index < references.getLength(); index++) {
            association |= ids.contains(references.item(index).getTextContent().trim());
          }
        }
      }
      if (association) {
        removed.add(element);
        if (!element.getAttribute("id").isBlank()) {
          ids.add(element.getAttribute("id"));
        }
      }
    }
    var shapes = document.getElementsByTagNameNS(DI, "*");
    for (int index = 0; index < shapes.getLength(); index++) {
      var shape = (Element) shapes.item(index);
      if (ids.contains(shape.getAttribute("bpmnElement"))) {
        removed.add(shape);
      }
    }
    removed.forEach(element -> element.getParentNode().removeChild(element));
    return true;
  }
}
