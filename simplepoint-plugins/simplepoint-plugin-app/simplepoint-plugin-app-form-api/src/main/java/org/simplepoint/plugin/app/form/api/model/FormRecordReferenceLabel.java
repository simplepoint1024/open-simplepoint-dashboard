package org.simplepoint.plugin.app.form.api.model;

/** Response-only label tied to the original identity; null means the name is unavailable. */
public record FormRecordReferenceLabel(String id,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS) String label) {
  /** Bounds optional presentation metadata without changing the record's stored identity. */
  public FormRecordReferenceLabel {
    if (id == null || id.isBlank() || id.length() > 64 || "***".equals(id)
        || label != null && (label.isBlank() || label.codePointCount(0, label.length()) > 256)) {
      throw new IllegalArgumentException("Invalid reference label");
    }
  }
}
