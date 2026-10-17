package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Captures every other active page's requirements under the form's admission generation. */
@Service
public class FormRecordInvariantService {
  private final EntityManager entityManager;
  private final FormDocumentCodec codec;
  private final FormRequestContext context;

  /** Metadata reads share the publication form lock; no remote call occurs inside this transaction. */
  public FormRecordInvariantService(final EntityManager entityManager, final FormDocumentCodec codec, final FormRequestContext context) {
    this.entityManager = entityManager;
    this.codec = codec;
    this.context = context;
  }

  /** The target write fence rejects this plan if publication advances after the metadata transaction ends. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Plan resolve(final PublishedApplicationForm runtime) {
    if (!context.requireActor().tenantId().equals(runtime.tenantId())) {
      throw conflict();
    }
    var form = entityManager.createQuery("from ApplicationFormEntity f where f.tenantId = :tenant "
            + "and f.applicationId = :app and f.id = :form", ApplicationFormEntity.class)
        .setParameter("tenant", runtime.tenantId()).setParameter("app", runtime.applicationId()).setParameter("form", runtime.formId())
        .setLockMode(LockModeType.PESSIMISTIC_READ).getResultStream().findFirst().orElseThrow(FormRecordInvariantService::conflict);
    if (form.getWritePolicyVersion() != runtime.writeAdmissionVersion() || form.getPublishedVersion() == null
        || form.getPublishedVersion() != runtime.writePolicyVersion()) {
      throw conflict();
    }
    var versions = entityManager.createQuery("select distinct b.formVersion from ApplicationFormPageBindingEntity b "
            + "where b.tenantId = :tenant and b.formId = :form and b.status = :active", Integer.class)
        .setParameter("tenant", runtime.tenantId()).setParameter("form", runtime.formId())
        .setParameter("active", ApplicationFormPageBindingStatus.ACTIVE).setMaxResults(1001).getResultList();
    if (versions.size() > 1000 || !versions.contains(runtime.formVersion())) {
      throw conflict();
    }
    var budget = new FormActiveDefinitionBudget();
    var governing = version(runtime, runtime.writePolicyVersion(), budget);
    var document = codec.decode(governing.getDocumentJson());
    var other = new ArrayList<ApplicationFormDocument>();
    for (int number : new TreeSet<>(versions)) {
      if (number != runtime.formVersion()) {
        other.add(FormWritePolicyProjection.apply(codec.decode(version(runtime, number, budget).getDocumentJson()), document));
      }
    }
    return new Plan(document, governing.getPhysicalModelVersion(), governing.getStorageChecksum(), other);
  }

  private ApplicationFormVersionEntity version(final PublishedApplicationForm runtime, final int number, final FormActiveDefinitionBudget budget) {
    var version = entityManager.createQuery("from ApplicationFormVersionEntity v where v.tenantId = :tenant "
            + "and v.formId = :form and v.versionNumber = :version", ApplicationFormVersionEntity.class)
        .setParameter("tenant", runtime.tenantId()).setParameter("form", runtime.formId()).setParameter("version", number)
        .getResultStream().findFirst().orElseThrow(FormRecordInvariantService::conflict);
    budget.include(number, version.getDocumentJson());
    if (version.getPhysicalModelVersion() == null || version.getStorageChecksum() == null) {
      throw conflict();
    }
    return version;
  }

  private static ApplicationFormException conflict() {
    return new ApplicationFormException(ApplicationFormErrorCode.RUNTIME_VERSION_CONFLICT, "The active form requirements changed; reload the page");
  }

  /** Detached plan used with a version-checked record and the original mutation admission generation. */
  public record Plan(ApplicationFormDocument governing, int physicalVersion, String storageChecksum, List<ApplicationFormDocument> documents) {
    /** Keeps the captured page set immutable outside the metadata transaction. */
    public Plan {
      documents = List.copyOf(documents);
    }

    /** Uses the activated storage defaults; explicit nulls must never acquire historical page defaults. */
    public Map<String, Object> createDefaults() {
      Map<String, Object> values = new LinkedHashMap<>();
      governing.fields().stream().filter(field -> field.storageMode() == FormStorageMode.STORED)
          .forEach(field -> values.put(field.fieldId(), FormRecordPayloadCompiler.value(field.defaultValue())));
      return values;
    }

    /** Historical transient inputs cannot change persisted invariants on another page. */
    public void validate(final FormRecordPayloadCompiler compiler, final Map<String, Object> existing,
        final Map<String, Object> requested, final FormRecordState current, final FormRecordState target) {
      for (var document : documents) {
        Set<String> persistent = document.fields().stream().filter(field -> field.storageMode() == FormStorageMode.STORED)
            .map(field -> field.fieldId()).collect(Collectors.toSet());
        Map<String, Object> changes = new LinkedHashMap<>();
        requested.forEach((field, value) -> {
          if (persistent.contains(field)) {
            changes.put(field, value);
          }
        });
        compiler.compile(document, existing, changes, current, target);
      }
    }
  }
}
