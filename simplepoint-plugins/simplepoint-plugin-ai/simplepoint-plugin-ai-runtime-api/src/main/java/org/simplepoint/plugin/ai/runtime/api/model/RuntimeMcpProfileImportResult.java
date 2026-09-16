package org.simplepoint.plugin.ai.runtime.api.model;

import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpDescriptor;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpProfile;

/** Descriptor and draft profile created by one atomic Catalog import. */
public record RuntimeMcpProfileImportResult(
    AiRuntimeMcpDescriptor descriptor,
    AiRuntimeMcpProfile profile
) {
}
