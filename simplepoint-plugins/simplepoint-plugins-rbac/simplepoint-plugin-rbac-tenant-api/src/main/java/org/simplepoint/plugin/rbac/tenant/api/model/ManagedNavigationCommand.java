package org.simplepoint.plugin.rbac.tenant.api.model;

import java.util.List;
import org.simplepoint.api.locale.LocalizedText;

/** Complete, version-fenced navigation metadata. Never changes resource lifecycle or grants. */
public record ManagedNavigationCommand(
    String tenantId,
    String rootKey,
    long revision,
    String tenantParentCode,
    List<Directory> directories,
    List<Page> pages) {
  public ManagedNavigationCommand {
    directories = directories == null ? List.of() : List.copyOf(directories);
    pages = pages == null ? List.of() : List.copyOf(pages);
  }

  public record Directory(
      String managedKey,
      String resourceCode,
      String parentKey,
      String name,
      LocalizedText nameLocalization,
      String icon,
      int sort) {}

  public record Page(
      String managedKey, String parentKey, String name, LocalizedText nameLocalization) {}
}
