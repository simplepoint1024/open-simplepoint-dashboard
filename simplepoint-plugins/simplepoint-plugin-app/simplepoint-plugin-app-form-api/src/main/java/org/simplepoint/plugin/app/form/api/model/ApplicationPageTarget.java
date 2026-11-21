package org.simplepoint.plugin.app.form.api.model;

/** APP-owned page metadata required to publish a form runtime resource. */
public record ApplicationPageTarget(
    String pageId,
    String name,
    String description,
    int sortOrder,
    String navigationGroupName,
    PageResourcePresentation presentation,
    long publicationGeneration) {
  public ApplicationPageTarget(String pageId, String name, String description, int sortOrder,
      String navigationGroupName, PageResourcePresentation presentation) {
    this(pageId, name, description, sortOrder, navigationGroupName, presentation, 0);
  }
  public ApplicationPageTarget(
      String pageId, String name, String description, int sortOrder, String navigationGroupName) {
    this(pageId, name, description, sortOrder, navigationGroupName, null);
  }

  /** Existing applications retain their original platform parent. */
  public ApplicationPageTarget(String pageId, String name, String description, int sortOrder) {
    this(pageId, name, description, sortOrder, null);
  }
}
