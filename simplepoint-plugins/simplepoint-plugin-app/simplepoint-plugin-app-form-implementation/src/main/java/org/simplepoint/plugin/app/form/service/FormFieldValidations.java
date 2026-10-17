package org.simplepoint.plugin.app.form.service;

import com.google.re2j.Pattern;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;

/** One validation boundary for designer defaults, main records and detail aggregate commands. */
final class FormFieldValidations {
  private FormFieldValidations() {}

  static void definition(FormFieldDefinition field) {
    var rule = field.validation();
    if (rule == null) return;
    if (field.type() != FormFieldType.STRING && field.type() != FormFieldType.TEXT)
      throw new IllegalArgumentException("Text validation requires a text field");
    int capacity = field.type() == FormFieldType.STRING ? field.length() : 1_048_576;
    if (rule.minLength() != null && (rule.minLength() < 0 || rule.minLength() > capacity)
        || rule.maxLength() != null && (rule.maxLength() < 1 || rule.maxLength() > capacity)
        || rule.minLength() != null && rule.maxLength() != null && rule.minLength() > rule.maxLength())
      throw new IllegalArgumentException("Invalid text length constraints");
    if (rule.patternMessage() != null && (rule.pattern() == null || rule.patternMessage().isBlank() || rule.patternMessage().length() > 500))
      throw new IllegalArgumentException("Invalid pattern message");
    if (rule.pattern() != null) {
      if (rule.pattern().isBlank() || rule.pattern().length() > 512)
        throw new IllegalArgumentException("Invalid validation pattern");
      try {
        if (Pattern.compile(rule.pattern()).programSize() > 4096)
          throw new IllegalArgumentException("Validation pattern exceeds complexity budget");
      } catch (com.google.re2j.PatternSyntaxException error) {
        throw new IllegalArgumentException("Invalid validation pattern", error);
      }
    }
  }

  static void value(FormFieldDefinition field, Object input) {
    var rule = field.validation();
    if (rule == null || input == null || input instanceof String text && text.isEmpty()) return;
    if (!(input instanceof String text)) throw new IllegalArgumentException("Text input required");
    int length = text.codePointCount(0, text.length());
    if (rule.minLength() != null && length < rule.minLength()
        || rule.maxLength() != null && length > rule.maxLength())
      throw new IllegalArgumentException("Text length validation failed");
    if (rule.pattern() != null && !Pattern.compile(rule.pattern()).matcher(text).find())
      throw new IllegalArgumentException("Text pattern validation failed");
  }
}
