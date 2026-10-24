package org.simplepoint.plugin.app.form.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;

/**
 * Intersects derived-field reads with every transitive source, without evaluating business data.
 */
final class FormCalculationPermissions {
  private FormCalculationPermissions() {}

  static Map<String, String> restrict(
      PublishedApplicationForm form, Map<String, String> permissions) {
    var calculationDocument = form.calculationDocument() == null ? form.document() : form.calculationDocument();
    if (!FormCalculationCompiler.hasCalculations(calculationDocument)) {
      return permissions;
    }
    var compiled = new FormCalculationCompiler(Clock.systemUTC()).compile(calculationDocument);
    Map<String, String> models = new LinkedHashMap<>();
    models.put("parent", form.physicalModelId());
    for (var detail : form.detailBindings()) {
      if (models.putIfAbsent(detail.detailId(), detail.physicalModelId()) != null) {
        throw unavailable();
      }
    }
    Map<String, String> result = new LinkedHashMap<>(permissions);
    compiled
        .dependencies()
        .forEach(
            (target, sources) -> {
              var field = compiled.fields().get(target);
              String model = model(models, target);
              String own = access(permissions, model, field);
              // Even a masked source can leak through comparisons, totals or constant-looking IF
              // branches.
              boolean protectedSource =
                  sources.stream()
                      .anyMatch(
                          source -> {
                            String policy =
                                access(
                                    permissions,
                                    model(models, source),
                                    compiled.fields().get(source));
                            return "HIDDEN".equals(policy) || "MASKED".equals(policy);
                          });
              String effective =
                  protectedSource || "HIDDEN".equals(own)
                      ? "HIDDEN"
                      : "MASKED".equals(own) ? "MASKED" : "VISIBLE";
              result.put(
                  RecordPermissionModel.key(model)
                      + "#"
                      + RecordPermissionModel.field(field.fieldId()),
                  effective);
            });
    return Map.copyOf(result);
  }

  private static String access(
      Map<String, String> permissions, String model, FormFieldDefinition field) {
    if (field == null) {
      throw unavailable();
    }
    return RecordPermissionModel.access(permissions, model, field.fieldId(), field.storageCode());
  }

  private static String model(Map<String, String> models, String identity) {
    String model = models.get(identity.substring(0, identity.indexOf('.')));
    if (model == null) {
      throw unavailable();
    }
    return model;
  }

  private static ApplicationFormException unavailable() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.PAGE_NOT_PUBLISHED, "Published calculation scope is unavailable");
  }
}
