package org.simplepoint.plugin.app.form.api.service;

import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormCatalogPage;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDesignerContract;

/** Authorized platform table and design contracts for an application's form directory. */
public interface ApplicationFormCatalogService {

  /** Returns bounded, filtered and sorted form metadata. */
  ApplicationFormCatalogPage page(String applicationId, Map<String, String> filters,
      int page, int size, List<String> sorts);

  /** Returns the permission-filtered SimpleTable contract. */
  Map<String, Object> schema(String applicationId);

  /** Returns the trusted component palette and effective design capabilities. */
  ApplicationFormDesignerContract designer(String applicationId);
}
