package org.simplepoint.plugin.rbac.core.api.pojo.vo;

/** Minimal selection projection: value is a user ID or a tenant role authority. */
public record TenantDirectoryItem(String id, String name, String value) {
}
