package org.simplepoint.plugin.dna.runtime.api.model;

import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Registers a form model without accepting a client-controlled physical target. */
public record PhysicalModelRegistrationCommand(
    String appId,
    String formId,
    String displayName,
    String placementKey,
    PhysicalSchemaDefinition initialSchema
) {

  /** Normalizes identifiers and rejects incomplete registration requests. */
  public PhysicalModelRegistrationCommand {
    appId = requireText(appId, 64, "appId");
    formId = requireText(formId, 64, "formId");
    displayName = requireText(displayName, 128, "displayName");
    placementKey = placementKey == null || placementKey.isBlank()
        ? "default" : requireText(placementKey, 64, "placementKey");
    Objects.requireNonNull(initialSchema, "initialSchema");
  }

  private static String requireText(
      final String value,
      final int maxLength,
      final String name
  ) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(name + " must contain 1 to " + maxLength + " characters");
    }
    return value.trim();
  }
}
