package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.List;

/** A bounded lookup directory; authorization participates in both its count and page query. */
public record FormRelationTargetPage(
    int page, int size, long total, List<FormRelationTargetOption> records) {
  public FormRelationTargetPage {
    records = List.copyOf(records);
  }
}
