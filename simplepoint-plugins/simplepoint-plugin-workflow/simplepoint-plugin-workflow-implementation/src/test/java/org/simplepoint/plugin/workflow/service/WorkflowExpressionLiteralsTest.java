package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import org.flowable.common.engine.impl.de.odysseus.el.util.SimpleContext;
import org.flowable.common.engine.impl.el.FlowableExpressionFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** The frontend codec and the real engine factory consume one literal contract, not two copies. */
class WorkflowExpressionLiteralsTest {

  @TestFactory
  Stream<DynamicTest> literalsAndTypedComparisonsUseTheActualFlowableGrammar() throws Exception {
    List<LiteralCase> cases;
    try (var input = getClass().getResourceAsStream("/workflow-expressions/stringLiterals.fixture.json")) {
      assertThat(input).isNotNull();
      cases = new ObjectMapper().readValue(input, new TypeReference<List<LiteralCase>>() {});
    }
    return cases.stream().map(item -> DynamicTest.dynamicTest(item.id(), () -> {
      var factory = new FlowableExpressionFactory();
      var context = new SimpleContext();
      assertThat(factory.createValueExpression(context, "${" + item.literal() + "}", Object.class).getValue(context))
          .isEqualTo(item.value());
      context.setVariable("reason", factory.createValueExpression(item.value(), Object.class));
      var comparison = factory.createValueExpression(context, "${reason == " + item.literal() + "}", Object.class);
      assertThat(comparison.getValue(context)).isEqualTo(true);
      context.setVariable("reason", factory.createValueExpression("different " + item.value(), Object.class));
      assertThat(factory.createValueExpression(context, "${reason == " + item.literal() + "}", Object.class).getValue(context))
          .isEqualTo(false);
      String condition = "${reason == " + item.literal() + "}";
      String body = condition.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", "&#13;");
      String xml = WorkflowBpmnTemplates.approval("stringProcess", "Strings")
          .replace("<bpmn:sequenceFlow id=\"flow_review_end\" sourceRef=\"review\" targetRef=\"end\"/>",
              "<bpmn:sequenceFlow id=\"flow_review_end\" sourceRef=\"review\" targetRef=\"end\">"
                  + "<bpmn:conditionExpression>" + body + "</bpmn:conditionExpression></bpmn:sequenceFlow>");
      assertThat(xml).contains("<bpmn:conditionExpression>");
      new BpmnDefinitionValidator().validate(xml, "stringProcess");
    }));
  }

  private record LiteralCase(String id, String value, String literal) {
  }
}
