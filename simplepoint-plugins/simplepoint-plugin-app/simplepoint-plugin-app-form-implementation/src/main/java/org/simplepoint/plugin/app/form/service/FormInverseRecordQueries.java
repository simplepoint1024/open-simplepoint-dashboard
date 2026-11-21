package org.simplepoint.plugin.app.form.service;

import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.service.FormInverseRelationResolver.ResolvedView;
import org.springframework.stereotype.Component;

/** Relation-owned read adapter over the standard record controller; never a second CRUD engine. */
@Component
public class FormInverseRecordQueries {
  private final ApplicationFormRecordServiceImpl records;

  /**
   * Delegates query compilation, data scope, paging and display decoration to the record domain.
   */
  public FormInverseRecordQueries(ApplicationFormRecordServiceImpl records) {
    this.records = records;
  }

  ApplicationFormRecordPageView page(ResolvedView view, ApplicationFormRecordPageQuery query) {
    return records.pageRelated(
        view.source(),
        query,
        view.relation().relationId(),
        view.parent().physicalModelId(),
        view.parentRecordId());
  }

  ApplicationFormRecordView get(ResolvedView view, String recordId) {
    var record = records.getDecorated(view.source(), recordId);
    requireMember(view, record);
    return record;
  }

  static void requireMember(ResolvedView view, ApplicationFormRecordView record) {
    boolean matches = false;
    if (record != null) {
      if (view.relation().sourceField().detailId() == null) {
        matches =
            Objects.equals(
                view.parentRecordId(), record.values().get(view.sourceField().fieldId()));
      } else {
        var rows = record.values().get(view.relation().sourceField().detailId());
        matches =
            FormDetailRecordCoordinator.parseRows(rows == null ? List.of() : rows).stream()
                .anyMatch(
                    row ->
                        Objects.equals(
                            view.parentRecordId(), row.values().get(view.sourceField().fieldId())));
      }
    }
    if (!matches) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_NOT_FOUND, "Related record is unavailable");
    }
  }
}
