package org.simplepoint.plugin.app.form.component;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.simplepoint.plugin.app.form.api.model.*;
import org.simplepoint.plugin.app.form.api.service.FormComponentContributor;
import org.simplepoint.plugin.app.form.api.error.*;

/** Bounded declarative display and input capabilities; documents never supply executable code. */
public final class StandardComponentsContributor implements FormComponentContributor {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Override public Collection<FormComponentDescriptor> descriptors() {
    return List.of(
        descriptor("display.divider", null, Map.of()),
        descriptor("display.image", null, Map.of("src", text("src", ""), "height", integer("height", 180))),
        descriptor("display.alert", null, Map.of("text", text("text", "提示内容"), "severity", text("severity", "info"))),
        descriptor("display.progress", null, Map.of("percent", integer("percent", 0))),
        descriptor("display.icon", null, Map.of("icon", text("icon", "InfoCircleOutlined"), "size", integer("size", 24))),
        descriptor("display.markdown", null, Map.of("text", text("text", "### 标题\n请输入内容"))),
        descriptor("field.rating", FormFieldType.INT32, Map.of("count", integer("count", 5))),
        descriptor("field.cascader", FormFieldType.JSON, Map.of("options", options())),
        descriptor("field.transfer", FormFieldType.JSON, Map.of("options", options())));
  }

  @Override public void validate(FormComponentDefinition node) {
    switch (node.componentType()) {
      case "display.image" -> {
        String src = text(node, "src");
        if (!src.isEmpty() && !safeUrl(src)) fail("Image source must be an HTTP(S) URL or a local absolute path");
        range(node, "height", 40, 800);
      }
      case "display.alert" -> {
        if (!Set.of("info", "success", "warning", "error").contains(text(node, "severity"))) fail("Unknown alert severity");
        boundedText(node, 4000);
      }
      case "display.markdown" -> boundedText(node, 16000);
      case "display.progress" -> range(node, "percent", 0, 100);
      case "display.icon" -> {
        if (!text(node, "icon").matches("[A-Za-z][A-Za-z0-9]{0,79}Outlined")) fail("Invalid platform icon name");
        range(node, "size", 12, 96);
      }
      case "field.rating" -> range(node, "count", 1, 10);
      case "field.cascader", "field.transfer" -> options(node);
      default -> { }
    }
  }

  public static JsonNode options(FormComponentDefinition node) {
    try {
      String raw = text(node, "options");
      if (raw.length() > 16000) fail("Selection options exceed the size budget");
      JsonNode options = JSON.readTree(raw);
      validateOptions(options, 1, new int[]{0}, node.componentType().equals("field.transfer"));
      return options;
    } catch (java.io.IOException error) { throw invalid("Selection options must be JSON"); }
  }

  private static void validateOptions(JsonNode options, int depth, int[] count, boolean flat) {
    if (options == null || !options.isArray() || depth > 6) fail("Selection options must be an array at most six levels deep");
    Set<String> values = new HashSet<>();
    for (JsonNode option : options) {
      if (++count[0] > 200 || !option.isObject() || !option.path("value").isTextual()
          || !option.path("label").isTextual() || option.path("value").asText().isBlank()
          || option.path("value").asText().length() > 256 || option.path("label").asText().isBlank()
          || option.path("label").asText().length() > 120 || !values.add(option.path("value").asText())) fail("Invalid or duplicate selection option");
      option.fieldNames().forEachRemaining(key -> {if (!Set.of("value", "label", "children").contains(key)) fail("Unknown selection option property");});
      if (option.has("children")) {
        if (flat) fail("Transfer options cannot be nested");
        validateOptions(option.get("children"), depth + 1, count, false);
      }
    }
  }

  public static boolean safeUrl(String value) {
    if (value.length() > 2048 || value.chars().anyMatch(c -> c <= 32 || c == 92 || c == 127)) return false;
    if (value.startsWith("/") && !value.startsWith("//")) return true;
    try {var uri = java.net.URI.create(value); return Set.of("http", "https").contains(uri.getScheme())
        && uri.getHost() != null && uri.getUserInfo() == null;} catch (RuntimeException error) {return false;}
  }

  private static void range(FormComponentDefinition node, String key, int min, int max) {
    try {int value = Integer.parseInt(text(node, key)); if (value < min || value > max) fail("Component numeric property is outside its range");}
    catch (NumberFormatException error) {fail("Component numeric property must be an integer");}
  }
  private static void boundedText(FormComponentDefinition node, int max) {if (text(node, "text").length() > max) fail("Component text exceeds its size budget");}
  private static String text(FormComponentDefinition node, String key) {var value = node.properties().get(key); return value == null || value.value() == null ? "" : value.value();}
  private static FormComponentDescriptor descriptor(String type, FormFieldType field, Map<String, FormComponentPropertyDescriptor> props) {
    return new FormComponentDescriptor(type, "1", false, field != null, field == null ? Set.of() : Set.of(field), props, Set.of(), Set.of());
  }
  private static FormComponentPropertyDescriptor text(String key, String value) {return property(key, FormValueKind.STRING, value);}
  private static FormComponentPropertyDescriptor integer(String key, int value) {return property(key, FormValueKind.INTEGER, Integer.toString(value));}
  private static FormComponentPropertyDescriptor options() {return property("options", FormValueKind.JSON, "[]");}
  private static FormComponentPropertyDescriptor property(String key, FormValueKind kind, String value) {return new FormComponentPropertyDescriptor(key, Set.of(kind), true, new FormValue(kind, value));}
  private static void fail(String message) {throw invalid(message);}
  private static ApplicationFormException invalid(String message) {return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message);}
}
