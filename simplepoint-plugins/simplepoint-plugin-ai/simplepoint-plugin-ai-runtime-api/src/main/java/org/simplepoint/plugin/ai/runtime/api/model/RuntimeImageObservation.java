package org.simplepoint.plugin.ai.runtime.api.model;

import java.util.List;
import java.util.Map;

/**
 * Supply-chain and MCP metadata observed after a runtime node prepares an OCI
 * image.
 */
public record RuntimeImageObservation(
    String reference,
    String imageId,
    List<String> repoDigests,
    Map<String, String> labels,
    String mcpTransport,
    String protocolVersion,
    boolean supplyChainAdmitted,
    boolean signatureVerified,
    boolean sbomVerified,
    String admissionPolicyHash
) {
}
