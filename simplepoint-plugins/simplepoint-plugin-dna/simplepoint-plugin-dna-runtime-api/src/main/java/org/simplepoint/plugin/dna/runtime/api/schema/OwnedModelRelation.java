package org.simplepoint.plugin.dna.runtime.api.schema;

/** Immutable owner relation. Only aggregate operations can access a child model. */
public record OwnedModelRelation(String parentModelId, String parentFieldId) {
  /** Validates and defensively copies the immutable request or snapshot boundary. */
  public OwnedModelRelation {
    if (parentModelId == null
        || !parentModelId.matches("[A-Za-z0-9_-]{1,64}")
        || parentFieldId == null
        || !parentFieldId.matches("[A-Za-z0-9_-]{1,64}")) {
      throw new IllegalArgumentException("Owned model relation identity is invalid");
    }
  }
}
