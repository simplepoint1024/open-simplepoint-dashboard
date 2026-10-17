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
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
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

/** Verifies form drafts and immutable versions against a real relational transaction. */
class ApplicationFormServiceDatabaseTest {

  private SessionFactory sessionFactory;
  private Session session;
  private ApplicationFormService service;

  @BeforeEach
  void setUp() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:app-form-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build();
    sessionFactory = new MetadataSources(registry)
        .addAnnotatedClass(ApplicationFormEntity.class)
        .addAnnotatedClass(ApplicationFormDraftEntity.class)
        .addAnnotatedClass(ApplicationFormVersionEntity.class)
        .addAnnotatedClass(ApplicationFormPublicationEntity.class)
        .buildMetadata()
        .buildSessionFactory();
    session = sessionFactory.openSession();
    session.beginTransaction();
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    service = new ApplicationFormServiceImpl(
        session,
        applicationAccessPort(),
        new FormRequestContext(),
        new FormDocumentValidator(new DefaultFormComponentRegistry()),
        new FormDocumentCodec(mapper),
        new FormStorageProjectionCompiler(mapper)
    );
    authorize("tenant-a", "user-a");
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
  void createsValidDraftAndFreezesOnlyChangedImmutableVersions() {
    var form = service.create(new CreateApplicationFormCommand(
        "app-a",
        "customer",
        "Customer",
        "Customer profile"
    ));
    var initial = service.draft("app-a", form.id());
    assertThat(initial.revision()).isZero();
    assertThat(initial.document().scenes()).singleElement()
        .extracting(FormSceneDefinition::type)
        .isEqualTo(FormSceneType.MAIN);

    var saved = service.saveDraft(
        "app-a",
        form.id(),
        new SaveApplicationFormDraftCommand(0, customerDocument("Customer name"))
    );
    assertThat(saved.revision()).isEqualTo(1);

    var versionOne = service.createVersion("app-a", form.id(), 1);
    var sameVersion = service.createVersion("app-a", form.id(), 1);
    assertThat(versionOne.version()).isEqualTo(1);
    assertThat(sameVersion.id()).isEqualTo(versionOne.id());

    var secondDraft = service.saveDraft(
        "app-a",
        form.id(),
        new SaveApplicationFormDraftCommand(1, customerDocument("Legal name"))
    );
    var versionTwo = service.createVersion("app-a", form.id(), secondDraft.revision());
    assertThat(versionTwo.version()).isEqualTo(2);
    assertThat(versionTwo.storageChecksum()).isEqualTo(versionOne.storageChecksum());
    assertThat(versionTwo.physicalModelVersion()).isNull();
    assertThat(service.versions("app-a", form.id()))
        .extracting(version -> version.version())
        .containsExactly(2, 1);
  }

  @Test
  void rejectsStaleDraftAndCrossTenantReads() {
    var form = service.create(new CreateApplicationFormCommand(
        "app-a",
        "expense",
        "Expense",
        null
    ));
    service.saveDraft(
        "app-a",
        form.id(),
        new SaveApplicationFormDraftCommand(0, customerDocument("Name"))
    );

    assertCode(
        () -> service.saveDraft(
            "app-a",
            form.id(),
            new SaveApplicationFormDraftCommand(0, customerDocument("Stale"))
        ),
        ApplicationFormErrorCode.DRAFT_REVISION_CONFLICT
    );

    authorize("tenant-b", "user-b");
    assertCode(
        () -> service.get("app-a", form.id()),
        ApplicationFormErrorCode.APPLICATION_NOT_FOUND
    );
  }

  @Test
  void preservesIncompleteDraftWithoutCreatingAnUnusableVersion() {
    var form = service.create(new CreateApplicationFormCommand("app-a", "incomplete", "Draft", null));
    var complete = customerDocument("Required input");
    var empty = service.draft("app-a", form.id()).document();
    var incomplete = new ApplicationFormDocument(complete.contractVersion(), complete.fields(),
        complete.indexes(), empty.scenes(), complete.rules());
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
        () -> service.create(new CreateApplicationFormCommand(
            "app-a",
            "customer",
            "Two",
            null
        )),
        ApplicationFormErrorCode.FORM_CODE_CONFLICT
    );
  }

  @Test
  void enforcesTheApplicationFormCapacityAtTheServiceBoundary() {
    for (int index = 0; index < 100; index++) {
      service.create(new CreateApplicationFormCommand(
          "app-a",
          "form_" + index,
          "Form " + index,
          null
      ));
    }

    assertCode(
        () -> service.create(new CreateApplicationFormCommand(
            "app-a",
            "form_overflow",
            "Overflow",
            null
        )),
        ApplicationFormErrorCode.FORM_LIMIT_EXCEEDED
    );
  }

  private static ApplicationFormDocument customerDocument(final String label) {
    FormFieldDefinition field = new FormFieldDefinition(
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
        List.of()
    );
    FormComponentDefinition input = new FormComponentDefinition(
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
        List.of()
    );
    FormComponentDefinition root = new FormComponentDefinition(
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
        List.of(input)
    );
    return new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        List.of(field),
        List.of(),
        List.of(new FormSceneDefinition(
            "main",
            "main",
            "Main",
            FormSceneType.MAIN,
            null,
            root
        )),
        List.of()
    );
  }

  private static void assertCode(
      final org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
      final ApplicationFormErrorCode expected
  ) {
    assertThatThrownBy(invocation)
        .isInstanceOfSatisfying(ApplicationFormException.class, exception ->
            assertThat(exception.errorCode()).isEqualTo(expected));
  }

  @Test
  void catalogPaginatesAndFiltersWithinBothTenantAndApplication() {
    service.create(new CreateApplicationFormCommand("app-a", "alpha", "Alpha", null));
    service.create(new CreateApplicationFormCommand("app-a", "beta", "Beta", null));
    seedOtherForm("tenant-b", "app-a", "other_tenant");
    seedOtherForm("tenant-a", "app-b", "other_app");
    var catalog = new ApplicationFormCatalogServiceImpl(session, new FormRequestContext(),
        applicationAccessPort(), new DefaultFormComponentRegistry());
    var first = catalog.page("app-a", Map.of(), 0, 1, List.of("code,asc"));
    assertThat(first.page().totalElements()).isEqualTo(2);
    assertThat(first.page().totalPages()).isEqualTo(2);
    assertThat(first.content()).extracting(form -> form.code()).containsExactly("alpha");
    assertThat(catalog.page("app-a", Map.of(), 1, 1, List.of("code,asc")).content())
        .extracting(form -> form.code()).containsExactly("beta");
    assertThat(catalog.page("app-a", Map.of("name", "like:ALP"), 0, 20, List.of())
        .page().totalElements()).isEqualTo(1);
    assertThatThrownBy(() -> catalog.page("app-a", Map.of("tenantId", "equals:tenant-b"),
        0, 20, List.of())).isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> catalog.page("app-a", Map.of(), 0, 101, List.of()))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> catalog.page("app-a", Map.of(), 0, 20,
        List.of("code,asc", "code,desc")))
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
    context.setResources(List.of("app.workbench.forms.edit"));
    context.setFieldPermissions(Map.of("ApplicationFormView#name", "HIDDEN"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var catalog = new ApplicationFormCatalogServiceImpl(session, new FormRequestContext(),
        applicationAccessPort(), new DefaultFormComponentRegistry());
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
            "Application was not found in the active tenant"
        );
      }
    };
  }
}
