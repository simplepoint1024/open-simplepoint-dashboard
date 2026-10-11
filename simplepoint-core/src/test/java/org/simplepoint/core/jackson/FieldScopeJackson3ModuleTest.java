package org.simplepoint.core.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.annotation.PermissionFields;
import org.simplepoint.core.annotation.PermissionResource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class FieldScopeJackson3ModuleTest {
  static class Entity {
    public String name = "Alice";

    @JsonProperty("phone_number")
    public String phone = "123456";

    public String secret = "private";
    public Integer count = 42;
    public Boolean enabled = true;
    public List<String> roles = List.of("admin");
    public String absent;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String password = "never";
  }

  @PermissionResource(Entity.class)
  record Alias(String name, String phone, String secret) {}

  record Projection(@PermissionFields(resource = Entity.class, value = "phone") String display) {}

  record AmountRow(BigDecimal amount, BigDecimal absent, int count, double ratio) {}

  @Test
  void exactDecimalsUseStringsInBeansMapsAndArraysButOtherNumbersRemainNumbers() {
    var amount = new BigDecimal("99999999999.99999999");
    var row = new AmountRow(amount, null, 42, 0.25);
    var json = mapper.readTree(mapper.writeValueAsString(row));
    assertThat(json.path("amount").isString()).isTrue();
    assertThat(json.path("amount").asText()).isEqualTo("99999999999.99999999");
    assertThat(json.path("absent").isNull()).isTrue();
    assertThat(json.path("count").isNumber()).isTrue();
    assertThat(json.path("ratio").isNumber()).isTrue();
    assertThat(mapper.writeValueAsString(Map.of("amount", amount))).isEqualTo("{\"amount\":\"99999999999.99999999\"}");
    assertThat(mapper.writeValueAsString(List.of(new BigDecimal("1E-12"), new BigDecimal("1E+12"))))
        .isEqualTo("[\"0.000000000001\",\"1000000000000\"]");
    assertThat(mapper.writeValueAsString(new BigDecimal("1E+100000000"))).isEqualTo("\"1E+100000000\"");
    assertThat(mapper.readValue(mapper.writeValueAsString(row), AmountRow.class).amount()).isEqualByComparingTo(amount);
    assertThat(mapper.readValue("{\"amount\":99999999999.99999999,\"count\":42,\"ratio\":0.25}", AmountRow.class).amount())
        .isEqualByComparingTo(amount);
  }

  @Test
  void decimalFieldPermissionsAreAppliedBeforeExactSerialization() {
    permissions(Map.of("AmountRow#amount", "MASKED"));
    assertThat(mapper.readTree(mapper.writeValueAsString(new AmountRow(new BigDecimal("0.12345678"), null, 1, 0)))
        .path("amount").asText()).isEqualTo("***");
    permissions(Map.of("AmountRow#amount", "HIDDEN"));
    assertThat(mapper.readTree(mapper.writeValueAsString(new AmountRow(new BigDecimal("0.12345678"), null, 1, 0)))
        .has("amount")).isFalse();
  }

  @JsonFormat(shape = JsonFormat.Shape.ARRAY)
  @PermissionResource(Entity.class)
  record ArrayRow(String name, String phone, String secret) {}

  private final JsonMapper mapper =
      JsonMapper.builder().addModule(new FieldScopeJackson3Module()).build();

  private void permissions(Map<String, String> fields) {
    var context = new AuthorizationContext();
    context.setFieldPermissions(fields);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void cachedSerializersReadCurrentPermissionsWithoutMutatingEntities() {
    var entity = new Entity();
    assertThat(mapper.writeValueAsString(entity))
        .contains("Alice", "123456")
        .doesNotContain("password", "never");
    permissions(
        Map.of(
            "Entity#name",
            "VISIBLE",
            "Entity#phone",
            "MASKED",
            "Entity#secret",
            "HIDDEN",
            "Entity#count",
            "MASKED",
            "Entity#enabled",
            "MASKED",
            "Entity#roles",
            "MASKED",
            "Entity#absent",
            "MASKED"));
    var json = mapper.readTree(mapper.writeValueAsString(entity));
    assertThat(json.path("name").asText()).isEqualTo("Alice");
    for (String name : List.of("phone_number", "count", "enabled", "roles"))
      assertThat(json.path(name).asText()).isEqualTo("***");
    assertThat(json.has("secret")).isFalse();
    assertThat(json.get("absent").isNull()).isTrue();
    assertThat(entity.phone).isEqualTo("123456");
    assertThat(entity.count).isEqualTo(42);
    permissions(Map.of("Entity#phone", "VISIBLE"));
    assertThat(mapper.writeValueAsString(entity)).contains("123456");
  }

  @Test
  void aliasesSourceFieldsAndArrayShapesShareTheSamePolicy() {
    permissions(Map.of("Entity#phone", "MASKED", "Entity#secret", "HIDDEN"));
    assertThat(mapper.writeValueAsString(new Alias("Alice", "123456", "private")))
        .contains("***")
        .doesNotContain("123456", "private", "secret");
    assertThat(mapper.writeValueAsString(new Projection("123456")))
        .isEqualTo("{\"display\":\"***\"}");
    assertThat(mapper.writeValueAsString(new ArrayRow("Alice", "123456", "private")))
        .isEqualTo("[\"Alice\",\"***\",null]");
  }

  @Test
  void canonicalAndLegacyPoliciesKeepTheStrictestRestrictionAndUnknownAccessFailsClosed() {
    permissions(Map.of(Entity.class.getName() + "#phone", "EDITABLE", "Entity#phone", "MASKED",
        "Entity#name", "UNKNOWN"));
    var json = mapper.readTree(mapper.writeValueAsString(new Entity()));
    assertThat(json.path("phone_number").asText()).isEqualTo("***");
    assertThat(json.has("name")).isFalse();
    assertThat(mapper.writeValueAsString(Map.of("name", "untyped response"))).contains("untyped response");
  }

  @Test
  void bootAutomaticallyRegistersModuleOnTheHttpMapperAlongsideLegacySchemaMapper() {
    permissions(Map.of("Entity#secret", "HIDDEN", "Entity#phone", "MASKED"));
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context)
                  .hasSingleBean(FieldScopeJackson3Module.class)
                  .hasSingleBean(JsonMapper.class)
                  .hasSingleBean(com.fasterxml.jackson.databind.ObjectMapper.class);
              String json = context.getBean(JsonMapper.class).writeValueAsString(new Entity());
              assertThat(json)
                  .contains("***")
                  .doesNotContain("123456", "private", "secret", "password");
            });
  }
}
