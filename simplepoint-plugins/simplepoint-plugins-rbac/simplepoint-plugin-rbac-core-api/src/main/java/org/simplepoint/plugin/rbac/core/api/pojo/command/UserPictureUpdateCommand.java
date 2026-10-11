package org.simplepoint.plugin.rbac.core.api.pojo.command;

/** A dedicated field-only command. Account IDs and other profile fields are never writable here. */
public record UserPictureUpdateCommand(String picture) {}
