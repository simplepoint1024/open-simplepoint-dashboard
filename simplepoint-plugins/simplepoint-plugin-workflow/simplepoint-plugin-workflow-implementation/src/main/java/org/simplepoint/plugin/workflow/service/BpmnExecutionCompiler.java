package org.simplepoint.plugin.workflow.service;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowException.Code;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Projects validated authoring models to execution: bounded native loops and descriptive data
 * shapes. The authored source remains the immutable version/diagram; only the engine resource is
 * lowered.
 */
final class BpmnExecutionCompiler {
  static final String NAMESPACE = "https://simplepoint.org/workflow/execution/v1";
  static final String MANIFEST_RESOURCE = "simplepoint-execution-v1.properties";
  static final String DIAGRAM_RESOURCE = "simplepoint-diagram-v1.properties";
  static final String STANDARD_LOOP = "standardLoop";
  private static final String BPMN = BpmnDefinitionValidator.BPMN_NAMESPACE;

  static Compilation compile(String validatedSource) {
    Document document = BpmnDefinitionValidator.parse(validatedSource);
    var elements = document.getElementsByTagNameNS(BPMN, "standardLoopCharacteristics");
    List<Element> loops = new ArrayList<>();
    for (int index = 0; index < elements.getLength(); index++) {
      loops.add((Element) elements.item(index));
    }
    boolean diagramData = BpmnDiagramCompilation.compile(document);
    if (loops.isEmpty() && !diagramData) {
      return new Compilation(validatedSource, null, false);
    }
    for (Element loop : loops) {
      Element owner = (Element) loop.getParentNode();
      Element extensions = child(owner, "extensionElements");
      if (extensions == null) {
        extensions = bpmn(owner, "extensionElements");
        // BPMN BaseElement orders documentation before extensionElements.
        Node next = owner.getFirstChild();
        while (next != null
            && (!(next instanceof Element element)
                || (BPMN.equals(element.getNamespaceURI())
                    && "documentation".equals(element.getLocalName())))) {
          next = next.getNextSibling();
        }
        owner.insertBefore(extensions, next);
      }
      Element marker = document.createElementNS(NAMESPACE, "spc:" + STANDARD_LOOP);
      Element before = document.createElementNS(NAMESPACE, "spc:testBefore");
      before.setTextContent(Boolean.toString("true".equals(loop.getAttribute("testBefore"))));
      marker.appendChild(before);
      Element authoredCondition = child(loop, "loopCondition");
      if (authoredCondition != null) {
        Element condition = document.createElementNS(NAMESPACE, "spc:condition");
        condition.setTextContent(authoredCondition.getTextContent());
        marker.appendChild(condition);
      }
      extensions.appendChild(marker);
      Element nativeLoop = bpmn(owner, "multiInstanceLoopCharacteristics");
      nativeLoop.setAttribute("isSequential", "true");
      Element count = bpmn(owner, "loopCardinality");
      count.setTextContent(loop.getAttribute("loopMaximum"));
      nativeLoop.appendChild(count);
      owner.replaceChild(nativeLoop, loop);
    }
    try {
      var factory = TransformerFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      var transformer = factory.newTransformer();
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      var output = new StringWriter();
      transformer.transform(new DOMSource(document), new StreamResult(output));
      String executionXml = output.toString();
      return new Compilation(
          executionXml,
          "compiler=simplepoint-bpmn-v1\nsourceSha256="
              + digest(validatedSource)
              + "\nexecutionSha256="
              + digest(executionXml)
              + "\n"
              + (diagramData ? "diagramData=documentation-v1\n" : ""),
          diagramData);
    } catch (Exception exception) {
      throw new WorkflowException(Code.ENGINE_FAILURE, "流程执行模型生成失败", exception);
    }
  }

  /** Returns the source node needing compilation, without rewriting a published source. */
  static String standardLoopOwner(String source) {
    var loops =
        BpmnDefinitionValidator.parse(source)
            .getElementsByTagNameNS(BPMN, "standardLoopCharacteristics");
    return loops.getLength() == 0
        ? null
        : ((Element) loops.item(0).getParentNode()).getAttribute("id");
  }

  private static String digest(String value) throws java.security.NoSuchAlgorithmException {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static Element child(Element parent, String name) {
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element
          && BPMN.equals(element.getNamespaceURI())
          && name.equals(element.getLocalName())) {
        return element;
      }
    }
    return null;
  }

  private static Element bpmn(Element owner, String name) {
    return owner
        .getOwnerDocument()
        .createElementNS(BPMN, owner.getPrefix() == null ? name : owner.getPrefix() + ":" + name);
  }

  record Compilation(String xml, String manifest, boolean diagramData) {
    boolean transformed() {
      return manifest != null;
    }

    boolean matchesDeployment(List<String> resources) {
      return !transformed()
          || resources.contains(MANIFEST_RESOURCE)
              && (!diagramData || resources.contains(DIAGRAM_RESOURCE));
    }
  }

  private BpmnExecutionCompiler() {}
}
