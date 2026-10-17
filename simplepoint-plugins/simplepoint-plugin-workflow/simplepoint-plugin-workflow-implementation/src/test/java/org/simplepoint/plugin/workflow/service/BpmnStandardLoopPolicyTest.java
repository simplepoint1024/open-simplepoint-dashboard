package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;

class BpmnStandardLoopPolicyTest {
  private final BpmnDefinitionValidator validator = new BpmnDefinitionValidator();

  @Test
  void allowsBeforeAfterCountedAndConditionBasedLoops() {
    for (String before : List.of("", " testBefore=\"true\"", " testBefore=\"false\"")) {
      for (String condition : List.of("", "${true}", "${false}", "#{approved}", "${amount > 2}")) {
        validator.validate(xml(before + " loopMaximum=\"2\"", condition), "loopReview");
      }
    }
  }

  @Test
  void rejectsUnboundedAndNonIntegerLoopsEvenWithAnExitCondition() {
    for (String maximum : List.of("", "0", "51", "-1", "1.5", "${amount}", "99999999999999999")) {
      rejects(xml("loopMaximum=\"" + maximum + "\"", "${false}"), "loopMaximum");
    }
    rejects(xml("", "${false}"), "loopMaximum");
    for (String before : List.of("", "0", "1", "yes", "True", "${test}")) {
      rejects(xml("loopMaximum=\"2\" testBefore=\"" + before + "\"", ""), "loopTest");
    }
  }

  @Test
  void parsesFullExpressionGrammarAndRejectsUnsafeOrNonBooleanLiterals() {
    for (String condition :
        List.of(
            " ",
            "yes",
            "${1}",
            "${null}",
            "${'true'}",
            "${approved == }",
            "${execution.variables}",
            "${user.fetch()}",
            "${user['class']}")) {
      rejects(xml("loopMaximum=\"2\"", condition), "loopCondition");
    }
    rejects(
        xml("loopMaximum=\"2\"", "${true}")
            .replace(
                "</bpmn:loopCondition>",
                "</bpmn:loopCondition><bpmn:loopCondition>${false}</bpmn:loopCondition>"),
        "loopCondition");
  }

  @Test
  void rejectsDuplicateMixedOrMisplacedLoopConfigurations() {
    String source = xml("loopMaximum=\"2\"", "");
    rejects(source.replace("userTask", "exclusiveGateway"), "loopOwner");
    rejects(
        source.replace(
            "</bpmn:userTask>",
            "<bpmn:standardLoopCharacteristics loopMaximum=\"3\"/></bpmn:userTask>"),
        "loopOwner");
    rejects(
        source.replace(
            "</bpmn:userTask>", "<bpmn:multiInstanceLoopCharacteristics/></bpmn:userTask>"),
        "loopOwner");
    rejects(
        source
            .replace("<bpmn:userTask", "<other:userTask xmlns:other=\"urn:other\"")
            .replace("</bpmn:userTask>", "</other:userTask>"),
        "loopOwner");
  }

  @Test
  void sourceCannotSpoofCompilerMetadataRegardlessOfPrefix() {
    String source = xml("loopMaximum=\"2\"", "");
    rejects(
        source.replace(
            "<bpmn:userTask",
            "<bpmn:userTask xmlns:fake=\""
                + BpmnExecutionCompiler.NAMESPACE
                + "\" fake:standardLoop=\"true\""),
        "compilerReserved");
    String forged =
        source
            .replace(
                "<bpmn:userTask id=\"review\"",
                "<fake:userTask id=\"review\" xmlns:fake=\""
                    + BpmnExecutionCompiler.NAMESPACE
                    + "\"")
            .replace("</bpmn:userTask>", "</fake:userTask>");
    rejects(forged, "compilerReserved");
  }

  @Test
  void compilationPreservesSourceIdentityAndPinsBothDigests() throws Exception {
    String source = xml("loopMaximum=\"2\" testBefore=\"true\"", "${approved}");
    var compiled = BpmnExecutionCompiler.compile(source);
    assertThat(compiled.transformed()).isTrue();
    assertThat(compiled.xml())
        .contains("id=\"review\"", "multiInstanceLoopCharacteristics", "spc:standardLoop")
        .doesNotContain("standardLoopCharacteristics");
    assertThat(compiled.manifest())
        .contains(
            "compiler=simplepoint-bpmn-v1\n",
            "sourceSha256=" + digest(source) + "\n",
            "executionSha256=" + digest(compiled.xml()) + "\n");
    assertThat(BpmnExecutionCompiler.compile(source)).isEqualTo(compiled);
    assertThat(BpmnExecutionCompiler.standardLoopOwner(source)).isEqualTo("review");
    // The normal validator must not accept an engine resource as an authored source.
    assertThatThrownBy(() -> validator.validate(compiled.xml(), "loopReview"))
        .isInstanceOf(WorkflowModelingException.class);
  }

  @Test
  void compilerSupportsDefaultNamespacesAndKeepsDocumentationAheadOfExtensions() {
    String source =
        xml("loopMaximum=\"2\"", "").replace("xmlns:bpmn=", "xmlns=").replace("bpmn:", "");
    validator.validate(source, "loopReview");
    var compiled = BpmnExecutionCompiler.compile(source);
    var document = BpmnDefinitionValidator.parse(compiled.xml());
    assertThat(
            document
                .getElementsByTagNameNS(
                    BpmnDefinitionValidator.BPMN_NAMESPACE, "multiInstanceLoopCharacteristics")
                .getLength())
        .isEqualTo(1);
    assertThat(compiled.xml().indexOf("<documentation>"))
        .isLessThan(compiled.xml().indexOf("<extensionElements>"));
    assertThat(compiled.xml()).contains("Preserved note");
  }

  @Test
  void modelsWithoutStandardLoopsRemainByteExactAndHaveNoCompilerManifest() {
    String source =
        xml("loopMaximum=\"2\"", "")
            .replaceAll(
                "(?s)<bpmn:standardLoopCharacteristics.*?</bpmn:standardLoopCharacteristics>", "");
    var compiled = BpmnExecutionCompiler.compile(source);
    assertThat(compiled.xml()).isEqualTo(source);
    assertThat(compiled.transformed()).isFalse();
    assertThat(compiled.manifest()).isNull();
    assertThat(BpmnExecutionCompiler.standardLoopOwner(source)).isNull();
  }

  private String digest(String source) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
  }

  private void rejects(String source, String rule) {
    assertThatThrownBy(() -> validator.validate(source, "loopReview"))
        .isInstanceOfSatisfying(
            WorkflowModelingException.class,
            error -> {
              assertThat(error.getMessageKey()).isEqualTo("workflow.modeling." + rule);
              assertThat(error.getElementId()).isEqualTo("review");
            });
  }

  private String xml(String attributes, String condition) {
    String expression =
        condition.isEmpty() ? "" : "<bpmn:loopCondition>" + condition + "</bpmn:loopCondition>";
    return """
           <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
               xmlns:flowable="http://flowable.org/bpmn">
             <bpmn:process id="loopReview" isExecutable="true">
               <bpmn:startEvent id="start"/>
               <bpmn:userTask id="review" flowable:assignee="actor">
                 <bpmn:documentation>Preserved note</bpmn:documentation>
                 <bpmn:standardLoopCharacteristics %s>
                   %s
                 </bpmn:standardLoopCharacteristics>
               </bpmn:userTask>
               <bpmn:endEvent id="end"/>
             </bpmn:process>
           </bpmn:definitions>
           """
        .formatted(attributes, expression);
  }
}
