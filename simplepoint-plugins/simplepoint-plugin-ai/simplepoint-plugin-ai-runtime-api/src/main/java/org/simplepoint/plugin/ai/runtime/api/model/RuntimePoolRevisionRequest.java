package org.simplepoint.plugin.ai.runtime.api.model;

/** Binds a Runtime Pool to one immutable MCP deployment revision. */
public record RuntimePoolRevisionRequest(
    String profileId,
    String revisionId
) {
}
