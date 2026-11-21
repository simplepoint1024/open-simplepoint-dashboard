package org.simplepoint.plugin.app.form.api.service;

import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.relation.FormCollectionOperation;
import org.simplepoint.plugin.app.form.api.model.relation.FormCollectionView;

public interface ApplicationFormCollectionService {
  List<FormCollectionView> views(
      String applicationId, String pageId, ApplicationFormRuntimeIdentity runtime, String recordId);

  Map<String, Object> schema(String applicationId, String pageId, Scope scope, boolean candidates);

  ApplicationFormRecordPageView page(
      String applicationId,
      String pageId,
      Scope scope,
      boolean candidates,
      ApplicationFormRecordPageQuery query);

  FormCollectionOperation.Observation observe(
      String applicationId,
      String pageId,
      Scope scope,
      FormCollectionOperation.Selection selection);

  void execute(
      String applicationId, String pageId, Scope scope, FormCollectionOperation.Command command);

  record Scope(
      ApplicationFormRuntimeIdentity runtime, String recordId, String viewId, boolean inverse) {}
}
