package org.simplepoint.core.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PermissionPageDescriptorTest {
  @Test void intersectsOperationsAcrossPageModelsAndNormalizesPrimitiveTypes() {
    var member = model("Member", Map.of(
        "orgId", field("java.lang.String", "equals", "in", "like"),
        "enabled", field("boolean", "equals", "not:equals"),
        "name", field("java.lang.String", "equals")));
    var user = model("User", Map.of(
        "orgId", field("java.lang.String", "in", "equals"),
        "enabled", field("java.lang.Boolean", "equals"),
        "name", field("java.lang.String")));
    var common = new PermissionPageDescriptor(List.of(member, user)).commonProperties();
    assertThat(common).containsOnlyKeys("orgId", "enabled");
    assertThat(common.get("orgId").operators()).containsExactly("equals", "in");
    assertThat(common.get("enabled").type()).isEqualTo("java.lang.Boolean");
    PermissionExpression.validate("orgId IN $user.organizationIds AND enabled = true", common);
  }

  @Test void rejectsIncompatibleTypesAndEnumValueContracts() {
    var first = model("A", Map.of("value", field("java.lang.Long", "equals"),
        "status", new PermissionModelDescriptor.Field("Status", "Status", List.of("equals"), List.of("A", "B"))));
    var second = model("B", Map.of("value", field("java.lang.String", "equals"),
        "status", new PermissionModelDescriptor.Field("Status", "Status", List.of("equals"), List.of("A", "C"))));
    assertThat(new PermissionPageDescriptor(List.of(first, second)).commonProperties()).isEmpty();
  }

  @Test void excludesJacksonWriteOnlyAndIgnoredFieldsFromRegisteredMetadata() {
    assertThat(PermissionModelDescriptor.describe(Credentials.class).properties()).containsOnlyKeys("id");
  }

  static class Credentials {
    String id;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password;
    @JsonIgnore String secret;
  }

  private static PermissionModelDescriptor model(String name, Map<String, PermissionModelDescriptor.Field> fields) {
    return new PermissionModelDescriptor(name, true, true, "id", "orgId", fields);
  }

  private static PermissionModelDescriptor.Field field(String type, String... operators) {
    return new PermissionModelDescriptor.Field(type, "field", List.of(operators), List.of());
  }
}
