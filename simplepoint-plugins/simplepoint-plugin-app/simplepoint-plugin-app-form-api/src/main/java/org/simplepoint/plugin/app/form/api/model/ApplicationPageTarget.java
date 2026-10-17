package org.simplepoint.plugin.app.form.api.model;

/** APP-owned page metadata required to publish a form runtime resource. */
public record ApplicationPageTarget(
    String pageId,
    String name,
    String description,
    int sortOrder,
    String navigationGroupName
) {
  /** Existing applications retain their original platform parent. */
  public ApplicationPageTarget(String pageId, String name, String description, int sortOrder) {
    this(pageId, name, description, sortOrder, null);
  }
}
