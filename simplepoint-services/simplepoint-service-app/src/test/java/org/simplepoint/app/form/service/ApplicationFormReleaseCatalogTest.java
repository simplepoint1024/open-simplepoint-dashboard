package org.simplepoint.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
import org.simplepoint.app.catalog.entity.WorkspaceApplicationPage;
import org.simplepoint.app.form.entity.ApplicationFormReleaseReadModel;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPublicationEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormWritePolicyPublicationEntity;
import org.simplepoint.plugin.app.form.service.FormRequestContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Exercises the joined publication projection before pagination and permission filtering. */
class ApplicationFormReleaseCatalogTest {
  private SessionFactory factory;
  private Session session;
  private ApplicationFormService forms;
  private ApplicationFormReleaseCatalog catalog;

  @BeforeEach
  void setUp() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:release-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .build())
        .addAnnotatedClass(WorkspaceApplicationPage.class)
        .addAnnotatedClass(ApplicationFormPageBindingEntity.class)
        .addAnnotatedClass(ApplicationFormPublicationEntity.class)
        .addAnnotatedClass(ApplicationFormWritePolicyPublicationEntity.class)
        .addAnnotatedClass(ApplicationFormReleaseReadModel.class)
        .buildMetadata().buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    forms = mock(ApplicationFormService.class);
    catalog = new ApplicationFormReleaseCatalog(session, forms, new FormRequestContext());
    authorize(Set.of());
    for (int index = 1; index <= 12; index++) {
      seed("own-" + index, "tenant-a", "app-a", "form-a", index);
    }
    seed("foreign", "tenant-b", "app-a", "form-a", 1);
    seed("other-app", "tenant-a", "app-b", "form-a", 1);
    seed("other-form", "tenant-a", "app-a", "form-b", 1);
    session.flush();
    session.clear();
  }

  @AfterEach
  void close() {
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
  void listsIntentsBeforeDnaExistsAndFiltersBothCountAndRowsByOwnership() {
    var first = catalog.page("app-a", "form-a", Map.of(), PageRequest.of(0, 5, Sort.by("formVersion")));
    var next = catalog.page("app-a", "form-a", Map.of(), PageRequest.of(1, 5, Sort.by("formVersion")));
    assertThat(first.getTotalElements()).isEqualTo(12);
    assertThat(first.getContent()).extracting(ApplicationFormReleaseCatalog.ReleaseRow::formVersion)
        .containsExactly(1, 2, 3, 4, 5);
    assertThat(next.getContent()).extracting(ApplicationFormReleaseCatalog.ReleaseRow::formVersion)
        .containsExactly(6, 7, 8, 9, 10);
    assertThat(first.getContent()).allSatisfy(row -> {
      assertThat(row.status()).isEqualTo("DATA_PREPARING");
      assertThat(row.errorCode()).isNull();
    });
    assertThat(catalog.page("app-a", "form-a", Map.of("status", "equals:ACTIVE"),
        PageRequest.of(0, 10)).getTotalElements()).isZero();
    assertThat(catalog.page("app-a", "form-a", Map.of("pageName", "equals:own-9"),
        PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    verify(forms, org.mockito.Mockito.atLeastOnce()).get("app-a", "form-a");
  }

  @Test
  void cancellationCapabilitiesRequireAuthorityAndSafePublicationStage() {
    var page = PageRequest.of(0, 20, Sort.by("formVersion"));
    assertThat(catalog.page("app-a", "form-a", Map.of(), page)).allMatch(row -> !row.canCancel());
    authorize(Set.of("app.workbench.forms.publish"));
    assertThat(catalog.page("app-a", "form-a", Map.of(), page)).allMatch(ApplicationFormReleaseCatalog.ReleaseRow::canCancel);
    var legacy = session.find(ApplicationFormPageBindingEntity.class, "own-1");
    legacy.setAttempt(1);
    legacy.setStatus(ApplicationFormPageBindingStatus.FAILED);
    session.find(ApplicationFormPageBindingEntity.class, "own-2").setStatus(ApplicationFormPageBindingStatus.CANCELLED);
    session.flush();
    session.clear();
    var rows = catalog.page("app-a", "form-a", Map.of(), page).getContent();
    assertThat(rows.get(0).canCancel()).isFalse();
    assertThat(rows.get(1).canCancel()).isFalse();
    assertThat(rows.get(2).canCancel()).isTrue();
  }

  @Test
  void schemaUsesServerPublishPermissionAndDoesNotExposeRecoverySecrets() {
    var json = new ObjectMapper();
    var readonly = json.valueToTree(catalog.schema("app-a", "form-a"));
    assertThat(readonly.at("/buttons")).hasSize(1);
    assertThat(readonly.at("/buttons/0/key").asText()).isEqualTo("data");
    authorize(Set.of("app.workbench.forms.publish"));
    var writable = json.valueToTree(catalog.schema("app-a", "form-a"));
    assertThat(writable.at("/buttons/0/key").asText()).isEqualTo("resume");
    assertThat(writable.at("/buttons/1/key").asText()).isEqualTo("cancel");
    assertThat(writable.toString()).doesNotContain("idempotencyKey", "executionToken", "physicalModelId");
    assertThatThrownBy(() -> catalog.page("app-a", "form-a", Map.of("tenantId", "equals:tenant-b"),
        PageRequest.of(0, 10))).isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> catalog.page("app-a", "form-a", Map.of(), PageRequest.of(0, 101)))
        .isInstanceOf(InvalidQueryException.class);
  }

  private void seed(String id, String tenant, String app, String form, int version) {
    Instant now = Instant.parse("2026-10-13T00:00:00Z");
    var page = new WorkspaceApplicationPage();
    page.setId("page-" + id);
    page.setTenantId(tenant);
    page.setApplicationId(app);
    page.setKind("MAIN");
    page.setName(id);
    page.setStatus("DRAFT");
    page.setSortOrder(0);
    page.setCreatedAt(now);
    page.setUpdatedAt(now);
    session.persist(page);
    var binding = new ApplicationFormPageBindingEntity();
    binding.setId(id);
    binding.setTenantId(tenant);
    binding.setApplicationId(app);
    binding.setFormId(form);
    binding.setPageId(page.getId());
    binding.setFormVersion(version);
    binding.setSceneId("main");
    binding.setIdempotencyKey("key-" + id);
    binding.setResourceManagedKey("page:" + page.getId());
    binding.setStatus(ApplicationFormPageBindingStatus.DATA_PREPARING);
    binding.setCreatedAt(now);
    binding.setUpdatedAt(now);
    session.persist(binding);
  }

  private void authorize(Set<String> resources) {
    var actor = new AuthorizationContext();
    actor.setUserId("user-a");
    actor.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    actor.setResources(resources);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, actor);
  }
}
