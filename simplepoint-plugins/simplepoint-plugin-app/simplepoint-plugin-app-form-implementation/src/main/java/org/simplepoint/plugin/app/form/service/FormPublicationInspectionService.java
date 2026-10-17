package org.simplepoint.plugin.app.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationInspection;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationInspection.Issue;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationInspection.Kind;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormWritePolicyPublicationEntity;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Describes the persisted inspection using metadata only; never retrieves business records. */
@Service
public class FormPublicationInspectionService {
  private final EntityManager entityManager;
  private final FormRequestContext context;
  private final FormDocumentCodec documents;
  private final ObjectMapper mapper;

  /** Uses saved policy commands and immutable form metadata for publication feedback. */
  public FormPublicationInspectionService(final EntityManager entityManager, final FormRequestContext context,
      final FormDocumentCodec documents, final ObjectMapper mapper) {
    this.entityManager = entityManager;
    this.context = context;
    this.documents = documents;
    this.mapper = mapper;
  }

  /** Caller must hold publication authority and verify the owning application/form before requesting this projection. */
  @Transactional(readOnly = true)
  public ApplicationFormPublicationInspection get(final String applicationId, final String formId, final String releaseId) {
    String tenant = context.requireActor().tenantId();
    var intent = entityManager.createQuery("from ApplicationFormWritePolicyPublicationEntity p where p.tenantId = :tenant "
            + "and p.applicationId = :app and p.formId = :form and p.bindingId = :binding", ApplicationFormWritePolicyPublicationEntity.class)
        .setParameter("tenant", tenant).setParameter("app", applicationId).setParameter("form", formId)
        .setParameter("binding", releaseId).getResultStream().findFirst().orElse(null);
    if (intent == null || intent.getStatus() != FormWritePolicyPublicationStatus.PREPARED || intent.getInspectionJson() == null) {
      return null;
    }
    var command = decode(intent.getCommandJson(), RecordWritePolicyPublicationCommand.class);
    var result = decode(intent.getInspectionJson(), RecordWritePolicyPublicationView.class);
    var rows = entityManager.createQuery("select v.documentJson from ApplicationFormVersionEntity v "
            + "where v.tenantId = :tenant and v.formId = :form and v.versionNumber = :version", String.class)
        .setParameter("tenant", tenant).setParameter("form", formId).setParameter("version", intent.getGoverningVersion()).getResultList();
    if (rows.size() != 1) {
      throw invalid();
    }
    Map<String, String> labels = new HashMap<>();
    documents.decode(rows.getFirst()).fields().forEach(field -> labels.put(field.storageCode(), field.label()));
    var issues = new ArrayList<Issue>();
    for (var entry : new java.util.TreeMap<>(result.violations()).entrySet()) {
      var check = command.checks().get(entry.getKey());
      if (check == null || !check.fields().containsAll(entry.getValue().emptyCounts().keySet())) {
        throw invalid();
      }
      for (var field : new java.util.TreeMap<>(entry.getValue().emptyCounts()).entrySet()) {
        if (field.getValue() > 0) {
          issues.add(new Issue(entry.getKey() + ":" + issues.size(), Kind.REQUIRED,
              java.util.List.of(labels.getOrDefault(field.getKey(), field.getKey())), field.getValue().toString()));
        }
      }
    }
    for (var entry : new java.util.TreeMap<>(result.valueViolations()).entrySet()) {
      var check = command.valueChecks().get(entry.getKey());
      if (check == null) {
        throw invalid();
      }
      var fields = new TreeSet<String>();
      fields(check.condition(), fields);
      issues.add(new Issue(entry.getKey(), Kind.INVALID_VALUE,
          fields.stream().map(field -> labels.getOrDefault(field, field)).toList(), entry.getValue().toString()));
    }
    return new ApplicationFormPublicationInspection(intent.getUpdatedAt(), issues, repairPage(tenant, applicationId, formId));
  }

  private String repairPage(final String tenant, final String appId, final String formId) {
    var actor = AuthorizationContextHolder.getContext();
    var pages = entityManager.createQuery("from ApplicationFormPageBindingEntity b where b.tenantId = :tenant "
            + "and b.applicationId = :app and b.formId = :form and b.status = :status order by b.id", ApplicationFormPageBindingEntity.class)
        .setParameter("tenant", tenant).setParameter("app", appId).setParameter("form", formId)
        .setParameter("status", ApplicationFormPageBindingStatus.ACTIVE).setMaxResults(1000).getResultList();
    return pages.stream().filter(page -> page.getResourceCode() != null && (Boolean.TRUE.equals(actor.getIsAdministrator())
        || actor.getResources() != null && actor.getResources().contains(page.getResourceCode())
        && actor.getResources().contains(page.getResourceCode() + ".records.edit")))
        .map(ApplicationFormPageBindingEntity::getPageId).findFirst().orElse(null);
  }

  private static void fields(final RecordValueCondition condition, final TreeSet<String> fields) {
    if (condition.field() != null) {
      fields.add(condition.field());
    }
    condition.children().forEach(child -> fields(child, fields));
  }

  private <T> T decode(final String json, final Class<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (JsonProcessingException failure) {
      throw new ApplicationFormException(ApplicationFormErrorCode.PERSISTENCE_FAILED, "The saved publication inspection is invalid", failure);
    }
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(ApplicationFormErrorCode.PERSISTENCE_FAILED, "The saved inspection does not match its publication");
  }
}
