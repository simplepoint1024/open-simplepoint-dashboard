package org.simplepoint.core.jackson;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.annotation.PermissionResource;

class FieldPermissionPolicyTest {
  static class Person {
    public String name = "Alice";
    @JsonProperty("phone_number")
    public String phone = "13812345678";
    public String secret = "never disclose";
  }

  @PermissionResource(Person.class)
  static class PersonView {
    public String name = "Alice";
    public String phone = "13812345678";
    public String secret = "never disclose";
  }

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new FieldScopeJacksonModule());

  private void scope() {
    AuthorizationContext context = new AuthorizationContext();
    context.setFieldPermissions(Map.of(
        "Person#name", "VISIBLE", "Person#phone", "MASKED", "Person#secret", "HIDDEN"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void entityAndAliasedDtoMaskAndHideWithoutChangingOriginalValues() throws Exception {
    scope();
    Person person = new Person();
    var entity = mapper.readTree(mapper.writeValueAsString(person));
    var dto = mapper.readTree(mapper.writeValueAsString(new PersonView()));
    assertThat(entity.has("secret")).isFalse();
    assertThat(dto.has("secret")).isFalse();
    assertThat(entity.get("phone_number").asText()).isEqualTo("***");
    assertThat(dto.get("phone").asText()).isEqualTo("***");
    assertThat(person.phone).isEqualTo("13812345678");
    assertThat(FieldPermissionPolicy.writable(Person.class, "phone")).isFalse();
  }

  @Test
  void schemaAndExplicitMapProjectionSharePolicy() throws Exception {
    scope();
    var schema = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
        "{\"properties\":{\"secret\":{},\"phone\":{\"default\":\"sensitive\"},\"name\":{}},\"required\":[\"secret\",\"name\"]}");
    FieldPermissionPolicy.applySchema(Person.class, schema);
    assertThat(schema.path("properties").has("secret")).isFalse();
    assertThat(schema.path("properties").path("phone").path("readOnly").asBoolean()).isTrue();
    assertThat(schema.path("properties").path("phone").has("default")).isFalse();
    assertThat(schema.path("required").toString()).isEqualTo("[\"name\"]");
    assertThat(FieldPermissionPolicy.project(Person.class, Map.of("secret", "raw", "phone", "123456", "name", "Alice")))
        .containsExactlyInAnyOrderEntriesOf(Map.of("phone", "***", "name", "Alice"));
  }

  @Test
  void serializersEvaluateCurrentRequestRatherThanCachedPermissions() throws Exception {
    assertThat(mapper.writeValueAsString(new Person())).contains("never disclose");
    scope();
    assertThat(mapper.writeValueAsString(new Person())).doesNotContain("never disclose", "13812345678");
    cleanup();
    assertThat(mapper.writeValueAsString(new Person())).contains("never disclose");
  }
}
