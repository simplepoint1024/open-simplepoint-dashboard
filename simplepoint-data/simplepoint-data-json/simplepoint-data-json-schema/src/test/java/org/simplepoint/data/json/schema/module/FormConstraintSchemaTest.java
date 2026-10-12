package org.simplepoint.data.json.schema.module;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.Module;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;

class FormConstraintSchemaTest {
  @Test
  void
      persistedTextRequirednessUsesBothDeclarationsWithoutMakingOptionalOrReadonlyFieldsMandatory() {
    var config = new JsonSchemaAutoConfiguration(new ObjectMapper());
    var schema =
        config
            .schemaGenerator(
                new LinkedHashSet<>(
                    List.of(
                        config.openApiModule(),
                        config.jacksonModule(),
                        config.javaxValidationModule())))
            .generateSchema(PersistedText.class);
    assertThat(schema.path("required")).extracting(value -> value.asText()).containsExactly("name");
    assertThat(schema.at("/properties/name/type").asText()).isEqualTo("string");
  }

  static class PersistedText {
    @jakarta.persistence.Column(nullable = false)
    @Schema(minLength = 1)
    public String name;

    @jakarta.persistence.Column(nullable = false)
    @Schema(minLength = 1, accessMode = Schema.AccessMode.READ_ONLY)
    public String generated;

    @jakarta.persistence.Column(nullable = false)
    @Schema(minLength = 1, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public String optional;

    @Schema(minLength = 1)
    public String optionalText;

    @jakarta.persistence.Column(nullable = false)
    public String audit;
  }

  @Test
  void declarationsProduceIdenticalRequiredPatternAndBoundsRegardlessOfModuleOrder() {
    var config = new JsonSchemaAutoConfiguration(new ObjectMapper());
    for (List<Module> order :
        List.of(
            List.<Module>of(
                config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()),
            List.<Module>of(
                config.javaxValidationModule(), config.jacksonModule(), config.openApiModule()))) {
      var schema = config.schemaGenerator(new LinkedHashSet<>(order)).generateSchema(Input.class);
      assertThat(schema.path("required"))
          .anySatisfy(value -> assertThat(value.asText()).isEqualTo("code"));
      assertThat(schema.path("required"))
          .anySatisfy(value -> assertThat(value.asText()).isEqualTo("title"));
      assertThat(schema.path("required"))
          .anySatisfy(value -> assertThat(value.asText()).isEqualTo("enabled"));
      assertThat(schema.path("properties").path("code").path("type").asText()).isEqualTo("string");
      assertThat(schema.path("properties").path("code").path("pattern").asText())
          .isEqualTo("^[A-Z]+$");
      assertThat(schema.path("properties").path("count").path("exclusiveMinimum").asInt())
          .isEqualTo(2);
      assertThat(schema.path("properties").path("count").path("maximum").asInt()).isEqualTo(5);
      assertThat(schema.path("properties").path("optional").path("minLength").asInt()).isEqualTo(2);
      assertThat(schema.path("required"))
          .noneSatisfy(value -> assertThat(value.asText()).isEqualTo("optional"));
      assertThat(schema.path("properties").path("alias").toString()).contains("^(?:");
      assertThat(schema.path("properties").path("tags").path("minItems").asInt()).isEqualTo(1);
      assertThat(schema.path("properties").path("tags").path("maxItems").asInt()).isEqualTo(3);
    }
  }

  @Test
  void actualAnnotationContractIsExportedWithServerVerdictsForBrowserParity() throws Exception {
    var mapper = new ObjectMapper();
    var config = new JsonSchemaAutoConfiguration(mapper);
    var schema =
        config
            .schemaGenerator(
                new LinkedHashSet<>(
                    List.of(
                        config.openApiModule(),
                        config.jacksonModule(),
                        config.javaxValidationModule())))
            .generateSchema(Input.class);
    var cases = new java.util.ArrayList<Map<String, Object>>();
    try (var validator = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      for (var patch :
          List.<Map<String, Object>>of(
              Map.of(),
              Map.of("code", ""),
              Map.of("code", "lowercase"),
              Map.of("title", " \t\n"),
              Map.of("optional", ""),
              Map.of("alias", "abc\n"),
              Map.of("alias", "abc"),
              Map.of("count", 2),
              Map.of("count", 4),
              Map.of("count", 7),
              Map.of("tags", List.of()),
              Map.of("optionalChoice", "INVALID"),
              java.util.Collections.singletonMap("optionalChoice", null),
              Map.of("permissions", List.of("READ", "WRITE")),
              Map.of("permissions", List.of("INVALID")))) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("code", "OK");
        data.put("title", "Title");
        data.put("enabled", true);
        data.putAll(patch);
        boolean valid = true;
        try {
          org.simplepoint.core.validation.DeclarativeFormValidation.validate(
              mapper.convertValue(data, Input.class), java.util.Set.of(), validator.getValidator());
        } catch (org.simplepoint.core.validation.InvalidFormException error) {
          valid = false;
        }
        cases.add(Map.of("data", data, "valid", valid));
      }
    }
    var contract = mapper.valueToTree(Map.of("schema", schema, "cases", cases));
    Path generated = Path.of("build/generated-test-resources/form-validation-contract.json");
    Files.createDirectories(generated.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(generated.toFile(), contract);
    var fixture =
        Path.of("../../../simplepoint-react/scripts/form-validation-contract.fixture.json");
    assertThat(Files.exists(fixture))
        .as("Commit the actual generated annotation contract")
        .isTrue();
    assertThat(mapper.readTree(fixture.toFile()))
        .isEqualTo(mapper.readTree(mapper.writeValueAsString(contract)));
  }

  static class Input {
    @Schema(allowableValues = {"ON", "OFF"})
    public String optionalChoice;

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        nullable = true,
        minLength = 2,
        maxLength = 8,
        pattern = "^[A-Z]+$")
    public String code;

    @NotBlank public String title;
    @NotNull public Boolean enabled;

    @Schema(minimum = "2", exclusiveMinimum = true, maximum = "9", multipleOf = 0.5)
    @jakarta.validation.constraints.Min(3)
    @jakarta.validation.constraints.Max(5)
    public Double count;

    @Schema(minLength = 2)
    public String optional;

    @Pattern(regexp = "[a-z]+")
    public String alias;

    @Size(min = 1, max = 3)
    public List<String> tags;

    @Schema(allowableValues = {"READ", "WRITE"})
    public List<String> permissions;

    @Schema(defaultValue = "3")
    public Integer initialCount;

    @Schema(defaultValue = "true")
    public Boolean initialEnabled;

    @Pattern(regexp = "[a-z]+", flags = Pattern.Flag.CASE_INSENSITIVE)
    public String serverOnlyPattern;
  }
}
