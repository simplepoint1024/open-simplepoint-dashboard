package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.simplepoint.plugin.workflow.api.WorkflowException;

/** Native numeric text must not pass through JavaScript Number or retain expression spelling. */
class WorkflowNumericLiteralPreviewTest {

  private final WorkflowNumericLiteralPreview service = new WorkflowNumericLiteralPreview();

  @TestFactory
  Stream<DynamicTest> engineIdentityTextMatchesTheSharedContract() throws Exception {
    List<LiteralCase> cases;
    try (var input = getClass().getResourceAsStream("/workflow-expressions/numericLiterals.fixture.json")) {
      assertThat(input).isNotNull();
      cases = new ObjectMapper().readValue(input, new TypeReference<List<LiteralCase>>() {});
    }
    return cases.stream().map(item -> DynamicTest.dynamicTest(item.literal(), () -> {
      assertThat(service.preview("${" + item.literal() + "}")).isEqualTo(item.value());
      assertThat(service.preview("  ${ " + item.literal() + " }  ")).isEqualTo(item.value());
    }));
  }

  @Test
  void rejectsEveryNonNumericExecutionSurfaceAndOversizedInput() {
    for (String expression : List.of("", "1e2", "${user}", "${true}", "${null}", "${'100'}", "#{1e2}",
        "${1 + 2}", "${foo()}", "${user.class}", "${execution}", "${1}${2}", "${01}", "${1e}",
        "${" + "1".repeat(500) + "}")) {
      assertThatThrownBy(() -> service.preview(expression)).as(expression).isInstanceOf(WorkflowException.class);
    }
    assertThatThrownBy(() -> service.preview(null)).isInstanceOf(WorkflowException.class);
  }

  private record LiteralCase(String literal, String value) {
  }
}
