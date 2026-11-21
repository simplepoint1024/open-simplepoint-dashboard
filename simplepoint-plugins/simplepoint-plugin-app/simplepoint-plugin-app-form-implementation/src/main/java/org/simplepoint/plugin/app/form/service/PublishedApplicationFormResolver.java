package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities;
import org.simplepoint.plugin.app.form.api.model.ApplicationPageBindingTarget;
import org.simplepoint.plugin.app.form.api.service.ApplicationPageAccessPort;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Resolves one active page to a detached immutable form and DNA schema identity. */
@Component
public class PublishedApplicationFormResolver {

  private final EntityManager entityManager;
  private final FormRequestContext requestContext;
  private final ApplicationPageAccessPort pageAccess;
  private final FormDocumentCodec codec;

  /** Creates the resolver over APP catalog and immutable form storage. */
  public PublishedApplicationFormResolver(
      final EntityManager entityManager,
      final FormRequestContext requestContext,
      final ApplicationPageAccessPort pageAccess,
      final FormDocumentCodec codec) {
    this.entityManager = entityManager;
    this.requestContext = requestContext;
    this.pageAccess = pageAccess;
    this.codec = codec;
  }

  /** Resolves an active page after checking its dynamic page resource. */
  @Transactional(readOnly = true)
  public PublishedApplicationForm resolve(final String applicationId, final String pageId) {
    return resolveVersion(applicationId, pageId, null, null);
  }

  /**
   * Historical resubmission retains its form version while applying current governing write policy.
   */
  @Transactional(readOnly = true)
  public PublishedApplicationForm resolveVersion(
      String applicationId, String pageId, String expectedFormId, Integer requestedVersion) {
    String appId = required(applicationId, "applicationId");
    String targetPageId = required(pageId, "pageId");
    FormRequestContext.FormActor actor = requestContext.requireActor();
    ApplicationPageBindingTarget binding =
        pageAccess.requireActiveBinding(actor.tenantId(), appId, targetPageId);
    requireResource(binding.resourceCode());
    if (expectedFormId != null && !expectedFormId.equals(binding.formId())) {
      throw failure(
          ApplicationFormErrorCode.PAGE_BINDING_CONFLICT,
          "The source page now belongs to another form");
    }
    int selectedVersion = requestedVersion == null ? binding.formVersion() : requestedVersion;
    if (selectedVersion < 1) {
      throw failure(ApplicationFormErrorCode.VERSION_NOT_FOUND, "Invalid form version");
    }
    ApplicationFormEntity form =
        entityManager
            .createQuery(
                "from ApplicationFormEntity form where form.tenantId = :tenant "
                    + "and form.applicationId = :application and form.id = :form",
                ApplicationFormEntity.class)
            .setParameter("tenant", actor.tenantId())
            .setParameter("application", appId)
            .setParameter("form", binding.formId())
            .getResultStream()
            .findFirst()
            .orElseThrow(
                () ->
                    failure(
                        ApplicationFormErrorCode.FORM_NOT_FOUND,
                        "The page's published form is unavailable"));
    ApplicationFormVersionEntity version =
        entityManager
            .createQuery(
                "from ApplicationFormVersionEntity version where version.tenantId = :tenant "
                    + "and version.formId = :form and version.versionNumber = :version",
                ApplicationFormVersionEntity.class)
            .setParameter("tenant", actor.tenantId())
            .setParameter("form", binding.formId())
            .setParameter("version", selectedVersion)
            .getResultStream()
            .findFirst()
            .orElseThrow(
                () ->
                    failure(
                        ApplicationFormErrorCode.VERSION_NOT_FOUND,
                        "The page's published form version is unavailable"));
    requireDnaBinding(form, version);
    Integer writePolicyVersion = form.getPublishedVersion();
    if (writePolicyVersion == null || writePolicyVersion < version.getVersionNumber()) {
      throw failure(
          ApplicationFormErrorCode.VERSION_NOT_FOUND,
          "The activated form write policy is unavailable");
    }
    ApplicationFormDocument document = codec.decode(version.getDocumentJson());
    List<FormRelationBinding> relationBindings = codec.decodeRelationBindings(document, version.getRelationBindingsJson());
    if (!FormRelationConversion.retiredFields(document).isEmpty()) {
      document = FormWritePolicyProjection.apply(document, document);
    }
    ApplicationFormDocument calculationDocument = null;
    List<FormDetailBinding> detailBindings =
        codec.decodeDetailBindings(version.getDetailBindingsJson());
    if (writePolicyVersion != version.getVersionNumber()) {
      ApplicationFormVersionEntity governing =
          entityManager
              .createQuery(
                  "from ApplicationFormVersionEntity version where version.tenantId = :tenant "
                      + "and version.formId = :form and version.versionNumber = :version",
                  ApplicationFormVersionEntity.class)
              .setParameter("tenant", actor.tenantId())
              .setParameter("form", form.getId())
              .setParameter("version", writePolicyVersion)
              .getResultStream()
              .findFirst()
              .orElseThrow(
                  () ->
                      failure(
                          ApplicationFormErrorCode.VERSION_NOT_FOUND,
                          "The activated form write policy is unavailable"));
      requireDnaBinding(form, governing);
      var governingDocument = codec.decode(governing.getDocumentJson());
      relationBindings = codec.decodeRelationBindings(governingDocument, governing.getRelationBindingsJson());
      document = FormWritePolicyProjection.apply(document, governingDocument);
      if (FormCalculationCompiler.hasCalculations(governingDocument)) {
        calculationDocument = governingDocument;
      }
      detailBindings = codec.decodeDetailBindings(governing.getDetailBindingsJson());
    }
    return new PublishedApplicationForm(
        actor.tenantId(),
        appId,
        binding.pageId(),
        binding.pageName(),
        form.getId(),
        form.getName(),
        version.getVersionNumber(),
        binding.resourceCode(),
        form.getPhysicalModelId(),
        version.getPhysicalModelVersion(),
        version.getStorageChecksum(),
        document,
        writePolicyVersion,
        form.getWritePolicyVersion(),
        detailBindings, calculationDocument, relationBindings);
  }

  /**
   * History reads retain the original page/field policy without resolving its newer active form
   * version.
   */
  public void requireRead(final PublishedApplicationForm form) {
    var actor = requestContext.requireActor();
    if (!actor.tenantId().equals(form.tenantId())) {
      throw failure(
          ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
          "Form snapshot belongs to another tenant");
    }
    requireResource(form.resourceCode());
  }

  /** Current dependency policy for historical evidence; never replaces its frozen layout or values. */
  @Transactional(readOnly = true)
  public PublishedApplicationForm currentCalculationScope(final PublishedApplicationForm historical) {
    requireRead(historical);
    var form = entityManager.createQuery("from ApplicationFormEntity form where form.tenantId = :tenant "
        + "and form.applicationId = :application and form.id = :form", ApplicationFormEntity.class)
        .setParameter("tenant", historical.tenantId()).setParameter("application", historical.applicationId())
        .setParameter("form", historical.formId()).setMaxResults(1).getResultList().stream().findFirst()
        .orElseThrow(() -> failure(ApplicationFormErrorCode.VERSION_NOT_FOUND, "Current form policy is unavailable"));
    if (!historical.physicalModelId().equals(form.getPhysicalModelId()) || form.getPublishedVersion() == null) {
      throw failure(ApplicationFormErrorCode.VERSION_NOT_FOUND, "Current form policy is unavailable");
    }
    var version = entityManager.createQuery("from ApplicationFormVersionEntity version where version.tenantId = :tenant "
        + "and version.formId = :form and version.versionNumber = :version", ApplicationFormVersionEntity.class)
        .setParameter("tenant", historical.tenantId()).setParameter("form", historical.formId())
        .setParameter("version", form.getPublishedVersion()).setMaxResults(1).getResultList().stream().findFirst()
        .orElseThrow(() -> failure(ApplicationFormErrorCode.VERSION_NOT_FOUND, "Current form policy is unavailable"));
    requireDnaBinding(form, version);
    var document = codec.decode(version.getDocumentJson());
    return new PublishedApplicationForm(historical.tenantId(), historical.applicationId(), historical.pageId(), historical.pageName(),
        historical.formId(), historical.formName(), version.getVersionNumber(), historical.resourceCode(), historical.physicalModelId(),
        version.getPhysicalModelVersion(), version.getStorageChecksum(), document,
        version.getVersionNumber(), form.getWritePolicyVersion(), codec.decodeDetailBindings(version.getDetailBindingsJson()), null,
        codec.decodeRelationBindings(document, version.getRelationBindingsJson()));
  }

  /** Requires one page child action for a mutation. */
  public void requireAction(final PublishedApplicationForm form, final String action) {
    requireResource(form.resourceCode() + "." + action);
  }

  /** Resolves effective standard child actions for the current user. */
  public ApplicationFormRuntimeCapabilities capabilities(final PublishedApplicationForm form) {
    return new ApplicationFormRuntimeCapabilities(
        hasResource(form.resourceCode() + ".records.create"),
        hasResource(form.resourceCode() + ".records.edit"),
        hasResource(form.resourceCode() + ".records.delete"),
        hasResource(form.resourceCode() + ".process.start")
            && hasResource("workflow.instances.start")
            && (hasResource("workflow.instances.started.view")
                || hasResource("workflow.instances.view")
                || hasResource("workflow.tasks.view")));
  }

  private static void requireDnaBinding(
      final ApplicationFormEntity form, final ApplicationFormVersionEntity version) {
    if (form.getPhysicalModelId() == null
        || form.getPhysicalModelId().isBlank()
        || version.getPhysicalModelVersion() == null
        || version.getPhysicalModelVersion() < 1
        || version.getStorageChecksum() == null
        || !version.getStorageChecksum().matches("[a-fA-F0-9]{64}")) {
      throw failure(
          ApplicationFormErrorCode.PAGE_NOT_PUBLISHED, "The page's form data model is not active");
    }
  }

  private static void requireResource(final String resourceCode) {
    if (!hasResource(resourceCode)) {
      throw failure(
          ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
          "The current user is not authorized for this application page operation");
    }
  }

  private static boolean hasResource(final String resourceCode) {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    Collection<String> resources =
        context == null || context.getResources() == null ? List.of() : context.getResources();
    return context != null
        && (Boolean.TRUE.equals(context.getIsAdministrator()) || resources.contains(resourceCode));
  }

  private static String required(final String value, final String name) {
    if (value == null || value.isBlank() || value.trim().length() > 36) {
      throw failure(ApplicationFormErrorCode.DOCUMENT_INVALID, name + " is required");
    }
    return value.trim();
  }

  private static ApplicationFormException failure(
      final ApplicationFormErrorCode code, final String message) {
    return new ApplicationFormException(code, message);
  }

  /** Detached runtime identity safe to use outside the APP read transaction. */
  public record PublishedApplicationForm(
      String tenantId,
      String applicationId,
      String pageId,
      String pageName,
      String formId,
      String formName,
      int formVersion,
      String resourceCode,
      String physicalModelId,
      int physicalModelVersion,
      String storageChecksum,
      ApplicationFormDocument document,
      int writePolicyVersion,
      long writeAdmissionVersion,
      List<FormDetailBinding> detailBindings,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      ApplicationFormDocument calculationDocument,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
      List<FormRelationBinding> relationBindings) {
    /** Preserves the immutable runtime identity and optional child bindings. */
    public PublishedApplicationForm {
      detailBindings = detailBindings == null ? List.of() : List.copyOf(detailBindings);
      relationBindings = relationBindings == null ? List.of() : List.copyOf(relationBindings);
      if (relationBindings.size() > 50
          || relationBindings.stream().map(FormRelationBinding::relationId).distinct().count() != relationBindings.size()) {
        throw new IllegalArgumentException("Runtime relation bindings must have bounded unique identities");
      }
    }

    /** Legacy accepted snapshots retain the original JSON when no relation bindings were present. */
    public PublishedApplicationForm(String tenantId, String applicationId, String pageId, String pageName, String formId,
        String formName, int formVersion, String resourceCode, String physicalModelId, int physicalModelVersion,
        String storageChecksum, ApplicationFormDocument document, int writePolicyVersion, long writeAdmissionVersion,
        List<FormDetailBinding> detailBindings, ApplicationFormDocument calculationDocument) {
      this(tenantId, applicationId, pageId, pageName, formId, formName, formVersion, resourceCode, physicalModelId,
          physicalModelVersion, storageChecksum, document, writePolicyVersion, writeAdmissionVersion, detailBindings,
          calculationDocument, List.of());
    }

    /** Legacy accepted operations preserve their serialized shape when no separate calculation scope existed. */
    public PublishedApplicationForm(String tenantId, String applicationId, String pageId, String pageName, String formId,
        String formName, int formVersion, String resourceCode, String physicalModelId, int physicalModelVersion,
        String storageChecksum, ApplicationFormDocument document, int writePolicyVersion, long writeAdmissionVersion,
        List<FormDetailBinding> detailBindings) {
      this(tenantId, applicationId, pageId, pageName, formId, formName, formVersion, resourceCode, physicalModelId,
          physicalModelVersion, storageChecksum, document, writePolicyVersion, writeAdmissionVersion, detailBindings, null);
    }

    /** Preserves the immutable runtime identity and optional child bindings. */
    public PublishedApplicationForm(
        String tenantId,
        String applicationId,
        String pageId,
        String pageName,
        String formId,
        String formName,
        int formVersion,
        String resourceCode,
        String physicalModelId,
        int physicalModelVersion,
        String storageChecksum,
        ApplicationFormDocument document,
        int writePolicyVersion,
        long writeAdmissionVersion) {
      this(
          tenantId,
          applicationId,
          pageId,
          pageName,
          formId,
          formName,
          formVersion,
          resourceCode,
          physicalModelId,
          physicalModelVersion,
          storageChecksum,
          document,
          writePolicyVersion,
          writeAdmissionVersion,
          List.of());
    }

    /** Retains the target baseline for previously constructed runtime descriptors. */
    public PublishedApplicationForm(
        String tenantId,
        String applicationId,
        String pageId,
        String pageName,
        String formId,
        String formName,
        int formVersion,
        String resourceCode,
        String physicalModelId,
        int physicalModelVersion,
        String storageChecksum,
        ApplicationFormDocument document,
        int writePolicyVersion) {
      this(
          tenantId,
          applicationId,
          pageId,
          pageName,
          formId,
          formName,
          formVersion,
          resourceCode,
          physicalModelId,
          physicalModelVersion,
          storageChecksum,
          document,
          writePolicyVersion,
          0);
    }

    /** Legacy single-version snapshots use their own policy as the form-wide floor. */
    public PublishedApplicationForm(
        String tenantId,
        String applicationId,
        String pageId,
        String pageName,
        String formId,
        String formName,
        int formVersion,
        String resourceCode,
        String physicalModelId,
        int physicalModelVersion,
        String storageChecksum,
        ApplicationFormDocument document) {
      this(
          tenantId,
          applicationId,
          pageId,
          pageName,
          formId,
          formName,
          formVersion,
          resourceCode,
          physicalModelId,
          physicalModelVersion,
          storageChecksum,
          document,
          formVersion);
    }
  }
}
