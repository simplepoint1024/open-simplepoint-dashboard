package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.FormRecordReferenceLabel;
import org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory;
import org.simplepoint.plugin.rbac.tenant.api.vo.AuditIdentityRequest;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adds response-only names after record/field authorization; never changes storage or snapshots.
 */
@Component
public class FormRecordIdentityLabels {
  private static final Set<String> FIELDS =
      Set.of("tenantId", "createdBy", "updatedBy", "createOrgDeptId");
  private final AuditIdentityDirectory directory;

  /** Uses the platform's narrow service contract, not management endpoints or foreign tables. */
  public FormRecordIdentityLabels(final AuditIdentityDirectory directory) {
    this.directory = directory;
  }

  ApplicationFormRecordView decorate(final String tenant, final ApplicationFormRecordView record) {
    return decorate(tenant, List.of(record)).getFirst();
  }

  List<ApplicationFormRecordView> decorate(
      final String tenant, final List<ApplicationFormRecordView> records) {
    Set<Reference> references = new LinkedHashSet<>();
    boolean tenantVisible = false;
    for (var record : records) {
      for (var field : FIELDS) {
        String id = identity(record.systemValues().get(field));
        if (id == null) {
          continue;
        }
        if (field.equals("tenantId")) {
          tenantVisible |= tenant.equals(id);
        } else {
          references.add(new Reference(field.equals("createOrgDeptId"), id));
        }
      }
    }
    Map<Reference, String> names = new LinkedHashMap<>();
    String tenantName = null;
    var ordered = new ArrayList<>(references);
    for (int offset = 0; offset < ordered.size() || offset == 0 && tenantVisible; offset += 100) {
      Set<String> users = new LinkedHashSet<>();
      Set<String> departments = new LinkedHashSet<>();
      var batch = ordered.subList(offset, Math.min(offset + 100, ordered.size()));
      batch.forEach(
          reference -> (reference.department() ? departments : users).add(reference.id()));
      try {
        var result =
            directory.resolve(
                new AuditIdentityRequest(tenant, tenantVisible && offset == 0, users, departments));
        if (result == null || !tenant.equals(result.tenantId())) {
          throw new IllegalStateException("Audit label response scope mismatch");
        }
        if (offset == 0 && tenantVisible) {
          tenantName = label(result.tenantName());
        }
        for (var reference : batch) {
          names.put(
              reference,
              label(
                  (reference.department() ? result.departments() : result.users())
                      .get(reference.id())));
        }
      } catch (RuntimeException failure) {
        // Names are optional presentation metadata. Existing authorized records remain available.
        LoggerFactory.getLogger(FormRecordIdentityLabels.class)
            .warn("Audit identity labels unavailable ({})", failure.getClass().getSimpleName());
      }
    }
    List<ApplicationFormRecordView> result = new ArrayList<>();
    for (var record : records) {
      Map<String, FormRecordReferenceLabel> labels = new LinkedHashMap<>();
      for (var field : FIELDS) {
        String id = identity(record.systemValues().get(field));
        if (id != null) {
          String name =
              field.equals("tenantId")
                  ? tenant.equals(id) ? tenantName : null
                  : names.get(new Reference(field.equals("createOrgDeptId"), id));
          labels.put(field, new FormRecordReferenceLabel(id, name));
        }
      }
      result.add(
          new ApplicationFormRecordView(
              record.id(),
              record.version(),
              record.state(),
              record.values(),
              record.systemValues(),
              labels,
              record.referenceSnapshots()));
    }
    return List.copyOf(result);
  }

  private static String identity(final Object value) {
    return value instanceof String text
            && !text.isBlank()
            && text.length() <= 64
            && text.equals(text.strip())
            && !"***".equals(text)
        ? text
        : null;
  }

  private static String label(final String value) {
    return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= 256
        ? value
        : null;
  }

  private record Reference(boolean department, String id) {}
}
