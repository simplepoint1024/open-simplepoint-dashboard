package org.simplepoint.plugin.oidc.api.model;

/** Immutable local reference; the binding ID fences an unlink followed by a new binding. */
public record ExternalIdentityBinding(
    String id, String providerId, String subject, String userId) {}
