package org.simplepoint.plugin.rbac.tenant.api.vo;

/**
 * Simplified user view for owner and member selection.
 *
 * @param id user identifier
 * @param name display name
 * @param email email address
 * @param phoneNumber phone number
 */
public record UserRelevanceVo(String id, String name, String email, String phoneNumber) {
}
