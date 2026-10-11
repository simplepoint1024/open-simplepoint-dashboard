package org.simplepoint.core.jackson;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
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

  static class JoinedView {
    @org.simplepoint.core.annotation.PermissionFields(resource = Person.class, value = {"name", "phone"})
    String display;
    @org.simplepoint.core.annotation.PermissionFields(resourceName = "org.simplepoint.core.jackson.FieldPermissionPolicyTest$Person", value = "secret")
    String protectedValue;
  }

  @PermissionResource(Person.class)
  static class AliasedJoinedView {
    @org.simplepoint.core.annotation.PermissionFields(resource = Person.class, value = {"name", "phone"})
    public String display = "never disclose phone";
    @org.simplepoint.core.annotation.PermissionFields(resource = Person.class, value = "secret")
    public String protectedValue = "never disclose secret";
  }

  @Test void projectionAnnotationsAreNotLostWhenTheirDtoAlsoAliasesTheOwningEntity() throws Exception {
    scope();
    assertThat(FieldPermissionPolicy.access(AliasedJoinedView.class, "display")).isEqualTo("MASKED");
    assertThat(FieldPermissionPolicy.access(AliasedJoinedView.class, "protectedValue")).isEqualTo("HIDDEN");
    var legacy = mapper.readTree(mapper.writeValueAsString(new AliasedJoinedView()));
    var modernMapper = tools.jackson.databind.json.JsonMapper.builder().addModule(new FieldScopeJackson3Module()).build();
    var modern = modernMapper.readTree(modernMapper.writeValueAsString(new AliasedJoinedView()));
    assertThat(legacy.path("display").asText()).isEqualTo("***"); assertThat(legacy.has("protectedValue")).isFalse();
    assertThat(modern.path("display").asText()).isEqualTo("***"); assertThat(modern.has("protectedValue")).isFalse();
  }

  @Test
  void computedProjectionsInheritTheStrictestSourcePolicy() {
    scope();
    assertThat(FieldPermissionPolicy.access(JoinedView.class, "display")).isEqualTo("MASKED");
    // Named bindings use canonical or simple entity names without introducing API dependency cycles.
    var context = new AuthorizationContext();
    context.setFieldPermissions(Map.of(Person.class.getName() + "#secret", "HIDDEN",
        Person.class.getName() + "#phone", "MASKED"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertThat(FieldPermissionPolicy.project(JoinedView.class, Map.of("display", "sensitive", "protectedValue", "secret")))
        .containsExactlyEntriesOf(Map.of("display", "***"));
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
    ResourcePolicyContext.clear();
  }

  @Test
  void pagePolicyOverridesLegacyFieldsOnlyForItsServerSelectedPage() {
    AuthorizationContext context = new AuthorizationContext();
    context.setFieldPermissions(Map.of("Person#phone", "VISIBLE"));
    context.setResourceFieldPermissions(Map.of("people.view", Map.of("Person#phone", "HIDDEN")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertThat(FieldPermissionPolicy.access(Person.class, "phone")).isEqualTo("VISIBLE");
    ResourcePolicyContext.set("people.view");
    assertThat(FieldPermissionPolicy.access(Person.class, "phone")).isEqualTo("HIDDEN");
    ResourcePolicyContext.clear();
    assertThat(FieldPermissionPolicy.access(Person.class, "phone")).isEqualTo("VISIBLE");
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
  void externalExtensionModelsCannotBypassEitherJsonConverter() throws Exception {
    var type = com.example.permission.ExtensionRecord.class;
    var context = new AuthorizationContext();
    context.setResourceFieldPermissions(Map.of("extension.view", Map.of(
        type.getName() + "#secret", "HIDDEN", type.getName() + "#name", "MASKED")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    ResourcePolicyContext.set("extension.view");
    var record = new com.example.permission.ExtensionRecord("private", "secret");
    var legacy = mapper.readTree(mapper.writeValueAsString(record));
    var modernMapper = tools.jackson.databind.json.JsonMapper.builder().addModule(new FieldScopeJackson3Module()).build();
    var modern = modernMapper.readTree(modernMapper.writeValueAsString(record));
    assertThat(legacy.has("secret")).isFalse();
    assertThat(modern.has("secret")).isFalse();
    assertThat(legacy.path("name").asText()).isEqualTo("***");
    assertThat(modern.path("name").asText()).isEqualTo("***");
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
