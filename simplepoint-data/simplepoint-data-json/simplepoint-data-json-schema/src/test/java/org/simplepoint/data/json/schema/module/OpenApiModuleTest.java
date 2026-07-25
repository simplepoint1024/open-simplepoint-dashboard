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
import org.simplepoint.api.schema.UploadField;

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

  @Test
  void uploadFieldAddsOssWidgetOptionsWithoutDataUrlValidation() {
    SchemaGeneratorConfigBuilder config = new SchemaGeneratorConfigBuilder(
        new ObjectMapper(), SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON
    );
    config.with(new OpenApiModule());

    ObjectNode schema = new SchemaGenerator(config.build()).generateSchema(UploadEntity.class);
    ObjectNode picture = (ObjectNode) schema.path("properties").path("picture");

    assertEquals(false, picture.has("format"));
    assertEquals("image", picture.path("x-upload").path("type").asText());
    assertEquals("avatars/users", picture.path("x-upload").path("directory").asText());
    assertEquals("OssImage", picture.path("x-ui").path("widget").asText());
    assertEquals("circle", picture.path("x-ui").path("options").path("shape").asText());
    assertEquals(8, picture.path("x-ui").path("options").path("maxSizeMb").asInt());
    assertEquals(true, picture.path("x-ui").path("x-list-visible").asBoolean());

    ObjectNode document = (ObjectNode) schema.path("properties").path("document");
    assertEquals("file", document.path("x-upload").path("type").asText());
    assertEquals("OssFile", document.path("x-ui").path("widget").asText());
    assertEquals(
        "application/pdf,.docx",
        document.path("x-ui").path("options").path("accept").asText()
    );
  }

  static class DictionaryEntity {

    @DictionaryField("account.status")
    @Schema(extensions = @Extension(name = "x-ui", properties = {
        @ExtensionProperty(name = "x-list-visible", value = "true", parseValue = true),
        @ExtensionProperty(name = "widget", value = "checkboxes")
    }))
    public String status;
  }

  static class UploadEntity {

    @UploadField(
        type = UploadField.Type.IMAGE,
        directory = "avatars/users",
        sourceServiceName = "rbac-avatar",
        maxSizeMb = 8,
        shape = "circle"
    )
    @Schema(
        format = "data-url",
        extensions = @Extension(name = "x-ui", properties = {
            @ExtensionProperty(name = "x-list-visible", value = "true", parseValue = true)
        })
    )
    public String picture;

    @UploadField(
        type = UploadField.Type.FILE,
        directory = "documents/forms",
        accept = "application/pdf,.docx",
        maxSizeMb = 20
    )
    public String document;
  }
}
