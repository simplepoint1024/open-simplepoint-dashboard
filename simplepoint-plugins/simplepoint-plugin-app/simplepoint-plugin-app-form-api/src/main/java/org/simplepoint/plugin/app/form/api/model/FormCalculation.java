package org.simplepoint.plugin.app.form.api.model;

/** Optional versioned formula. References identify fields, never mutable column names. */
public record FormCalculation(String languageVersion, String expression) {

  /** Bounds transport metadata before the independent grammar performs semantic validation. */
  public FormCalculation {
    if (!"1".equals(languageVersion)
        || expression == null
        || expression.isBlank()
        || expression.length() > 8192) {
      throw new IllegalArgumentException(
          "Invalid calculation language version or expression budget");
    }
  }
}
