package org.simplepoint.plugin.dna.runtime.api.model;

import java.time.Instant;

/** Public resource binding projection without physical placement. */
public record PhysicalModelResourceBindingView(
    String id,
    String physicalModelId,
    String resourceCode,
    Instant createdAt
) {
}
