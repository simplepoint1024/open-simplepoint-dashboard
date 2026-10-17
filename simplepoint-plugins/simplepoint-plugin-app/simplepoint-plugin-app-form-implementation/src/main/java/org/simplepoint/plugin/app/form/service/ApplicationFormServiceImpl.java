package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDraftView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormVersionView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormView;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.SaveApplicationFormDraftCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationAccessPort;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.simplepoint.plugin.app.form.entity.ApplicationFormDraftEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.service.FormDocumentCodec.EncodedDocument;
import org.simplepoint.plugin.app.form.service.FormRequestContext.FormActor;
import org.simplepoint.plugin.app.form.service.FormStorageProjectionCompiler.CompiledStorageProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional form definition, draft and immutable version service. */
@Service
@Transactional(readOnly = true)
public class ApplicationFormServiceImpl implements ApplicationFormService {

  private static final int MAX_FORMS_PER_APPLICATION = 100;
  private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,62}");

  private final EntityManager entityManager;
  private final ApplicationAccessPort applicationAccess;
  private final FormRequestContext requestContext;
  private final FormDocumentValidator validator;
  private final FormDocumentCodec codec;
  private final FormStorageProjectionCompiler storageCompiler;

  /** Creates the form domain service from explicit application and document boundaries. */
  public ApplicationFormServiceImpl(
      final EntityManager entityManager,
      final ApplicationAccessPort applicationAccess,
      final FormRequestContext requestContext,
      final FormDocumentValidator validator,
      final FormDocumentCodec codec,
      final FormStorageProjectionCompiler storageCompiler) {
    this.entityManager = entityManager;
    this.applicationAccess = applicationAccess;
    this.requestContext = requestContext;
    this.validator = validator;
    this.codec = codec;
    this.storageCompiler = storageCompiler;
  }

  @Override
  @Transactional
  public ApplicationFormView create(final CreateApplicationFormCommand command) {
    FormActor actor = requestContext.requireActor();
    if (command == null) {
      throw invalid("Form creation command is required");
    }
    String applicationId = required(command.applicationId(), 36, "applicationId");
    applicationAccess.requireApplicationForUpdate(actor.tenantId(), applicationId);
    String code = required(command.code(), 63, "code");
    if (!CODE.matcher(code).matches()) {
      throw invalid("Form code must use lower-case letters, numbers, and underscores");
    }
    final String name = required(command.name(), 120, "name");
    final String description = optional(command.description(), 500, "description");
    if (formCodeExists(actor.tenantId(), applicationId, code)) {
      throw conflict();
    }
    if (formCount(actor.tenantId(), applicationId) >= MAX_FORMS_PER_APPLICATION) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.FORM_LIMIT_EXCEEDED,
          "Application cannot contain more than 100 forms");
    }

    String formId = UUID.randomUUID().toString();
    ApplicationFormDocument document = emptyDocument();
    validator.validate(document);
    final EncodedDocument encoded = codec.encode(document);
    Instant now = Instant.now();

    ApplicationFormEntity form = new ApplicationFormEntity();
    form.setId(formId);
    form.setTenantId(actor.tenantId());
    form.setApplicationId(applicationId);
    form.setCode(code);
    form.setName(name);
    form.setDescription(description);
    form.setStatus(ApplicationFormStatus.DRAFT);
    form.setDraftRevision(0);
    form.setLatestVersion(0);
    form.setCreatedBy(actor.actorId());
    form.setCreatedAt(now);
    form.setUpdatedAt(now);

    ApplicationFormDraftEntity draft = new ApplicationFormDraftEntity();
    draft.setFormId(formId);
    draft.setTenantId(actor.tenantId());
    draft.setRevision(0);
    draft.setDocumentJson(encoded.json());
    draft.setChecksum(encoded.checksum());
    draft.setUpdatedBy(actor.actorId());
    draft.setUpdatedAt(now);
    try {
      entityManager.persist(form);
      entityManager.persist(draft);
      entityManager.flush();
      return ApplicationFormViews.definition(form);
    } catch (PersistenceException exception) {
      throw persistenceFailure(exception);
    }
  }

  @Override
  public List<ApplicationFormView> list(final String applicationId) {
    FormActor actor = requestContext.requireActor();
    String appId = required(applicationId, 36, "applicationId");
    applicationAccess.requireApplication(actor.tenantId(), appId);
    return entityManager
        .createQuery(
            "from ApplicationFormEntity form where form.tenantId = :tenant "
                + "and form.applicationId = :application order by form.createdAt desc, form.id asc",
            ApplicationFormEntity.class)
        .setParameter("tenant", actor.tenantId())
        .setParameter("application", appId)
        .getResultList()
        .stream()
        .map(ApplicationFormViews::definition)
        .toList();
  }

  @Override
  public ApplicationFormView get(final String applicationId, final String formId) {
    FormActor actor = requestContext.requireActor();
    return ApplicationFormViews.definition(requireForm(actor, applicationId, formId, false));
  }

  @Override
  public ApplicationFormDraftView draft(final String applicationId, final String formId) {
    FormActor actor = requestContext.requireActor();
    ApplicationFormEntity form = requireForm(actor, applicationId, formId, false);
    return draftView(requireDraft(actor.tenantId(), form.getId(), false));
  }

  @Override
  @Transactional
  public ApplicationFormDraftView saveDraft(
      final String applicationId,
      final String formId,
      final SaveApplicationFormDraftCommand command) {
    FormActor actor = requestContext.requireActor();
    if (command == null || command.expectedRevision() < 0 || command.document() == null) {
      throw invalid("Draft command, revision, and document are required");
    }
    ApplicationFormEntity form = requireForm(actor, applicationId, formId, true);
    if (form.getStatus() == ApplicationFormStatus.ARCHIVED) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.FORM_ARCHIVED, "Archived forms cannot be changed");
    }
    ApplicationFormDraftEntity draft = requireDraft(actor.tenantId(), form.getId(), true);
    if (draft.getRevision() != command.expectedRevision()
        || form.getDraftRevision() != command.expectedRevision()) {
      throw revisionConflict();
    }
    validator.validate(command.document());
    EncodedDocument encoded = codec.encode(command.document());
    long nextRevision = command.expectedRevision() + 1;
    Instant now = Instant.now();
    draft.setRevision(nextRevision);
    draft.setDocumentJson(encoded.json());
    draft.setChecksum(encoded.checksum());
    draft.setUpdatedBy(actor.actorId());
    draft.setUpdatedAt(now);
    form.setDraftRevision(nextRevision);
    form.setUpdatedAt(now);
    entityManager.flush();
    return draftView(draft);
  }

  @Override
  @Transactional
  public ApplicationFormVersionView createVersion(
      final String applicationId, final String formId, final long expectedRevision) {
    FormActor actor = requestContext.requireActor();
    if (expectedRevision < 0) {
      throw invalid("expectedRevision cannot be negative");
    }
    ApplicationFormEntity form = requireForm(actor, applicationId, formId, true);
    if (form.getStatus() == ApplicationFormStatus.ARCHIVED) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.FORM_ARCHIVED, "Archived forms cannot create versions");
    }
    ApplicationFormDraftEntity draft = requireDraft(actor.tenantId(), form.getId(), true);
    if (draft.getRevision() != expectedRevision || form.getDraftRevision() != expectedRevision) {
      throw revisionConflict();
    }
    ApplicationFormDocument document = codec.decode(draft.getDocumentJson());
    validator.validatePublication(document);
    ApplicationFormVersionEntity latest = latestVersion(actor.tenantId(), form);
    if (latest != null) {
      FormDetailEvolutionPolicy.validate(
          codec.decode(latest.getDocumentJson()), document, storageCompiler);
    }
    if (latest != null && latest.getChecksum().equals(draft.getChecksum())) {
      return versionView(latest);
    }

    CompiledStorageProjection storage = storageCompiler.compile(document);
    ApplicationFormVersionEntity version = new ApplicationFormVersionEntity();
    version.setId(UUID.randomUUID().toString());
    version.setTenantId(actor.tenantId());
    version.setFormId(form.getId());
    version.setVersionNumber(form.getLatestVersion() + 1);
    version.setDocumentJson(draft.getDocumentJson());
    version.setChecksum(draft.getChecksum());
    version.setStorageSchemaJson(storage.json());
    version.setStorageChecksum(storage.checksum());
    version.setRendererVersion(ApplicationFormApiVersion.RENDERER_V1);
    version.setCreatedBy(actor.actorId());
    version.setCreatedAt(Instant.now());
    form.setLatestVersion(version.getVersionNumber());
    form.setUpdatedAt(version.getCreatedAt());
    entityManager.persist(version);
    entityManager.flush();
    return versionView(version);
  }

  @Override
  public List<ApplicationFormVersionView> versions(
      final String applicationId, final String formId) {
    FormActor actor = requestContext.requireActor();
    ApplicationFormEntity form = requireForm(actor, applicationId, formId, false);
    return entityManager
        .createQuery(
            "from ApplicationFormVersionEntity version where version.tenantId = :tenant "
                + "and version.formId = :form order by version.versionNumber desc",
            ApplicationFormVersionEntity.class)
        .setParameter("tenant", actor.tenantId())
        .setParameter("form", form.getId())
        .getResultList()
        .stream()
        .map(this::versionView)
        .toList();
  }

  private ApplicationFormEntity requireForm(
      final FormActor actor, final String applicationId, final String formId, final boolean lock) {
    String appId = required(applicationId, 36, "applicationId");
    String id = required(formId, 36, "formId");
    applicationAccess.requireApplication(actor.tenantId(), appId);
    var query =
        entityManager
            .createQuery(
                "from ApplicationFormEntity form where form.tenantId = :tenant "
                    + "and form.applicationId = :application and form.id = :form",
                ApplicationFormEntity.class)
            .setParameter("tenant", actor.tenantId())
            .setParameter("application", appId)
            .setParameter("form", id);
    if (lock) {
      query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
    }
    return query.getResultStream().findFirst().orElseThrow(ApplicationFormServiceImpl::notFound);
  }

  private ApplicationFormDraftEntity requireDraft(
      final String tenantId, final String formId, final boolean lock) {
    ApplicationFormDraftEntity draft =
        lock
            ? entityManager.find(
                ApplicationFormDraftEntity.class, formId, LockModeType.PESSIMISTIC_WRITE)
            : entityManager.find(ApplicationFormDraftEntity.class, formId);
    if (draft == null || !tenantId.equals(draft.getTenantId())) {
      throw notFound();
    }
    return draft;
  }

  private ApplicationFormVersionEntity latestVersion(
      final String tenantId, final ApplicationFormEntity form) {
    if (form.getLatestVersion() < 1) {
      return null;
    }
    return entityManager
        .createQuery(
            "from ApplicationFormVersionEntity version where version.tenantId = :tenant "
                + "and version.formId = :form and version.versionNumber = :version",
            ApplicationFormVersionEntity.class)
        .setParameter("tenant", tenantId)
        .setParameter("form", form.getId())
        .setParameter("version", form.getLatestVersion())
        .getResultStream()
        .findFirst()
        .orElseThrow(
            () ->
                new ApplicationFormException(
                    ApplicationFormErrorCode.PERSISTENCE_FAILED,
                    "Latest form version metadata is inconsistent"));
  }

  private boolean formCodeExists(
      final String tenantId, final String applicationId, final String code) {
    return entityManager
            .createQuery(
                "select count(form) from ApplicationFormEntity form where form.tenantId = :tenant "
                    + "and form.applicationId = :application and form.code = :code",
                Long.class)
            .setParameter("tenant", tenantId)
            .setParameter("application", applicationId)
            .setParameter("code", code)
            .getSingleResult()
        > 0;
  }

  private long formCount(final String tenantId, final String applicationId) {
    return entityManager
        .createQuery(
            "select count(form) from ApplicationFormEntity form where form.tenantId = :tenant "
                + "and form.applicationId = :application",
            Long.class)
        .setParameter("tenant", tenantId)
        .setParameter("application", applicationId)
        .getSingleResult();
  }

  private ApplicationFormDraftView draftView(final ApplicationFormDraftEntity draft) {
    return new ApplicationFormDraftView(
        draft.getFormId(),
        draft.getRevision(),
        codec.decode(draft.getDocumentJson()),
        draft.getChecksum(),
        draft.getUpdatedBy(),
        draft.getUpdatedAt());
  }

  private ApplicationFormVersionView versionView(final ApplicationFormVersionEntity version) {
    return new ApplicationFormVersionView(
        version.getId(),
        version.getFormId(),
        version.getVersionNumber(),
        codec.decode(version.getDocumentJson()),
        version.getChecksum(),
        version.getStorageChecksum(),
        version.getPhysicalModelVersion(),
        version.getRendererVersion(),
        version.getCreatedBy(),
        version.getCreatedAt());
  }

  private static ApplicationFormDocument emptyDocument() {
    return new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        List.of(),
        List.of(),
        List.of(
            new FormSceneDefinition(
                "main",
                "main",
                "主页面",
                FormSceneType.MAIN,
                null,
                new FormComponentDefinition(
                    "root",
                    "layout.form",
                    ApplicationFormApiVersion.RENDERER_V1,
                    null,
                    24,
                    null,
                    null,
                    null,
                    null,
                    false,
                    java.util.Map.of(),
                    List.of()))),
        List.of());
  }

  private static ApplicationFormException persistenceFailure(final PersistenceException cause) {
    return new ApplicationFormException(
        ApplicationFormErrorCode.PERSISTENCE_FAILED, "Form persistence failed", cause);
  }

  private static String required(final String value, final int maxLength, final String name) {
    if (value == null || value.isBlank() || value.trim().length() > maxLength) {
      throw invalid(name + " is required and exceeds its accepted length");
    }
    return value.trim();
  }

  private static String optional(final String value, final int maxLength, final String name) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw invalid(name + " exceeds its accepted length");
    }
    return normalized;
  }

  private static ApplicationFormException invalid(final String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message);
  }

  private static ApplicationFormException notFound() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.FORM_NOT_FOUND, "Form was not found in the active application");
  }

  private static ApplicationFormException conflict() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.FORM_CODE_CONFLICT, "Form code already exists in the application");
  }

  private static ApplicationFormException revisionConflict() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.DRAFT_REVISION_CONFLICT,
        "Form draft revision does not match expectedRevision");
  }
}
