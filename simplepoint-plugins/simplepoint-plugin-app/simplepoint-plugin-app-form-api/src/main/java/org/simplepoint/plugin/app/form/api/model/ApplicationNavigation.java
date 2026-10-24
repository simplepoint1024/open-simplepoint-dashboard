package org.simplepoint.plugin.app.form.api.model;

import java.util.List;
import org.simplepoint.api.locale.LocalizedText;

/** Authored tenant navigation. IDs remain stable independently from labels and placement. */
public record ApplicationNavigation(
    String tenantParentCode, LocalizedText name, List<Directory> directories, List<Page> pages) {
  public ApplicationNavigation {
    directories = directories == null ? List.of() : List.copyOf(directories);
    pages = pages == null ? List.of() : List.copyOf(pages);
  }

  public record Directory(String id, String parentId, LocalizedText name, String icon) {}

  public record Page(String id, String directoryId, LocalizedText name) {}
}
