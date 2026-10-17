package org.simplepoint.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
import org.simplepoint.app.catalog.entity.WorkspaceApplication;
import org.simplepoint.app.catalog.entity.WorkspaceApplicationPage;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStep;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPublicationEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.service.FormDocumentCodec;
import org.simplepoint.plugin.app.form.service.FormRequestContext;

/** Executes catalog HQL against persistence with tenant, active-version and resource boundaries. */
class ApplicationFormWorkflowCatalogTest {
  private SessionFactory factory;
  private Session session;
  private ApplicationFormWorkflowCatalog catalog;
  private FormDocumentCodec documents;
  private final java.util.ArrayList<String> statements = new java.util.ArrayList<>();

  @BeforeEach
  void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:form-catalog-" + UUID.randomUUID())
        .applySetting("hibernate.session_factory.statement_inspector", (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> {
          statements.add(sql); return sql;
        })
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build())
        .addAnnotatedClass(WorkspaceApplication.class).addAnnotatedClass(WorkspaceApplicationPage.class)
        .addAnnotatedClass(ApplicationFormEntity.class).addAnnotatedClass(ApplicationFormVersionEntity.class)
        .addAnnotatedClass(ApplicationFormPublicationEntity.class)
        .addAnnotatedClass(org.simplepoint.plugin.app.form.entity.ApplicationFormSubmissionEntity.class)
        .addAnnotatedClass(org.simplepoint.app.form.entity.ApplicationFormSubmissionReadModel.class)
        .buildMetadata().buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    documents = new FormDocumentCodec(new ObjectMapper().findAndRegisterModules());
    catalog = new ApplicationFormWorkflowCatalog(session, new FormRequestContext(), documents);
    authorize(Set.of());
    seed("visible", "tenant");
    seed("foreign", "other-tenant");
    session.flush(); session.clear();
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {session.getTransaction().rollback(); session.close();}
    if (factory != null) factory.close();
  }

  @Test
  void submissionHistoryFiltersActorTenantAndPageBeforePaginationWithoutLoadingPayloads() {
    seedSubmission("a", "tenant", "actor", "page-visible");
    seedSubmission("b", "tenant", "other-actor", "page-visible");
    seedSubmission("c", "other-tenant", "actor", "page-visible");
    seedSubmission("d", "tenant", "actor", "another-page");
    session.flush(); session.clear(); statements.clear();
    var resolver = mock(org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.class);
    when(resolver.capabilities(null)).thenReturn(new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities(false, false, false, true));
    var submissions = new ApplicationFormSubmissionCatalog(session, resolver, new FormRequestContext());
    var page = submissions.page("app-visible", "page-visible", Map.of(), org.springframework.data.domain.PageRequest.of(0, 1));
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent()).extracting(ApplicationFormSubmissionCatalog.SubmissionRow::id).containsExactly("a".repeat(64));
    assertThat(page.getContent().getFirst().canRetry()).isTrue();
    assertThat(page.getContent().getFirst().canSnapshot()).isTrue();
    assertThat(statements).noneMatch(sql -> sql.contains("intent_json"));
    assertThat(submissions.page("app-visible", "page-visible", Map.of("status", "equals:SUCCEEDED"),
        org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements()).isZero();
  }

  @Test
  void publishesOnlyCurrentVersionChoicesButCanHydrateAnAuthorizedHistoricalBinding() {
    var choices = catalog.forms("", 0, 20);
    assertThat(choices.getContent()).extracting(ApplicationFormWorkflowCatalog.Choice::id).containsExactly("visible:2");
    assertThat(choices.getTotalElements()).isEqualTo(1);
    assertThat(catalog.forms("%", 0, 20).getTotalElements()).isZero();
    assertThat(catalog.options(List.of("visible:1"))).extracting(ApplicationFormWorkflowCatalog.Choice::version).containsExactly(1);
    assertThatThrownBy(() -> catalog.detail("foreign:1")).isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    assertThatThrownBy(() -> catalog.detail("visible:3")).isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
  }

  @Test
  void runtimeChoicesRequireBothPageReadAndStartAndNeverExposeAnotherTenant() {
    assertThat(catalog.pages("visible", 2, "", 0, 20).getTotalElements()).isZero();
    authorize(Set.of("page.visible"));
    assertThat(catalog.pages("visible", 2, "", 0, 20).getTotalElements()).isZero();
    authorize(Set.of("page.visible", "page.visible.process.start", "page.foreign", "page.foreign.process.start"));
    assertThat(catalog.pages("visible", 2, "", 0, 20).getContent()).extracting(ApplicationFormWorkflowCatalog.RuntimePage::id)
        .containsExactly("page-visible");
    assertThat(catalog.pages("visible", 1, "", 0, 20).getTotalElements()).isZero();
    assertThat(catalog.pages("foreign", 2, "", 0, 20).getTotalElements()).isZero();
  }

  private void seed(String id, String tenant) {
    var now = Instant.now();
    var app = new WorkspaceApplication(); app.setId("app-" + id); app.setTenantId(tenant); app.setName("业务 " + id);
    app.setMainPageId("page-" + id); app.setStatus("ACTIVE"); app.setCreatedAt(now); app.setUpdatedAt(now); session.persist(app);
    var form = new ApplicationFormEntity(); form.setId(id); form.setTenantId(tenant); form.setApplicationId(app.getId());
    form.setCode(id); form.setName("申请 " + id); form.setStatus(ApplicationFormStatus.PUBLISHED);
    form.setCreatedAt(now); form.setUpdatedAt(now); session.persist(form);
    for (int version = 1; version <= 2; version++) {
      var row = new ApplicationFormVersionEntity(); row.setId(id + "-v" + version); row.setTenantId(tenant); row.setFormId(id);
      row.setVersionNumber(version); row.setPhysicalModelVersion(version); row.setRendererVersion("1"); row.setCreatedAt(now);
      row.setChecksum("a".repeat(64)); row.setStorageChecksum("b".repeat(64)); row.setStorageSchemaJson("{}");
      row.setDocumentJson(documents.encode(new ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of())).json());
      session.persist(row);
      var publication = new ApplicationFormPublicationEntity(); publication.setId(id + "-pub" + version);
      publication.setTenantId(tenant); publication.setFormId(id); publication.setFormVersion(version);
      publication.setIdempotencyKey(id + "-key" + version); publication.setStatus(version == 1 ? ApplicationFormPublicationStatus.RETIRED : ApplicationFormPublicationStatus.ACTIVE);
      publication.setStep(ApplicationFormPublicationStep.ACTIVATE); publication.setCreatedAt(now); publication.setUpdatedAt(now); session.persist(publication);
    }
    var page = new WorkspaceApplicationPage(); page.setId("page-" + id); page.setTenantId(tenant); page.setApplicationId(app.getId());
    page.setKind("MAIN"); page.setName("申请 " + id); page.setStatus("PUBLISHED"); page.setResourceCode("page." + id);
    page.setFormId(id); page.setFormVersion(2); page.setCreatedAt(now); page.setUpdatedAt(now); session.persist(page);
  }

  private void seedSubmission(String id, String tenant, String actor, String page) {
    var row = new org.simplepoint.plugin.app.form.entity.ApplicationFormSubmissionEntity();
    row.setId(id.repeat(64)); row.setTenantId(tenant); row.setApplicationId("app-visible"); row.setPageId(page);
    row.setFormId("visible"); row.setFormVersion(2); row.setActorId(actor); row.setRequestFingerprint("f".repeat(64));
    row.setIntentJson("{\"private\":true}"); row.setSnapshotJson("{\"private\":true}"); row.setTitle("申请 " + id);
    row.setDefinitionId("workflow"); row.setDefinitionVersion(1); row.setStatus("RETRY"); row.setStep("DATA_COMMITTED");
    row.setCreatedAt(Instant.now()); row.setUpdatedAt(Instant.now()); row.setNextAttemptAt(Instant.now()); session.persist(row);
  }

  private static void authorize(Set<String> resources) {
    var authority = new AuthorizationContext(); authority.setUserId("actor"); authority.setIsAdministrator(false);
    authority.setScopeType(AuthorizationScopeType.TENANT); authority.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    authority.setResources(resources); RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authority);
  }
}
