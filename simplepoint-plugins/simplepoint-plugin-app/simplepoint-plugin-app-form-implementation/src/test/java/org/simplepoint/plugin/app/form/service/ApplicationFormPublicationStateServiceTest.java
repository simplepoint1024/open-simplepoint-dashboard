package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.PrepareApplicationFormDataCommand;
import org.simplepoint.plugin.app.form.api.model.SaveApplicationFormDraftCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationAccessPort;
import org.simplepoint.plugin.app.form.configuration.ApplicationFormPublicationProperties;
import org.simplepoint.plugin.app.form.entity.ApplicationFormDraftEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPublicationEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.PreparedDnaSchema;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus;

class ApplicationFormPublicationStateServiceTest {

  private static final Instant START = Instant.parse("2026-10-13T00:00:00Z");

  private SessionFactory sessionFactory;
  private Session session;
  private FormStorageProjectionCompiler storageCompiler;
  private ApplicationAccessPort applicationAccess;
  private FormRequestContext requestContext;
  private String formId;

  @BeforeEach
  void setUp() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:app-publish-" + UUID.randomUUID())
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
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    storageCompiler = new FormStorageProjectionCompiler(mapper);
    applicationAccess = applicationAccessPort();
    requestContext = new FormRequestContext();
    authorize();
    inTransaction(() -> {
      var forms = new ApplicationFormServiceImpl(
          session,
          applicationAccess,
          requestContext,
          new FormDocumentValidator(new DefaultFormComponentRegistry()),
          new FormDocumentCodec(mapper),
          storageCompiler
      );
      var form = forms.create(new CreateApplicationFormCommand(
          "app-a",
          "orders",
          "Orders",
          null
      ));
      formId = form.id();
      forms.saveDraft(
          "app-a",
          formId,
          new SaveApplicationFormDraftCommand(0, document("Order number"))
      );
      forms.createVersion("app-a", formId, 1);
      forms.saveDraft(
          "app-a",
          formId,
          new SaveApplicationFormDraftCommand(1, document("Order reference"))
      );
      forms.createVersion("app-a", formId, 2);
    });
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
  void claimsCompletesAndReplaysOneFormVersion() {
    ApplicationFormPublicationStateService state = stateAt(START);
    var claim = inTransaction(() -> state.claim(
        "app-a",
        formId,
        new PrepareApplicationFormDataCommand(1, "publish-orders-v1")
    ));

    assertThat(claim.execute()).isTrue();
    assertThat(claim.view().status()).isEqualTo(
        ApplicationFormPublicationStatus.DATA_PREPARING
    );
    assertThat(claim.view().attempt()).isEqualTo(1);

    var active = new PreparedDnaSchema(
        "model-a",
        1,
        "migration-a",
        SchemaMigrationStatus.ACTIVE
    );
    var complete = inTransaction(() -> state.completeData(
        claim.work().publicationId(),
        claim.work().executionToken(),
        active
    ));
    assertThat(complete.status()).isEqualTo(ApplicationFormPublicationStatus.DATA_READY);
    assertThat(complete.dataReady()).isTrue();

    var replay = inTransaction(() -> state.claim(
        "app-a",
        formId,
        new PrepareApplicationFormDataCommand(1, "another-click")
    ));
    assertThat(replay.execute()).isFalse();
    assertThat(replay.view().id()).isEqualTo(claim.view().id());

    inTransaction(() -> {
      ApplicationFormEntity form = session.find(ApplicationFormEntity.class, formId);
      ApplicationFormVersionEntity version = session.createQuery(
              "from ApplicationFormVersionEntity version where version.formId = :form "
                  + "and version.versionNumber = 1",
              ApplicationFormVersionEntity.class
          )
          .setParameter("form", formId)
          .getSingleResult();
      assertThat(form.getPhysicalModelId()).isEqualTo("model-a");
      assertThat(version.getPhysicalModelVersion()).isEqualTo(1);
    });
  }

  @Test
  void preservesTheLeaseAndReclaimsOnlyAfterItExpires() {
    var first = inTransaction(() -> stateAt(START).claim(
        "app-a",
        formId,
        new PrepareApplicationFormDataCommand(1, "publish-orders-v1")
    ));
    var concurrent = inTransaction(() -> stateAt(START.plusSeconds(30)).claim(
        "app-a",
        formId,
        new PrepareApplicationFormDataCommand(1, "publish-orders-v1")
    ));
    assertThat(concurrent.execute()).isFalse();

    var reclaimed = inTransaction(() -> stateAt(START.plusSeconds(1_201)).claim(
        "app-a",
        formId,
        new PrepareApplicationFormDataCommand(1, "publish-orders-v1")
    ));
    assertThat(reclaimed.execute()).isTrue();
    assertThat(reclaimed.view().status()).isEqualTo(ApplicationFormPublicationStatus.RETRYING);
    assertThat(reclaimed.view().attempt()).isEqualTo(2);
    assertThat(reclaimed.work().executionToken()).isNotEqualTo(first.work().executionToken());
    assertThat(reclaimed.work().recoverStaleDna()).isTrue();
  }

  @Test
  void retriesFailuresAndRejectsKeyReuseForAnotherVersion() {
    var first = inTransaction(() -> stateAt(START).claim(
        "app-a",
        formId,
        new PrepareApplicationFormDataCommand(1, "publish-orders-v1")
    ));
    inTransaction(() -> stateAt(START).fail(
        first.work().publicationId(),
        first.work().executionToken(),
        ApplicationFormErrorCode.PUBLICATION_FAILED
    ));
    var retry = inTransaction(() -> stateAt(START.plusSeconds(1)).claim(
        "app-a",
        formId,
        new PrepareApplicationFormDataCommand(1, "publish-orders-v1")
    ));
    assertThat(retry.execute()).isTrue();
    assertThat(retry.view().attempt()).isEqualTo(2);
    assertThat(retry.work().recoverStaleDna()).isFalse();

    assertThatThrownBy(() -> inTransaction(() -> stateAt(START).claim(
        "app-a",
        formId,
        new PrepareApplicationFormDataCommand(2, "publish-orders-v1")
    ))).isInstanceOfSatisfying(ApplicationFormException.class, exception ->
        assertThat(exception.errorCode()).isEqualTo(
            ApplicationFormErrorCode.PUBLICATION_IDEMPOTENCY_CONFLICT
        ));
  }

  private ApplicationFormPublicationStateService stateAt(final Instant instant) {
    ApplicationFormPublicationProperties properties = new ApplicationFormPublicationProperties();
    return new ApplicationFormPublicationStateService(
        session,
        requestContext,
        storageCompiler,
        new FormDocumentValidator(new DefaultFormComponentRegistry()),
        new FormDocumentCodec(new ObjectMapper()),
        Clock.fixed(instant, ZoneOffset.UTC),
        properties
    );
  }

  private <T> T inTransaction(final java.util.concurrent.Callable<T> work) {
    session.beginTransaction();
    try {
      T result = work.call();
      session.getTransaction().commit();
      return result;
    } catch (RuntimeException exception) {
      session.getTransaction().rollback();
      throw exception;
    } catch (Exception exception) {
      session.getTransaction().rollback();
      throw new IllegalStateException(exception);
    }
  }

  private void inTransaction(final Runnable work) {
    inTransaction(() -> {
      work.run();
      return null;
    });
  }

  private static ApplicationFormDocument document(final String label) {
    FormFieldDefinition field = new FormFieldDefinition(
        "order_number",
        "order_number",
        label,
        "Order number",
        FormFieldType.STRING,
        FormStorageMode.STORED,
        true,
        false,
        64,
        null,
        null,
        null,
        List.of()
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
                List.of(new FormComponentDefinition("order-input", "field.text", "1",
                    field.fieldId(), 24, null, null, null, null, false, Map.of(), List.of()))
            )
        )),
        List.of()
    );
  }

  private static void authorize() {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("user-a");
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private static ApplicationAccessPort applicationAccessPort() {
    return (tenantId, applicationId) -> {
      if (!"tenant-a".equals(tenantId) || !"app-a".equals(applicationId)) {
        throw new ApplicationFormException(
            ApplicationFormErrorCode.APPLICATION_NOT_FOUND,
            "Application was not found"
        );
      }
    };
  }
}
