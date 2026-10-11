package org.simplepoint.plugin.ai.core.api.vo;

import org.simplepoint.core.annotation.PermissionResource;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;

/** Scalar selected-value projection: never load or expose provider credentials/configuration. */
@PermissionResource(AiProviderDefinition.class)
public record AiProviderOption(String id, String name, String code, Boolean enabled) {
}
