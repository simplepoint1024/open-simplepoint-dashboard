package org.simplepoint.plugin.rbac.core.api.pojo.command;

/** The selected organization and membership version prevent removing a relocated member. */
public record OrganizationMemberRemoval(String orgId, Long revision) {}
