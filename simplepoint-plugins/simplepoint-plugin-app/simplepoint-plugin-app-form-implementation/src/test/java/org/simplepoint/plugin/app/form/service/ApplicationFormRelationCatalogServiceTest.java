package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashSet;
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
import org.simplepoint.api.locale.LocalizedText;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTargetMetadata;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplication;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplicationPage;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;

/** Real joined queries prove filtering/count/paging; exact target resolution has separate tests. */
class ApplicationFormRelationCatalogServiceTest {
  private SessionFactory factory;
  private Session session;
  private ApplicationFormService forms;
  private FormRelationTargetResolver targets;
  private ApplicationFormRelationCatalogServiceImpl catalog;
  private AuthorizationContext authorization;

  @BeforeEach
  void setup() {
    factory =
        new MetadataSources(
                new StandardServiceRegistryBuilder()
                    .applySetting(
                        "hibernate.connection.url",
                        "jdbc:h2:mem:relation-catalog-" + UUID.randomUUID())
                    .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                    .build())
            .addAnnotatedClass(WorkspaceApplication.class)
            .addAnnotatedClass(WorkspaceApplicationPage.class)
            .addAnnotatedClass(ApplicationFormEntity.class)
            .addAnnotatedClass(ApplicationFormVersionEntity.class)
            .addAnnotatedClass(ApplicationFormPageBindingEntity.class)
            .buildMetadata()
            .buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    forms = mock(ApplicationFormService.class);
    targets = mock(FormRelationTargetResolver.class);
    var context = new FormRequestContext();
    catalog =
        new ApplicationFormRelationCatalogServiceImpl(
            session, context, forms, targets, new DnaInvocationContextFactory(context, targets));
    authorization = new AuthorizationContext();
    authorization.setUserId("designer");
    authorization.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
    for (int index = 0; index < 65; index++) {
      seed("entry-" + index, "tenant", "Customer " + index, index);
    }
    seed("foreign", "other-tenant", "FOREIGN", 0);
    seed("hidden", "tenant", "HIDDEN", 0);
    seed("offline", "tenant", "OFFLINE", 0);
    seed("retired", "tenant", "RETIRED", 0);
    seed("archived", "tenant", "ARCHIVED", 0);
    seed("unprepared", "tenant", "UNPREPARED", 0);
    session.find(WorkspaceApplicationPage.class, "offline").setStatus("OFFLINING");
    session
        .find(ApplicationFormPageBindingEntity.class, "retired")
        .setStatus(ApplicationFormPageBindingStatus.RETIRED);
    session.find(ApplicationFormEntity.class, "archived").setStatus(ApplicationFormStatus.ARCHIVED);
    session.find(ApplicationFormVersionEntity.class, "unprepared").setPhysicalModelVersion(null);
    var grants = new LinkedHashSet<String>();
    grants.add("app.workbench.view");
    for (int index = 0; index < 65; index++) {
      grants.add("resource-entry-" + index);
    }
    grants.addAll(
        Set.of(
            "resource-foreign",
            "resource-offline",
            "resource-retired",
            "resource-archived",
            "resource-unprepared"));
    authorize(grants, false);
    session.flush();
    session.clear();
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {
      session.getTransaction().rollback();
      session.close();
    }
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void scopesCountAndPagesBeforePagination() {
    var first = catalog.search("source-app", "source-form", "", 0, 50);
    var next = catalog.search("source-app", "source-form", "", 1, 50);
    assertThat(first.total()).isEqualTo(65);
    assertThat(next.total()).isEqualTo(65);
    assertThat(first.records()).hasSize(50);
    assertThat(next.records()).hasSize(15);
    var all = new LinkedHashSet<>(first.records());
    all.addAll(next.records());
    assertThat(all).hasSize(65).allMatch(row -> row.pageName().startsWith("Customer"));
    verify(forms, times(2)).get("source-app", "source-form");
    verifyNoInteractions(targets);
    authorize(Set.of("app.workbench.view", "resource-entry-3"), false);
    var restricted = catalog.search("source-app", "source-form", "", 0, 50);
    assertThat(restricted.total()).isEqualTo(1);
    assertThat(restricted.records().getFirst().target().accessResourceId()).isEqualTo("entry-3");
  }

  @Test
  void keywordSearchIsUnicodeAndLiteralAndResolveKeepsRequestedOrderWithoutLeakingHiddenEntries() {
    session.find(WorkspaceApplicationPage.class, "entry-1").setName("日本客户😀100%_!");
    session.flush();
    assertThat(catalog.search("source-app", "source-form", "%_!", 0, 50).total()).isEqualTo(1);
    assertThat(catalog.search("source-app", "source-form", "日本客户😀", 0, 50).total()).isEqualTo(1);
    var rows =
        catalog.resolve(
            "source-app",
            "source-form",
            List.of("entry-9", "hidden", "entry-1", "entry-9", "foreign", "offline"));
    assertThat(rows)
        .extracting(row -> row.target().accessResourceId())
        .containsExactly("entry-9", "entry-1");
    assertThat(rows.getFirst().pageNameLocalization().translations())
        .containsEntry("en-US", "Customer 9");
  }

  @Test
  void sourceOwnershipAndWorkbenchPermissionAreMandatoryAndAdminStillStaysInTenant() {
    authorize(Set.of("resource-entry-1"), false);
    assertDenied(() -> catalog.search("source-app", "source-form", "", 0, 50));
    verifyNoInteractions(forms);
    authorize(Set.of(), true);
    assertThat(catalog.search("source-app", "source-form", "", 0, 100).total()).isEqualTo(66);
    doThrow(new ApplicationFormException(ApplicationFormErrorCode.FORM_NOT_FOUND, "Missing source"))
        .when(forms)
        .get("other-app", "source-form");
    assertThatThrownBy(() -> catalog.resolve("other-app", "source-form", List.of()))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void invalidBudgetsNeverBecomeUnboundedQueries() {
    assertThatThrownBy(() -> catalog.search("a", "f", "", 0, 101))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> catalog.search("a", "f", "", Integer.MAX_VALUE, 100))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> catalog.search("a", "f", "😀".repeat(201), 0, 50))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(
            () -> catalog.resolve("a", "f", java.util.Collections.nCopies(101, "entry-1")))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> catalog.resolve("a", "f", List.of("../foreign")))
        .isInstanceOf(ApplicationFormException.class);
    verifyNoInteractions(forms, targets);
  }

  @Test
  void describeRejectsStaleOrInaccessibleTargetBeforeReadingItsDefinition() {
    assertDenied(
        () ->
            catalog.describe(
                "source-app",
                "source-form",
                new FormRelationTarget("app-hidden", "hidden", "hidden")));
    assertDenied(
        () ->
            catalog.describe(
                "source-app",
                "source-form",
                new FormRelationTarget("wrong-app", "entry-1", "entry-1")));
    assertDenied(
        () ->
            catalog.describe(
                "source-app",
                "source-form",
                new FormRelationTarget("app-archived", "archived", "archived")));
    verifyNoInteractions(targets);
  }

  @Test
  void descriptorExcludesHiddenMaskedTransientAndJsonFieldsAndNeverExposesStorage()
      throws Exception {
    var identity = new FormRelationTarget("app-entry-1", "entry-1", "entry-1");
    var fields =
        List.of(
            field("name", FormFieldType.STRING, FormStorageMode.STORED),
            field("amount", FormFieldType.DECIMAL, FormStorageMode.STORED),
            field("hidden", FormFieldType.STRING, FormStorageMode.STORED),
            field("masked", FormFieldType.STRING, FormStorageMode.STORED),
            field("json", FormFieldType.JSON, FormStorageMode.STORED),
            field("temporary", FormFieldType.STRING, FormStorageMode.TRANSIENT));
    var document = new ApplicationFormDocument("1", fields, List.of(), List.of(), List.of());
    var target =
        new PublishedApplicationForm(
            "tenant",
            "app-entry-1",
            "entry-1",
            "Customer",
            "entry-1",
            "Customer",
            1,
            "resource-entry-1",
            "physical-model-secret",
            1,
            "a".repeat(64),
            document);
    when(targets.describeTarget(identity)).thenReturn(target);
    authorization.setResourceFieldPermissions(
        Map.of(
            "resource-entry-1",
            Map.of(
                RecordPermissionModel.key("physical-model-secret")
                        + "#"
                        + RecordPermissionModel.field("hidden"),
                    "HIDDEN",
                RecordPermissionModel.key("physical-model-secret")
                        + "#"
                        + RecordPermissionModel.field("masked"),
                    "MASKED")));
    installAuthorization();
    var result = catalog.describe("source-app", "source-form", identity);
    assertThat(result.fields())
        .extracting(FormRelationTargetMetadata.Field::fieldId)
        .containsExactly("name", "amount");
    assertThat(result.fields())
        .extracting(FormRelationTargetMetadata.Field::searchable)
        .containsExactly(true, false);
    assertThat(new ObjectMapper().writeValueAsString(result))
        .doesNotContain("physical-model-secret", "storage_", "temporary", "masked", "hidden");
    when(targets.describeTarget(identity))
        .thenThrow(
            new ApplicationFormException(
                ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "Revoked"));
    assertDenied(() -> catalog.describe("source-app", "source-form", identity));
  }

  @Test
  void inverseChoicesExposeOnlyReadableOwningRelationsBackToTheDesignedForm() throws Exception {
    var identity = new FormRelationTarget("app-entry-1", "entry-1", "entry-1");
    var parent = new FormRelationTarget("source-app", "source-form", "source-resource");
    var visible =
        new org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition(
            "1",
            "visible",
            org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality.MANY_TO_ONE,
            new org.simplepoint.plugin.app.form.api.model.relation.FormRelationField(
                null, "customer"),
            parent);
    var hidden =
        new org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition(
            "1",
            "secret",
            visible.cardinality(),
            new org.simplepoint.plugin.app.form.api.model.relation.FormRelationField(
                null, "private"),
            parent);
    var unrelated =
        new org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition(
            "1",
            "unrelated",
            visible.cardinality(),
            new org.simplepoint.plugin.app.form.api.model.relation.FormRelationField(null, "other"),
            new FormRelationTarget("another-app", "another-form", "another-resource"));
    var document =
        new ApplicationFormDocument(
            "1",
            List.of(
                field("customer", FormFieldType.UUID, FormStorageMode.REFERENCE),
                field("private", FormFieldType.UUID, FormStorageMode.REFERENCE),
                field("other", FormFieldType.UUID, FormStorageMode.REFERENCE)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            List.of(visible, hidden, unrelated));
    var source =
        new PublishedApplicationForm(
            "tenant",
            identity.applicationId(),
            "page",
            "交易",
            identity.formId(),
            "交易",
            2,
            "resource-entry-1",
            "physical-model-secret",
            1,
            "a".repeat(64),
            document);
    var target =
        new PublishedApplicationForm(
            "tenant",
            "source-app",
            "parent-page",
            "客户",
            "source-form",
            "客户",
            1,
            "parent-resource",
            "parent-model",
            1,
            "b".repeat(64),
            new ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of()));
    when(targets.describeTarget(identity)).thenReturn(source);
    when(targets.resolve(source, "visible"))
        .thenReturn(new FormRelationTargetResolver.ResolvedRelation(visible, target));
    when(targets.resolve(source, "secret"))
        .thenReturn(new FormRelationTargetResolver.ResolvedRelation(hidden, target));
    authorization.setResourceFieldPermissions(
        Map.of(
            "resource-entry-1",
            Map.of(
                RecordPermissionModel.key("physical-model-secret")
                    + "#"
                    + RecordPermissionModel.field("private"),
                "MASKED")));
    installAuthorization();
    var result = catalog.inverseSource("source-app", "source-form", identity);
    assertThat(result.relations())
        .extracting(value -> value.relationId())
        .containsExactly("visible");
    assertThat(result.source()).isEqualTo(identity);
    assertThat(result.formVersion()).isEqualTo(2);
    assertThat(new ObjectMapper().writeValueAsString(result))
        .doesNotContain(
            "physical-model-secret", "storage_", "private", "unrelated", "parent-model");
    assertDenied(
        () ->
            catalog.inverseSource(
                "source-app",
                "source-form",
                new FormRelationTarget("app-foreign", "foreign", "foreign")));
    verify(targets, org.mockito.Mockito.never()).resolve(source, "unrelated");
  }

  private static FormFieldDefinition field(String id, FormFieldType type, FormStorageMode mode) {
    return new FormFieldDefinition(
        id, "storage_" + id, id, id, type, mode, false, false, null, null, null, null, List.of());
  }

  private void installAuthorization() {
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
  }

  private void authorize(Set<String> resources, boolean administrator) {
    authorization = new AuthorizationContext();
    authorization.setUserId("designer");
    authorization.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    authorization.setResources(resources);
    authorization.setIsAdministrator(administrator);
    installAuthorization();
  }

  private void seed(String id, String tenant, String name, int ordinal) {
    var now = Instant.parse("2026-10-27T00:00:00Z");
    var app = new WorkspaceApplication();
    app.setId("app-" + id);
    app.setTenantId(tenant);
    app.setName("Business");
    app.setMainPageId(id);
    app.setStatus("PUBLISHED");
    app.setCreatedAt(now);
    app.setUpdatedAt(now);
    session.persist(app);
    var form = new ApplicationFormEntity();
    form.setId(id);
    form.setApplicationId(app.getId());
    form.setTenantId(tenant);
    form.setCode("form");
    form.setName(name);
    form.setStatus(ApplicationFormStatus.PUBLISHED);
    form.setPublishedVersion(1);
    form.setLatestVersion(1);
    form.setPhysicalModelId("model-" + id);
    form.setCreatedAt(now);
    form.setUpdatedAt(now);
    session.persist(form);
    var version = new ApplicationFormVersionEntity();
    version.setId(id);
    version.setTenantId(tenant);
    version.setFormId(id);
    version.setVersionNumber(1);
    version.setDocumentJson("{}");
    version.setChecksum("a".repeat(64));
    version.setStorageSchemaJson("{}");
    version.setStorageChecksum("a".repeat(64));
    version.setPhysicalModelVersion(1);
    version.setRendererVersion("1");
    version.setCreatedAt(now);
    session.persist(version);
    var page = new WorkspaceApplicationPage();
    page.setId(id);
    page.setTenantId(tenant);
    page.setApplicationId(app.getId());
    page.setName(name);
    page.setNameLocalization(new LocalizedText(name, "zh-CN", Map.of("en-US", name)));
    page.setKind("MAIN");
    page.setStatus("PUBLISHED");
    page.setSortOrder(ordinal);
    page.setFormId(id);
    page.setFormVersion(1);
    page.setResourceId(id);
    page.setResourceCode("resource-" + id);
    page.setCreatedAt(now);
    page.setUpdatedAt(now);
    session.persist(page);
    var binding = new ApplicationFormPageBindingEntity();
    binding.setId(id);
    binding.setTenantId(tenant);
    binding.setApplicationId(app.getId());
    binding.setPageId(id);
    binding.setFormId(id);
    binding.setFormVersion(1);
    binding.setSceneId("main");
    binding.setIdempotencyKey(id);
    binding.setGeneration(1);
    binding.setPolicyProtocolVersion(1);
    binding.setStatus(ApplicationFormPageBindingStatus.ACTIVE);
    binding.setResourceManagedKey(id);
    binding.setResourceId(id);
    binding.setResourceCode("resource-" + id);
    binding.setCreatedAt(now);
    binding.setUpdatedAt(now);
    session.persist(binding);
  }

  private static void assertDenied(Runnable task) {
    assertThatThrownBy(task::run)
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED));
  }
}
