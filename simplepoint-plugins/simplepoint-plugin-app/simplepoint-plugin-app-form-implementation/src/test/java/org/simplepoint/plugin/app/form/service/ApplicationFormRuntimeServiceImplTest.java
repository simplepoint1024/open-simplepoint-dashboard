package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationPageBindingTarget;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.service.ApplicationPageAccessPort;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;

class ApplicationFormRuntimeServiceImplTest {

  private SessionFactory factory;
  private Session session;
  private ApplicationPageAccessPort pages;
  private ApplicationFormRuntimeServiceImpl runtime;
  private PublishedApplicationFormResolver resolver;
  private FormDocumentCodec codec;

  @BeforeEach
  void setUp() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:app-runtime-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build();
    factory = new MetadataSources(registry)
        .addAnnotatedClass(ApplicationFormEntity.class)
        .addAnnotatedClass(ApplicationFormVersionEntity.class)
        .buildMetadata()
        .buildSessionFactory();
    session = factory.openSession();
    pages = mock(ApplicationPageAccessPort.class);
    codec = new FormDocumentCodec(new ObjectMapper().findAndRegisterModules());
    resolver = new PublishedApplicationFormResolver(
        session,
        new FormRequestContext(),
        pages,
        codec
    );
    runtime = new ApplicationFormRuntimeServiceImpl(
        resolver,
        new FormRuntimeDocumentCompiler()
    );
    session.beginTransaction();
    persistForm(codec);
    session.getTransaction().commit();
    when(pages.requireActiveBinding("tenant-a", "app-a", "page-a")).thenReturn(
        new ApplicationPageBindingTarget(
            "page-a",
            "Orders",
            "form-a",
            1,
            "app.runtime.page.one"
        )
    );
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {
      session.close();
    }
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void resolvesOnlyTheVersionPinnedByTheAuthorizedPageBinding() {
    authorize(Set.of("app.runtime.page.one"));

    var view = runtime.page("app-a", "page-a");

    assertThat(view.pageName()).isEqualTo("Orders");
    assertThat(view.formName()).isEqualTo("Order form");
    assertThat(view.formVersion()).isEqualTo(1);
    assertThat(view.sceneId()).isEqualTo("order-main");
    assertThat(view.document().scenes()).singleElement()
        .extracting(FormSceneDefinition::sceneId)
        .isEqualTo("order-main");
  }

  @Test
  void resolvesTheActivatedAdmissionVersionSeparatelyFromThePinnedFormVersion() {
    authorize(Set.of("app.runtime.page.one"));
    session.beginTransaction();
    session.find(ApplicationFormEntity.class, "form-a").setWritePolicyVersion(9);
    session.getTransaction().commit();
    session.clear();
    var resolved = resolver.resolve("app-a", "page-a");
    assertThat(resolved.formVersion()).isEqualTo(1);
    assertThat(resolved.writePolicyVersion()).isEqualTo(1);
    assertThat(resolved.writeAdmissionVersion()).isEqualTo(9);
  }

  @Test
  void rejectsUserWithoutTheDynamicPageResource() {
    authorize(Set.of("app.workbench.view"));

    assertThatThrownBy(() -> runtime.page("app-a", "page-a"))
        .isInstanceOfSatisfying(ApplicationFormException.class, exception ->
            assertThat(exception.errorCode())
                .isEqualTo(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED));
  }

  @Test
  void oldPagesUseTheActivatedPolicyWithoutAdoptingDraftRulesOrNewerPresentation() {
    authorize(Set.of("app.runtime.page.one"));
    persistPolicyVersion(2, FormFieldWritePolicy.CREATE_ONLY, true);
    persistPolicyVersion(3, FormFieldWritePolicy.EDITABLE, false);
    var view = runtime.page("app-a", "page-a");
    assertThat(view.formVersion()).isEqualTo(1);
    assertThat(view.writePolicyVersion()).isEqualTo(2);
    assertThat(view.document().fields().getFirst().label()).isEqualTo("Original label");
    assertThat(view.document().fields().getFirst().writePolicy()).isEqualTo(FormFieldWritePolicy.CREATE_ONLY);
    var resolved = resolver.resolve("app-a", "page-a");
    assertThat(resolved.physicalModelVersion()).isEqualTo(1);
    assertThat(resolved.storageChecksum()).isEqualTo("a".repeat(64));
    assertThatThrownBy(() -> new FormRecordPayloadCompiler().compile(resolved.document(),
        Map.of("name-field", "Original"), Map.of("name-field", "Changed"), FormRecordState.DRAFT, FormRecordState.DRAFT))
        .isInstanceOf(ApplicationFormException.class);
    assertThat(codec.decode(session.find(ApplicationFormVersionEntity.class, "version-a").getDocumentJson())
        .fields().getFirst().effectiveWritePolicy()).isEqualTo(FormFieldWritePolicy.EDITABLE);
  }

  @Test
  void missingActivatedPolicyFailsClosedRatherThanReopeningHistoricalWrites() {
    authorize(Set.of("app.runtime.page.one"));
    for (Integer policyVersion : Arrays.asList(null, 0, 2)) {
      session.beginTransaction();
      session.find(ApplicationFormEntity.class, "form-a").setPublishedVersion(policyVersion);
      session.getTransaction().commit();
      assertThatThrownBy(() -> runtime.page("app-a", "page-a"))
          .isInstanceOfSatisfying(ApplicationFormException.class,
              failure -> assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.VERSION_NOT_FOUND));
    }
  }

  private void persistPolicyVersion(final int number, final FormFieldWritePolicy policy, final boolean active) {
    session.beginTransaction();
    var version = new ApplicationFormVersionEntity();
    version.setId("version-" + number);
    version.setTenantId("tenant-a");
    version.setFormId("form-a");
    version.setVersionNumber(number);
    var original = document();
    var encoded = codec.encode(new ApplicationFormDocument("1",
        policyFields(policy, "New label"),
        original.indexes(), original.scenes(), original.rules()));
    version.setDocumentJson(encoded.json());
    version.setChecksum(encoded.checksum());
    version.setStorageSchemaJson("{}");
    version.setStorageChecksum("b".repeat(64));
    version.setPhysicalModelVersion(number);
    version.setRendererVersion("1");
    version.setCreatedAt(Instant.now());
    session.persist(version);
    var form = session.find(ApplicationFormEntity.class, "form-a");
    form.setLatestVersion(number);
    if (active) {
      form.setPublishedVersion(number);
    }
    session.getTransaction().commit();
  }

  private void persistForm(final FormDocumentCodec codec) {
    Instant timestamp = Instant.parse("2026-10-13T00:00:00Z");
    ApplicationFormEntity form = new ApplicationFormEntity();
    form.setId("form-a");
    form.setTenantId("tenant-a");
    form.setApplicationId("app-a");
    form.setCode("orders");
    form.setName("Order form");
    form.setStatus(ApplicationFormStatus.PUBLISHED);
    form.setDraftRevision(1);
    form.setLatestVersion(1);
    form.setPublishedVersion(1);
    form.setPhysicalModelId("model-a");
    form.setCreatedAt(timestamp);
    form.setUpdatedAt(timestamp);
    session.persist(form);

    ApplicationFormVersionEntity version = new ApplicationFormVersionEntity();
    version.setId("version-a");
    version.setTenantId("tenant-a");
    version.setFormId("form-a");
    version.setVersionNumber(1);
    var encoded = codec.encode(document());
    version.setDocumentJson(encoded.json());
    version.setChecksum(encoded.checksum());
    version.setStorageSchemaJson("{}");
    version.setStorageChecksum("a".repeat(64));
    version.setPhysicalModelVersion(1);
    version.setRendererVersion(ApplicationFormApiVersion.RENDERER_V1);
    version.setCreatedAt(timestamp);
    session.persist(version);
  }

  private static ApplicationFormDocument document() {
    return new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        policyFields(FormFieldWritePolicy.EDITABLE, "Original label"),
        List.of(),
        List.of(new FormSceneDefinition(
            "order-main",
            "order-main",
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
                List.of()
            )
        )),
        List.of()
    );
  }

  private static List<FormFieldDefinition> policyFields(final FormFieldWritePolicy policy, final String label) {
    return List.of(new FormFieldDefinition("name-field", "name", label, "Name", FormFieldType.STRING,
        FormStorageMode.STORED, false, false, 120, null, null, null, List.of(), policy));
  }

  private static void authorize(final Set<String> resources) {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("user-a");
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    context.setResources(resources);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }
}
