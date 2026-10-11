package org.simplepoint.plugin.rbac.core.api.pojo.vo;

/**
 * Minimal user projection used by remote user-picker fields.
 *
 * @param id user identifier persisted by the form
 * @param name human-readable display name
 * @param email email address used for searching and disambiguation
 * @param phoneNumber phone number used for searching and disambiguation
 * @param picture OSS avatar URL
 */
public record UserPickerItem(
    @org.simplepoint.core.annotation.PermissionFields(resource = org.simplepoint.security.entity.User.class, value = "id")
    String id,
    String name,
    @org.simplepoint.core.annotation.PermissionFields(resource = org.simplepoint.security.entity.User.class, value = "email")
    String email,
    @org.simplepoint.core.annotation.PermissionFields(resource = org.simplepoint.security.entity.User.class, value = "phoneNumber")
    String phoneNumber,
    @org.simplepoint.core.annotation.PermissionFields(resource = org.simplepoint.security.entity.User.class, value = "picture")
    String picture
) {
}
