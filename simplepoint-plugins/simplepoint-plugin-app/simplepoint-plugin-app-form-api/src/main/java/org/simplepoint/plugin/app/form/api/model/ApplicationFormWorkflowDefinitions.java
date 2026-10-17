package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** Page of eligible latest workflow versions for one published form. */
public record ApplicationFormWorkflowDefinitions(
    List<Definition> content, int page, int size, long totalElements) {
  /** Published process identity and display metadata for the bound-form selector. */
  public record Definition(String id, String name, int version, String description) {}
}
