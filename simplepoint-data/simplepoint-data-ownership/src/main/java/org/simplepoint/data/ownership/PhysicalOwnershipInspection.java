package org.simplepoint.data.ownership;

import java.util.Objects;
import java.util.UUID;

/** Directory installation evidence only; it does not certify legacy claims or activation. */
public record PhysicalOwnershipInspection(
    PhysicalNamespaceDescriptor target, UUID namespaceId, boolean complete) {
  /** Missing identity means absent; identity without completion is a resumable MySQL header. */
  public PhysicalOwnershipInspection {
    Objects.requireNonNull(target, "target");
    if (complete && namespaceId == null) {
      throw new IllegalArgumentException("A completed directory requires its namespace identity");
    }
  }
}
