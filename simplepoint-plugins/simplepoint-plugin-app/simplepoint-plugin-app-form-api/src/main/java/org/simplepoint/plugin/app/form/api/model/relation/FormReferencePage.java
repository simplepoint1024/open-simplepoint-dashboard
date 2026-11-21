package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.List;

/** Zero-based, server-filtered reference directory. */
public record FormReferencePage(int page, int size, long total, List<FormReferenceOption> records) {
  public FormReferencePage {
    records = List.copyOf(records);
  }
}
