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
    String id,
    String name,
    String email,
    String phoneNumber,
    String picture
) {
}
