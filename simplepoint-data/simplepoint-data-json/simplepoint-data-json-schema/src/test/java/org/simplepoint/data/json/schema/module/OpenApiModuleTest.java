package org.simplepoint.data.json.schema.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.schema.DictionaryField;

class OpenApiModuleTest {

  @Test
  void dictionaryFieldAddsStableDictionaryMetadataAndKeepsExistingUiOptions() {
    SchemaGeneratorConfigBuilder config = new SchemaGeneratorConfigBuilder(
        new ObjectMapper(), SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON
    );
    config.with(new OpenApiModule());

    ObjectNode schema = new SchemaGenerator(config.build()).generateSchema(DictionaryEntity.class);
    ObjectNode status = (ObjectNode) schema.path("properties").path("status");

    assertEquals("account.status", status.path("x-dictionary-code").asText());
    assertEquals("account.status", status.path("x-ui").path("dictCode").asText());
    assertEquals("checkboxes", status.path("x-ui").path("widget").asText());
    assertEquals(true, status.path("x-ui").path("x-list-visible").asBoolean());
  }

  static class DictionaryEntity {

    @DictionaryField("account.status")
    @Schema(extensions = @Extension(name = "x-ui", properties = {
        @ExtensionProperty(name = "x-list-visible", value = "true", parseValue = true),
        @ExtensionProperty(name = "widget", value = "checkboxes")
    }))
    public String status;
  }
}
