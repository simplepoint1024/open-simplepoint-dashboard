package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * Write-only Runtime secret rotation request.
 *
 * @param value replacement plaintext value
 */
public record RuntimeSecretRotateRequest(String value) {
}
