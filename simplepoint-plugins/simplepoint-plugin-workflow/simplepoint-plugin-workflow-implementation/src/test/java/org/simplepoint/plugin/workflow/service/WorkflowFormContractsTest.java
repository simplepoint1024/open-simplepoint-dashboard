package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract.Binding;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract.Variable;

class WorkflowFormContractsTest {

  private final WorkflowFormContracts contracts = new WorkflowFormContracts(new ObjectMapper());

  @Test
  void exactDecimalsRoundTripAsTextAndRejectLossyOrUnboundedRepresentations() {
    var contract = contract(List.of(variable("amount", "decimal", "both", true, null)));
    String exact = "9007199254740993.12345678901234567890";
    var wire = contracts.submission(contract, Map.of("amount", exact), Map.of(), "start");
    var engine = contracts.engineVariables(contract, wire);
    assertThat(engine.get("amount")).isEqualTo(new java.math.BigDecimal(exact));
    assertThat(contracts.project(contract, engine, "task")).containsEntry("amount", exact);
    for (Object invalid : List.of(1.25, 1, "1e2", "01", "-", " 1", "9".repeat(39), "0." + "0".repeat(38) + "1")) {
      assertThatThrownBy(() -> contracts.submission(contract, Map.of("amount", invalid), Map.of(), "start"))
          .isInstanceOf(WorkflowException.class);
    }
  }

  @TestFactory
  Stream<DynamicTest> sharedRoleAuthoritiesMatchBothScalarAndArrayFormContracts() throws Exception {
    List<RoleCase> cases;
    try (var input = getClass().getResourceAsStream("/workflow-expressions/roleAuthorities.fixture.json")) {
      assertThat(input).isNotNull();
      cases = new ObjectMapper().readValue(input, new TypeReference<List<RoleCase>>() {});
    }
    return cases.stream().flatMap(item -> Stream.of("role", "roles").map(type ->
        DynamicTest.dynamicTest(type + " authority=" + item.value(), () -> {
          Object expected = "role".equals(type) ? item.value() : List.of(item.value());
          var value = contract(List.of(variable("reviewRole", type, "both", false, expected)));
          if (item.selectable()) {
            assertThat(contracts.normalize(value).variables().get(0).defaultValue()).isEqualTo(expected);
          } else {
            assertThatThrownBy(() -> contracts.normalize(value)).isInstanceOf(WorkflowException.class);
          }
        })));
  }

  @Test
  void legacyTaskFormsRetainUserValuesButNeverExposeOrAcceptPlatformEngineAndPrototypeNames() {
    assertThat(contracts.project(WorkflowFormContract.empty(), Map.of("amount", 42, "approved", false,
        "initiator", "actor", "workflowVersion", 1, "execution", "private", "__proto__", "private"), "task"))
        .containsExactlyInAnyOrderEntriesOf(Map.of("amount", 42, "approved", false));
    for (String name : List.of("__proto__", "constructor", "prototype")) {
      assertThatThrownBy(() -> contracts.normalize(contract(List.of(variable(name, "string", "both", false, null)))))
          .isInstanceOf(WorkflowException.class);
    }
  }

  @Test
  void roundTripsPinnedProviderNeutralContractAndDefaults() {
    var contract = contract(List.of(variable("approver", "user", "start", true, "member1")));
    contract = new WorkflowFormContract(1, contract.variables(),
        new Binding("forms", "leave", 3, Map.of("reviewer", "approver")), Map.of());
    String json = contracts.write(contract);
    assertThat(contracts.write(contracts.read(json))).isEqualTo(json);
    assertThat(contracts.read(json).startBinding().version()).isEqualTo(3);
    assertThat(contracts.project(contract, Map.of("initiator", "hidden"), "start"))
        .containsExactlyEntriesOf(Map.of("approver", "member1"));
  }

  @Test
  void refusesDuplicateReservedAndAmbiguousVariableNames() {
    for (String name : List.of("initiator", "workflowVersion", "a.b", "a-b", "1number", "true", "or", "Task", "execution")) {
      assertThatThrownBy(() -> contracts.write(contract(List.of(variable(name, "string", "both", false, null)))))
          .isInstanceOf(WorkflowException.class);
    }
    assertThatThrownBy(() -> contracts.normalize(contract(List.of(
        variable("name", "string", "both", false, null), variable("name", "boolean", "both", false, null)))))
        .isInstanceOf(WorkflowException.class);
  }

  @Test
  void validatesTypedScalarDirectoryAndDateDefaults() {
    for (Variable variable : List.of(
        variable("amount", "number", "both", false, "100"),
        variable("approved", "boolean", "both", false, "true"),
        variable("date", "date", "start", false, "2026-02-30"),
        variable("people", "users", "start", false, List.of("member", "member")),
        variable("kind", "unknown", "start", false, null))) {
      assertThatThrownBy(() -> contracts.normalize(contract(List.of(variable))))
          .isInstanceOf(WorkflowException.class);
    }
  }

  @Test
  void roleDefaultsAcceptRealAuthoritiesWithoutAmbiguousCsvDelimiters() {
    contracts.normalize(contract(List.of(variable("roles", "roles", "both", false,
        List.of("部门/审核", "review (operations)")), variable("role", "role", "start", false, "部门/审核"))));
    for (String role : List.of("role,other", " leading", "trailing ", "role\tcontrol", "x".repeat(101))) {
      assertThatThrownBy(() -> contracts.normalize(contract(List.of(variable("role", "role", "both", false, role)))))
          .isInstanceOf(WorkflowException.class);
    }
  }

  @Test
  void enforcesStageOwnershipAndRequiredValuesWithoutOverwritingCurrentData() {
    var contract = contract(List.of(variable("reason", "string", "start", true, null),
        variable("approved", "boolean", "task", true, false)));
    assertThat(contracts.submission(contract, Map.of("reason", "leave"), Map.of(), "start"))
        .containsExactlyEntriesOf(Map.of("reason", "leave"));
    assertThat(contracts.submission(contract, Map.of(), Map.of("reason", "leave"), "task"))
        .containsExactlyEntriesOf(Map.of("approved", false));
    assertThatThrownBy(() -> contracts.submission(contract, Map.of("reason", "tampered"), Map.of(), "task"))
        .isInstanceOf(WorkflowException.class).hasMessageContaining("不允许");
    assertThatThrownBy(() -> contracts.submission(contract, Map.of(), Map.of(), "start"))
        .isInstanceOf(WorkflowException.class).hasMessageContaining("请填写");
    assertThat(contracts.project(contract, Map.of("reason", "leave", "approved", true, "initiator", "system"), "task"))
        .containsExactlyEntriesOf(Map.of("approved", true));
  }

  @Test
  void rejectsUnpinnedBindingsUndeclaredMappingsAndFutureSchemaVersions() {
    for (Binding binding : List.of(new Binding("forms", "form", 0, Map.of()),
        new Binding("forms", "form", 1, Map.of("field", "unknown")))) {
      assertThatThrownBy(() -> contracts.normalize(new WorkflowFormContract(1, List.of(), binding, Map.of())))
          .isInstanceOf(WorkflowException.class);
    }
    assertThatThrownBy(() -> contracts.normalize(new WorkflowFormContract(2, List.of(), null, Map.of())))
        .isInstanceOf(WorkflowException.class);
  }

  @Test
  void legacyContractsKeepConstrainedAdvancedVariablesAvailable() {
    assertThat(contracts.read(null)).isEqualTo(WorkflowFormContract.empty());
    assertThat(contracts.submission(contracts.read(null), Map.of("legacy", "value"), Map.of(), "task"))
        .containsExactlyEntriesOf(Map.of("legacy", "value"));
  }

  private WorkflowFormContract contract(final List<Variable> variables) {
    return new WorkflowFormContract(1, variables, null, Map.of());
  }

  private Variable variable(
      final String name, final String type, final String scope, final boolean required, final Object defaultValue
  ) {
    return new Variable(name, name, type, scope, required, defaultValue);
  }

  private record RoleCase(String value, boolean selectable) {
  }
}
