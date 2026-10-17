package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** Platform table page without transport-library dependencies in the form API. */
public record ApplicationFormCatalogPage(List<ApplicationFormView> content, Metadata page) {

  /** Retains immutable page content. */
  public ApplicationFormCatalogPage {
    content = List.copyOf(content);
  }

  /** Standard platform pagination metadata. */
  public record Metadata(int number, int size, long totalElements, int totalPages) {
  }
}
