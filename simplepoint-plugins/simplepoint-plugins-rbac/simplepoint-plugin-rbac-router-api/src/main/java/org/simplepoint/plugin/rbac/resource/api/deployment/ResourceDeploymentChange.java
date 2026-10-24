package org.simplepoint.plugin.rbac.resource.api.deployment;

import java.util.UUID;

/** Installer-owned compare-and-set command; null clientId retires a registration writer. */
public record ResourceDeploymentChange(
    String owner, long expectedGeneration, UUID operationId, String clientId) {}
