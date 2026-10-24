package org.simplepoint.plugin.app.form.api.model;

/** Value-free calculation diagnostic. Identities identify only the authorized document's fields. */
public record FormCalculationDiagnostic(
    String code, int line, int column, String scope, String fieldId, Integer row) {}
