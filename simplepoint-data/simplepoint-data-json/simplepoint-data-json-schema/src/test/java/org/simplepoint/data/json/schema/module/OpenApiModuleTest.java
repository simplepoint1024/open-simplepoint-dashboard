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
import java.math.BigDecimal;
import jakarta.persistence.Column;

class OpenApiModuleTest {

  @Test
  void decimalSchemasUseExactTextWithRealDatabasePrecisionAndNumericBounds() {
    var config = new SchemaGeneratorConfigBuilder(new ObjectMapper(), SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);
    config.with(new OpenApiModule());
    var schema = new SchemaGenerator(config.build()).generateSchema(DecimalEntity.class);
    var price = schema.path("properties").path("price");
    assertEquals("string", price.path("type").isArray() ? price.path("type").get(0).asText() : price.path("type").asText(), price.toString());
    assertEquals(19, price.path("x-decimal").path("precision").asInt());
    assertEquals(8, price.path("x-decimal").path("scale").asInt());
    assertEquals("0", price.path("x-decimal").path("minimum").asText());
    assertEquals("99999999999.99999999", price.path("x-decimal").path("maximum").asText());
    assertEquals(true, price.path("x-ui").path("x-list-visible").asBoolean());
    assertEquals(false, price.has("minimum"));
    var cost = schema.path("properties").path("cost");
    assertEquals(12, cost.path("x-decimal").path("scale").asInt());
    assertEquals("999999999999.999999999999", cost.path("x-decimal").path("maximum").asText());
    assertEquals(true, java.util.regex.Pattern.matches(price.path("pattern").asText(), "99999999999.99999999"));
    assertEquals(true, java.util.regex.Pattern.matches(price.path("pattern").asText(), "1E-8"));
    assertEquals(false, java.util.regex.Pattern.matches(price.path("pattern").asText(), "NaN"));
  }

  static class DecimalEntity {
    @Column(precision = 19, scale = 8)
    @Schema(minimum = "0", extensions = @Extension(name = "x-ui", properties =
        @ExtensionProperty(name = "x-list-visible", value = "true", parseValue = true)))
    public BigDecimal price;
    @Column(precision = 24, scale = 12)
    public BigDecimal cost;
  }

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
