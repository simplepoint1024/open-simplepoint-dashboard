package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog;
import org.simplepoint.app.form.service.WorkspaceApplicationAccessPort;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDraftRevision;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.SaveApplicationFormDraftCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationAccessPort;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.simplepoint.plugin.app.form.entity.ApplicationFormDraftEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPublicationEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplication;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplicationPage;

/** Verifies form drafts and immutable versions against a real relational transaction. */
class ApplicationFormServiceDatabaseTest {

  private SessionFactory sessionFactory;
  private Session session;
  private ApplicationFormService service;

  @BeforeEach
  void setUp() {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", "jdbc:h2:mem:app-form-" + UUID.randomUUID())
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .build();
    sessionFactory =
        new MetadataSources(registry)
            .addAnnotatedClass(ApplicationFormEntity.class)
            .addAnnotatedClass(ApplicationFormDraftEntity.class)
            .addAnnotatedClass(ApplicationFormVersionEntity.class)
            .addAnnotatedClass(ApplicationFormPublicationEntity.class)
            .addAnnotatedClass(WorkspaceApplication.class)
            .addAnnotatedClass(WorkspaceApplicationPage.class)
            .buildMetadata()
            .buildSessionFactory();
    session = sessionFactory.openSession();
    session.beginTransaction();
    service = forms(applicationAccessPort());
    authorize("tenant-a", "user-a");
  }

  private ApplicationFormService forms(ApplicationAccessPort access) {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    return new ApplicationFormServiceImpl(
            session,
            access,
            new FormRequestContext(),
            new FormDocumentValidator(new DefaultFormComponentRegistry()),
            new FormDocumentCodec(mapper),
            new FormStorageProjectionCompiler(mapper),
            org.mockito.Mockito.mock(FormRelationTargetResolver.class));
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {
      if (session.getTransaction().isActive()) {
        session.getTransaction().rollback();
      }
      session.close();
    }
    if (sessionFactory != null) {
      sessionFactory.close();
    }
  }

  @Test
  void aStaleMemberPreventsTheEntireDraftDeletion() {
    var first = service.create(new CreateApplicationFormCommand("app-a", "first", "First", null));
    var second = service.create(new CreateApplicationFormCommand("app-a", "second", "Second", null));
    var firstRevision = new ApplicationFormDraftRevision(first.id(), first.draftRevision());
    var secondRevision = new ApplicationFormDraftRevision(second.id(), second.draftRevision());
    assertThatThrownBy(() -> service.deleteDrafts("app-a", List.of(firstRevision,
        new ApplicationFormDraftRevision(second.id(), second.draftRevision() + 1))))
        .isInstanceOfSatisfying(ApplicationFormException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ApplicationFormErrorCode.DRAFT_REVISION_CONFLICT));
    assertThat(service.list("app-a")).extracting(view -> view.id()).contains(first.id(), second.id());
    service.deleteDrafts("app-a", List.of(firstRevision, secondRevision));
    session.flush();
    assertThat(service.list("app-a")).isEmpty();
    assertThat(session.createQuery("from ApplicationFormDraftEntity", ApplicationFormDraftEntity.class)
        .getResultList()).isEmpty();
  }

  @Test
  void removingDraftApplicationsRemovesTheirPagesAndFormDrafts() {
    AuthorizationContextHolder.getContext().setScopeType(AuthorizationScopeType.TENANT);
    var forms = forms(new WorkspaceApplicationAccessPort(session));
    var catalog = new WorkspaceApplicationCatalog(session, forms);
    var first = catalog.create(new WorkspaceApplicationCatalog.CreateApplication("First", null, "Main"));
    var second = catalog.create(new WorkspaceApplicationCatalog.CreateApplication("Second", null, "Main"));
    for (var app : List.of(first.application(), second.application())) {
      forms.create(new CreateApplicationFormCommand(app.id(), "draft", "Draft", null));
    }
    session.flush();
    var selection = List.of(first.application().id(), second.application().id()).stream()
        .map(catalog::detail)
        .map(info -> new WorkspaceApplicationCatalog.CatalogRevision(
            info.application().id(), info.application().updatedAt())).toList();
    catalog.deleteApplications(selection);
    session.flush();
    for (var entity : List.of(WorkspaceApplication.class, WorkspaceApplicationPage.class,
        ApplicationFormEntity.class, ApplicationFormDraftEntity.class)) {
      assertThat(session.createQuery("select count(*) from " + entity.getSimpleName(), Long.class)
          .getSingleResult()).isZero();
    }
  }

  @Test
  void createsValidDraftAndFreezesOnlyChangedImmutableVersions() {
    var form =
        service.create(
            new CreateApplicationFormCommand("app-a", "customer", "Customer", "Customer profile"));
    var initial = service.draft("app-a", form.id());
    assertThat(initial.revision()).isZero();
    assertThat(initial.document().scenes())
        .singleElement()
        .extracting(FormSceneDefinition::type)
        .isEqualTo(FormSceneType.MAIN);

    var saved =
        service.saveDraft(
            "app-a",
            form.id(),
            new SaveApplicationFormDraftCommand(0, customerDocument("Customer name")));
    assertThat(saved.revision()).isEqualTo(1);

    var versionOne = service.createVersion("app-a", form.id(), 1);
    var sameVersion = service.createVersion("app-a", form.id(), 1);
    assertThat(versionOne.version()).isEqualTo(1);
    assertThat(sameVersion.id()).isEqualTo(versionOne.id());

    var secondDraft =
        service.saveDraft(
            "app-a",
            form.id(),
            new SaveApplicationFormDraftCommand(1, customerDocument("Legal name")));
    var versionTwo = service.createVersion("app-a", form.id(), secondDraft.revision());
    assertThat(versionTwo.version()).isEqualTo(2);
    assertThat(versionTwo.storageChecksum()).isEqualTo(versionOne.storageChecksum());
    assertThat(versionTwo.physicalModelVersion()).isNull();
    assertThat(service.versions("app-a", form.id()))
        .extracting(version -> version.version())
        .containsExactly(2, 1);
  }

  @Test
  void freezesRelationBindingsOnceAndThePersistedSnapshotCannotBeUpdated() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    final var codec = new FormDocumentCodec(mapper);
    var resolver = org.mockito.Mockito.mock(FormRelationTargetResolver.class);
    // Isolate immutable-version persistence below the public relation-release gate. The gate itself
    // remains covered by FormRelationDocumentTest until the complete runtime is available.
    var internal =
        new ApplicationFormServiceImpl(
            session,
            applicationAccessPort(),
            new FormRequestContext(),
            org.mockito.Mockito.mock(FormDocumentValidator.class),
            codec,
            new FormStorageProjectionCompiler(mapper),
            resolver);
    var form = internal.create(new CreateApplicationFormCommand("app-a", "trade", "Trade", null));
    var document = FormRelationPublicationProjectionTest.document();
    var bindings = FormRelationPublicationProjectionTest.bindings(document);
    org.mockito.Mockito.when(resolver.freeze(document)).thenReturn(bindings);
    internal.saveDraft("app-a", form.id(), new SaveApplicationFormDraftCommand(0, document));
    var version = internal.createVersion("app-a", form.id(), 1);
    String frozen =
        session.find(ApplicationFormVersionEntity.class, version.id()).getRelationBindingsJson();
    assertThat(codec.decodeRelationBindings(document, frozen)).containsExactlyElementsOf(bindings);
    org.mockito.Mockito.when(resolver.freeze(document))
        .thenThrow(new AssertionError("Retry re-resolved frozen targets"));
    assertThat(internal.createVersion("app-a", form.id(), 1).id()).isEqualTo(version.id());
    session
        .find(ApplicationFormVersionEntity.class, version.id())
        .setRelationBindingsJson("must-not-overwrite");
    session.flush();
    session.clear();
    assertThat(
            session
                .find(ApplicationFormVersionEntity.class, version.id())
                .getRelationBindingsJson())
        .isEqualTo(frozen);
  }

  @Test
  void inverseViewsMustPassResolvedValidationBeforeCreatingAnImmutableVersion() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var codec = new FormDocumentCodec(mapper);
    var resolver = org.mockito.Mockito.mock(FormRelationTargetResolver.class);
    var internal =
        new ApplicationFormServiceImpl(
            session,
            applicationAccessPort(),
            new FormRequestContext(),
            new FormDocumentValidator(new DefaultFormComponentRegistry()),
            codec,
            new FormStorageProjectionCompiler(mapper),
            resolver);
    var form = internal.create(new CreateApplicationFormCommand("app-a", "customers", "客户", null));
    session.find(ApplicationFormEntity.class, form.id()).setPhysicalModelId("customer-model");
    var original = customerDocument("客户名称");
    var view =
        new org.simplepoint.plugin.app.form.api.model.relation.FormInverseRelationView(
            "trades",
            "交易记录",
            new org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget(
                "sales", "trades", "trade-entry"),
            "customer",
            true,
            true,
            true,
            true,
            null);
    var document =
        new ApplicationFormDocument(
            original.contractVersion(),
            original.fields(),
            original.indexes(),
            original.scenes(),
            original.rules(),
            original.details(),
            original.constraints(),
            new org.simplepoint.plugin.app.form.api.model.FormPresentation(
                List.of(), 140, List.of(view)));
    internal.saveDraft("app-a", form.id(), new SaveApplicationFormDraftCommand(0, document));
    org.mockito.Mockito.doThrow(
            new ApplicationFormException(
                ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "Invalid source"))
        .when(resolver)
        .validateInverseViews("app-a", form.id(), "customer-model", document);
    assertThatThrownBy(() -> internal.createVersion("app-a", form.id(), 1))
        .isInstanceOf(ApplicationFormException.class);
    assertThat(internal.versions("app-a", form.id())).isEmpty();
    org.mockito.Mockito.doNothing()
        .when(resolver)
        .validateInverseViews("app-a", form.id(), "customer-model", document);
    var saved = internal.createVersion("app-a", form.id(), 1);
    assertThat(
            codec
                .decode(
                    session.find(ApplicationFormVersionEntity.class, saved.id()).getDocumentJson())
                .presentation()
                .inverseViews())
        .containsExactly(view);
    assertThat(internal.createVersion("app-a", form.id(), 1).id()).isEqualTo(saved.id());
  }

  @Test
  void unchangedLegacyDraftCreatesAnAdditiveIdentityVersionWithoutRewritingOldStorage() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    final var codec = new FormDocumentCodec(mapper);
    final var storage = new FormStorageProjectionCompiler(mapper);
    var form = service.create(new CreateApplicationFormCommand("app-a", "legacy", "Legacy", null));
    var document = customerDocument("Name");
    service.saveDraft("app-a", form.id(), new SaveApplicationFormDraftCommand(0, document));
    var old = new ApplicationFormVersionEntity();
    old.setId(UUID.randomUUID().toString());
    old.setTenantId("tenant-a");
    old.setFormId(form.id());
    old.setVersionNumber(1);
    old.setDocumentJson(codec.encode(document).json());
    old.setChecksum(codec.encode(document).checksum());
    old.setStorageSchemaJson(storage.compile(document).json());
    old.setStorageChecksum(storage.compile(document).checksum());
    old.setRendererVersion(ApplicationFormApiVersion.RENDERER_V1);
    old.setCreatedAt(Instant.now());
    session.persist(old);
    session.find(ApplicationFormEntity.class, form.id()).setLatestVersion(1);
    session.flush();
    session.clear();
    var upgraded = service.createVersion("app-a", form.id(), 1);
    assertThat(upgraded.version()).isEqualTo(2);
    assertThat(upgraded.checksum()).isEqualTo(old.getChecksum());
    var stored = session.find(ApplicationFormVersionEntity.class, upgraded.id());
    assertThat(storage.decode(stored.getStorageSchemaJson()).tenantRecordIdentity()).isTrue();
    assertThat(stored.getRelationBindingsJson()).isNull();
    assertThat(session.find(ApplicationFormVersionEntity.class, old.getId()).getStorageSchemaJson())
        .isEqualTo(old.getStorageSchemaJson());
    assertThat(service.createVersion("app-a", form.id(), 1).id()).isEqualTo(upgraded.id());
  }

  @Test
  void rejectsStaleDraftAndCrossTenantReads() {
    var form =
        service.create(new CreateApplicationFormCommand("app-a", "expense", "Expense", null));
    service.saveDraft(
        "app-a", form.id(), new SaveApplicationFormDraftCommand(0, customerDocument("Name")));

    assertCode(
        () ->
            service.saveDraft(
                "app-a",
                form.id(),
                new SaveApplicationFormDraftCommand(0, customerDocument("Stale"))),
        ApplicationFormErrorCode.DRAFT_REVISION_CONFLICT);

    authorize("tenant-b", "user-b");
    assertCode(
        () -> service.get("app-a", form.id()), ApplicationFormErrorCode.APPLICATION_NOT_FOUND);
  }

  @Test
  void preservesIncompleteDraftWithoutCreatingAnUnusableVersion() {
    var form =
        service.create(new CreateApplicationFormCommand("app-a", "incomplete", "Draft", null));
    var complete = customerDocument("Required input");
    var empty = service.draft("app-a", form.id()).document();
    var incomplete =
        new ApplicationFormDocument(
            complete.contractVersion(),
            complete.fields(),
            complete.indexes(),
            empty.scenes(),
            complete.rules());
    service.saveDraft("app-a", form.id(), new SaveApplicationFormDraftCommand(0, incomplete));
    assertThatThrownBy(() -> service.createVersion("app-a", form.id(), 1))
        .isInstanceOf(org.simplepoint.core.validation.InvalidFormException.class);
    assertThat(service.versions("app-a", form.id())).isEmpty();
    assertThat(service.draft("app-a", form.id()).document().fields()).hasSize(1);
  }

  @Test
  void rejectsDuplicateCodesWithinOneApplication() {
    service.create(new CreateApplicationFormCommand("app-a", "customer", "One", null));
    assertCode(
        () -> service.create(new CreateApplicationFormCommand("app-a", "customer", "Two", null)),
        ApplicationFormErrorCode.FORM_CODE_CONFLICT);
  }

  @Test
  void enforcesTheApplicationFormCapacityAtTheServiceBoundary() {
    for (int index = 0; index < 100; index++) {
      service.create(
          new CreateApplicationFormCommand("app-a", "form_" + index, "Form " + index, null));
    }

    assertCode(
        () ->
            service.create(
                new CreateApplicationFormCommand("app-a", "form_overflow", "Overflow", null)),
        ApplicationFormErrorCode.FORM_LIMIT_EXCEEDED);
  }

  private static ApplicationFormDocument customerDocument(final String label) {
    FormFieldDefinition field =
        new FormFieldDefinition(
            "customer_name",
            "customer_name",
            label,
            "Customer name",
            FormFieldType.STRING,
            FormStorageMode.STORED,
            true,
            false,
            120,
            null,
            null,
            null,
            List.of());
    FormComponentDefinition input =
        new FormComponentDefinition(
            "customer_name_input",
            "field.text",
            ApplicationFormApiVersion.RENDERER_V1,
            field.fieldId(),
            24,
            null,
            null,
            null,
            null,
            false,
            Map.of(),
            List.of());
    FormComponentDefinition root =
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
            Map.of(),
            List.of(input));
    return new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        List.of(field),
        List.of(),
        List.of(new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)),
        List.of());
  }

  private static void assertCode(
      final org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
      final ApplicationFormErrorCode expected) {
    assertThatThrownBy(invocation)
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(expected));
  }

  @Test
  void catalogPaginatesAndFiltersWithinBothTenantAndApplication() {
    service.create(new CreateApplicationFormCommand("app-a", "alpha", "Alpha", null));
    service.create(new CreateApplicationFormCommand("app-a", "beta", "Beta", null));
    seedOtherForm("tenant-b", "app-a", "other_tenant");
    seedOtherForm("tenant-a", "app-b", "other_app");
    var catalog =
        new ApplicationFormCatalogServiceImpl(
            session,
            new FormRequestContext(),
            applicationAccessPort(),
            new DefaultFormComponentRegistry());
    var first = catalog.page("app-a", Map.of(), 0, 1, List.of("code,asc"));
    assertThat(first.page().totalElements()).isEqualTo(2);
    assertThat(first.page().totalPages()).isEqualTo(2);
    assertThat(first.content()).extracting(form -> form.code()).containsExactly("alpha");
    assertThat(catalog.page("app-a", Map.of(), 1, 1, List.of("code,asc")).content())
        .extracting(form -> form.code())
        .containsExactly("beta");
    assertThat(
            catalog
                .page("app-a", Map.of("name", "like:ALP"), 0, 20, List.of())
                .page()
                .totalElements())
        .isEqualTo(1);
    assertThatThrownBy(
            () -> catalog.page("app-a", Map.of("tenantId", "equals:tenant-b"), 0, 20, List.of()))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> catalog.page("app-a", Map.of(), 0, 501, List.of()))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(
            () -> catalog.page("app-a", Map.of(), 0, 20, List.of("code,asc", "code,desc")))
        .isInstanceOf(InvalidQueryException.class);
    authorize("tenant-b", "user-b");
    assertThatThrownBy(() -> catalog.page("app-a", Map.of(), 0, 20, List.of()))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void catalogSchemaPreservesFieldRestrictionsAndServerActionCapabilities() {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("user-a");
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    context.setScopeType(org.simplepoint.core.AuthorizationScopeType.TENANT);
    context.setResources(List.of("app.workbench.view", "app.workbench.forms.edit"));
    context.setFieldPermissions(Map.of("ApplicationFormView#name", "HIDDEN"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var catalog =
        new ApplicationFormCatalogServiceImpl(
            session,
            new FormRequestContext(),
            applicationAccessPort(),
            new DefaultFormComponentRegistry());
    var descriptor = catalog.designer("app-a");
    assertThat(descriptor.canCreate()).isFalse();
    assertThat(descriptor.canEdit()).isTrue();
    assertThat(descriptor.canPublish()).isFalse();
    assertThat(descriptor.components()).isNotEmpty();
    var result = catalog.schema("app-a");
    var schema = (Map<?, ?>) result.get("schema");
    assertThat(((Map<?, ?>) schema.get("properties")).containsKey("name")).isFalse();
    assertThat(((List<?>) schema.get("required")).contains("name")).isFalse();
    assertThat((List<?>) result.get("buttons")).hasSize(1);
  }

  @Test
  void catalogExposesRemovalOnlyWhenTheWorkspaceGrantsIt() {
    var catalog = new ApplicationFormCatalogServiceImpl(
        session, new FormRequestContext(), applicationAccessPort(), new DefaultFormComponentRegistry());
    for (boolean granted : List.of(false, true)) {
      var context = new AuthorizationContext();
      context.setUserId("user-a");
      context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
      context.setScopeType(org.simplepoint.core.AuthorizationScopeType.TENANT);
      context.setIsAdministrator(true);
      context.setResources(granted
          ? List.of("app.workbench.view", "app.workbench.forms.delete")
          : List.of("app.workbench.view"));
      RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
      assertThat((List<?>) catalog.schema("app-a").get("buttons")).hasSize(granted ? 2 : 1);
    }
  }

  private void seedOtherForm(final String tenant, final String app, final String code) {
    var form = new ApplicationFormEntity();
    form.setId(UUID.randomUUID().toString());
    form.setTenantId(tenant);
    form.setApplicationId(app);
    form.setCode(code);
    form.setName(code);
    form.setStatus(ApplicationFormStatus.DRAFT);
    form.setCreatedAt(Instant.now());
    form.setUpdatedAt(Instant.now());
    session.persist(form);
    session.flush();
  }

  private static void authorize(final String tenantId, final String userId) {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId(userId);
    context.setAttributes(Map.of("X-Tenant-Id", tenantId));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private static ApplicationAccessPort applicationAccessPort() {
    return (tenantId, applicationId) -> {
      if (!"tenant-a".equals(tenantId) || !"app-a".equals(applicationId)) {
        throw new ApplicationFormException(
            ApplicationFormErrorCode.APPLICATION_NOT_FOUND,
            "Application was not found in the active tenant");
      }
    };
  }
}
