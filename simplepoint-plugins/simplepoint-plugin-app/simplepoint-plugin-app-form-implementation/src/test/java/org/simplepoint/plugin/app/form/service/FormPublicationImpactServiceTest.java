package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationPageTarget;
import org.simplepoint.plugin.app.form.api.model.FormChoiceOption;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.service.ApplicationPageAccessPort;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormWritePolicyPublicationEntity;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;

/** Exercises actual metadata queries and the atomic recheck before freezing a target command. */
class FormPublicationImpactServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  private static final BindingWork WORK = new BindingWork("candidate", "attempt", "tenant-a", "app-a", "page-a", "form-a", 2,
      "model-a", "page:page-a", "Orders", null, 10, 2);
  private final FormDocumentCodec codec = new FormDocumentCodec(new ObjectMapper());
  private final FormRequestContext context = new FormRequestContext();
  private SessionFactory factory;
  private Session session;

  @BeforeEach
  void setUp() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:form-impact-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
    factory = new MetadataSources(registry).addAnnotatedClass(ApplicationFormEntity.class)
        .addAnnotatedClass(ApplicationFormVersionEntity.class).addAnnotatedClass(ApplicationFormPageBindingEntity.class)
        .addAnnotatedClass(ApplicationFormWritePolicyPublicationEntity.class).buildMetadata().buildSessionFactory();
    session = factory.openSession();
    authorize("tenant-a");
    transaction(() -> {
      var form = new ApplicationFormEntity();
      form.setId("form-a");
      form.setTenantId("tenant-a");
      form.setApplicationId("app-a");
      form.setCode("orders");
      form.setName("Orders");
      form.setStatus(ApplicationFormStatus.PUBLISHED);
      form.setDraftRevision(1);
      form.setLatestVersion(2);
      form.setPublishedVersion(1);
      form.setWritePolicyVersion(7);
      form.setPhysicalModelId("model-a");
      form.setCreatedAt(NOW);
      form.setUpdatedAt(NOW);
      session.persist(form);
      seedVersion(1, true, FormFieldWritePolicy.EDITABLE);
      seedVersion(2, false, FormFieldWritePolicy.CREATE_ONLY);
      seedBinding("old-a", "page-a", 1, 1, ApplicationFormPageBindingStatus.ACTIVE);
      seedBinding("old-b", "page-b", 1, 1, ApplicationFormPageBindingStatus.ACTIVE);
      seedBinding("candidate", "page-a", 2, 2, ApplicationFormPageBindingStatus.ACTIVATING);
      return null;
    });
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
  void combinesCandidateWithSurvivingPagesAndPersistsTheExactObservedPlan() {
    var impact = transaction(() -> impacts().requirements(WORK));
    assertThat(impact.expectedVersion()).isEqualTo(7);
    assertThat(impact.governingVersion()).isEqualTo(2);
    assertThat(impact.activeBindingIds()).containsExactlyInAnyOrder("old-a", "old-b");
    assertThat(impact.checks()).hasSize(1);
    assertThat(impact.valueChecks()).hasSize(1);
    var check = impact.checks().values().iterator().next();
    assertThat(check.fields()).containsExactly("column_name");
    assertThat(check.filter()).isNull(); // The surviving old page now applies CREATE_ONLY to its required field.
    var prepared = transaction(() -> policies().prepare(WORK, impact));
    assertThat(prepared.command().checks()).isEqualTo(impact.checks());
    assertThat(prepared.command().valueChecks()).isEqualTo(impact.valueChecks());
    assertThat(prepared.command().expectedVersion()).isEqualTo(7);
    assertThat(prepared.command().targetVersion()).isEqualTo(8);
    session.close();
    session = factory.openSession();
    assertThat(transaction(() -> policies().prepare(WORK, null)).command()).isEqualTo(prepared.command());
  }

  @Test
  void savedInspectionUsesExactCountsAndOnlyOffersAnAuthorizedActiveRepairPage() {
    var impact = transaction(() -> impacts().requirements(WORK));
    transaction(() -> policies().prepare(WORK, impact));
    String required = impact.checks().keySet().iterator().next();
    String choice = impact.valueChecks().keySet().iterator().next();
    var result = new org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationView(false, 7, "a".repeat(64),
        Map.of(required, new org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessView(9007199254740993L,
            Map.of("column_name", 9007199254740993L))), Map.of(choice, 3L));
    transaction(() -> policies().recordResult(WORK, result));
    var report = transaction(() -> new FormPublicationInspectionService(session, context, codec, new ObjectMapper())
        .get("app-a", "form-a", "candidate"));
    assertThat(report.issues()).hasSize(2);
    assertThat(report.issues().getFirst().count()).isEqualTo("9007199254740993");
    assertThat(report.issues().getFirst().fields()).containsExactly("Name");
    assertThat(report.repairPageId()).isNull();
    transaction(() -> {
      session.find(ApplicationFormPageBindingEntity.class, "old-b").setResourceCode("runtime.b");
      return null;
    });
    org.simplepoint.core.AuthorizationContextHolder.getContext().setResources(java.util.Set.of("runtime.b", "runtime.b.records.edit"));
    assertThat(transaction(() -> new FormPublicationInspectionService(session, context, codec, new ObjectMapper())
        .get("app-a", "form-a", "candidate")).repairPageId()).isEqualTo("page-b");
    transaction(() -> policies().requestCancellation(WORK));
    assertThat(transaction(() -> new FormPublicationInspectionService(session, context, codec, new ObjectMapper())
        .get("app-a", "form-a", "candidate"))).isNull();
    authorize("tenant-b");
    assertThat(transaction(() -> new FormPublicationInspectionService(session, context, codec, new ObjectMapper())
        .get("app-a", "form-a", "candidate"))).isNull();
  }

  @Test
  void runtimeRequirementsIncludeOtherActivePagesAndRejectStaleAdmission() {
    transaction(() -> {
      session.find(ApplicationFormEntity.class, "form-a").setPublishedVersion(2);
      session.createNativeMutationQuery("update simpoint_app_form_page_bindings set form_version = 2 where id = 'old-b'").executeUpdate();
      session.flush();
      session.clear();
      return null;
    });
    var document = transaction(() -> codec.decode(session.find(ApplicationFormVersionEntity.class, "version-2").getDocumentJson()));
    var runtime = new PublishedApplicationFormResolver.PublishedApplicationForm("tenant-a", "app-a", "page-b", "Orders", "form-a",
        "Orders", 2, "runtime.b", "model-a", 1, "b".repeat(64), document, 2, 7);
    var plan = transaction(() -> new FormRecordInvariantService(session, codec, context).resolve(runtime));
    assertThat(plan.documents()).hasSize(1);
    assertThatThrownBy(() -> plan.validate(new FormRecordPayloadCompiler(), plan.createDefaults(), Map.of(), null,
        org.simplepoint.plugin.app.form.api.model.FormRecordState.DRAFT)).isInstanceOf(ApplicationFormException.class);
    plan.validate(new FormRecordPayloadCompiler(), plan.createDefaults(), Map.of("name", "active"), null,
        org.simplepoint.plugin.app.form.api.model.FormRecordState.DRAFT);
    transaction(() -> {
      session.find(ApplicationFormEntity.class, "form-a").setWritePolicyVersion(8);
      return null;
    });
    assertThatThrownBy(() -> transaction(() -> new FormRecordInvariantService(session, codec, context).resolve(runtime)))
        .isInstanceOfSatisfying(ApplicationFormException.class, failure ->
            assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.RUNTIME_VERSION_CONFLICT));
  }

  @Test
  void excludesTheReplacedPageAndRetiredPagesFromRequirementsButObservesTheReplacedBinding() {
    transaction(() -> {
      session.find(ApplicationFormPageBindingEntity.class, "old-b").setStatus(ApplicationFormPageBindingStatus.RETIRED);
      return null;
    });
    var impact = transaction(() -> impacts().requirements(WORK));
    assertThat(impact.activeBindingIds()).containsExactly("old-a");
    assertThat(impact.checks()).isEmpty(); // The optional candidate replaces the only surviving required page.
  }

  @Test
  void rejectsAnObservedPlanAfterAnotherPageBecomesActiveAndCanReanalyze() {
    var stale = transaction(() -> impacts().requirements(WORK));
    transaction(() -> {
      seedBinding("old-c", "page-c", 1, 1, ApplicationFormPageBindingStatus.ACTIVE);
      return null;
    });
    assertThatThrownBy(() -> transaction(() -> policies().prepare(WORK, stale)))
        .isInstanceOfSatisfying(ApplicationFormException.class, failure ->
            assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS));
    var current = transaction(() -> impacts().requirements(WORK));
    assertThat(current.activeBindingIds()).containsExactlyInAnyOrder("old-a", "old-b", "old-c");
    assertThat(current.checks()).hasSize(1);
    assertThat(transaction(() -> policies().prepare(WORK, current)).command().checks()).isEqualTo(current.checks());
  }

  @Test
  void rejectsStaleAttemptsAndCrossTenantRequests() {
    transaction(() -> {
      session.find(ApplicationFormPageBindingEntity.class, "candidate").setExecutionToken("new-attempt");
      return null;
    });
    assertThatThrownBy(() -> transaction(() -> impacts().requirements(WORK)))
        .isInstanceOfSatisfying(ApplicationFormException.class, failure ->
            assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS));
    authorize("tenant-b");
    assertThatThrownBy(() -> transaction(() -> impacts().requirements(WORK)))
        .isInstanceOfSatisfying(ApplicationFormException.class, failure ->
            assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.PUBLICATION_NOT_FOUND));
  }

  private FormPublicationImpactService impacts() {
    return new FormPublicationImpactService(session, codec, context);
  }

  private FormWritePolicyPublicationStateService policies() {
    var pages = mock(ApplicationPageAccessPort.class);
    when(pages.requirePageForUpdate("tenant-a", "app-a", "page-a"))
        .thenReturn(new ApplicationPageTarget("page-a", "Orders", null, 10));
    return new FormWritePolicyPublicationStateService(session, pages, context, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void seedVersion(final int number, final boolean required, final FormFieldWritePolicy policy) {
    var field = new FormFieldDefinition("name", "column_name", "Name", "Name", FormFieldType.STRING, FormStorageMode.STORED,
        required, false, 128, null, null, null, number == 2 ? List.of(new FormChoiceOption("active", "Active")) : List.of(), policy);
    var input = new FormComponentDefinition("input", "field.text", "1", "name", 24,
        null, null, null, null, false, Map.of(), List.of());
    var root = new FormComponentDefinition("root", "layout.form", "1", null, 24,
        null, null, null, null, false, Map.of(), List.of(input));
    var main = new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root);
    var encoded = codec.encode(new ApplicationFormDocument("1", List.of(field), List.of(), List.of(main), List.of()));
    var version = new ApplicationFormVersionEntity();
    version.setId("version-" + number);
    version.setTenantId("tenant-a");
    version.setFormId("form-a");
    version.setVersionNumber(number);
    version.setDocumentJson(encoded.json());
    version.setChecksum(encoded.checksum());
    version.setStorageSchemaJson("{}");
    version.setStorageChecksum("b".repeat(64));
    version.setPhysicalModelVersion(1);
    version.setRendererVersion("1");
    version.setCreatedAt(NOW);
    session.persist(version);
  }

  private void seedBinding(final String id, final String page, final int version, final long generation,
      final ApplicationFormPageBindingStatus status) {
    var binding = new ApplicationFormPageBindingEntity();
    binding.setId(id);
    binding.setTenantId("tenant-a");
    binding.setApplicationId("app-a");
    binding.setPageId(page);
    binding.setFormId("form-a");
    binding.setFormVersion(version);
    binding.setSceneId("main");
    binding.setIdempotencyKey(id);
    binding.setGeneration(generation);
    binding.setStatus(status);
    binding.setResourceManagedKey("page:" + page);
    binding.setExecutionToken(status == ApplicationFormPageBindingStatus.ACTIVATING ? "attempt" : null);
    binding.setCreatedAt(NOW);
    binding.setUpdatedAt(NOW);
    session.persist(binding);
  }

  private <T> T transaction(final java.util.concurrent.Callable<T> work) {
    session.beginTransaction();
    try {
      T result = work.call();
      session.getTransaction().commit();
      session.clear();
      return result;
    } catch (RuntimeException exception) {
      session.getTransaction().rollback();
      session.clear();
      throw exception;
    } catch (Exception exception) {
      session.getTransaction().rollback();
      session.clear();
      throw new IllegalStateException(exception);
    }
  }

  private static void authorize(final String tenant) {
    var authorization = new AuthorizationContext();
    authorization.setUserId("user-a");
    authorization.setAttributes(Map.of("X-Tenant-Id", tenant));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
  }
}
