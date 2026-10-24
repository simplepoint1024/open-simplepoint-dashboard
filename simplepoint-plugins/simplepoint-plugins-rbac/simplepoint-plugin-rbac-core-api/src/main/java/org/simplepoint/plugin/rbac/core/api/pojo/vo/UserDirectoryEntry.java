package org.simplepoint.plugin.rbac.core.api.pojo.vo;

/**
 * Minimal account projection for trusted service directory operations; never carries credentials.
 */
public record UserDirectoryEntry(
    String id, String name, String email, String phoneNumber, boolean enabled) {}
