package org.simplepoint.app.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.app.catalog.entity.WorkspaceApplication;
import org.simplepoint.app.catalog.entity.WorkspaceApplicationPage;
import org.simplepoint.app.catalog.entity.WorkspaceApplicationReadModel;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.ApplicationSummary;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.CreateApplication;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.CreatePage;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.query.InvalidQueryException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;

/** Executes read-model joins and aggregate filters against a real database before pagination. */
class WorkspaceApplicationQueriesTest {
  private static SessionFactory factory;
  private Session session;
  private WorkspaceApplicationCatalog catalog;

  @BeforeAll
  static void database() {
    factory =
        new MetadataSources(
                new StandardServiceRegistryBuilder()
                    .applySetting(
                        "hibernate.connection.url", "jdbc:h2:mem:app-query;DB_CLOSE_DELAY=-1")
                    .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                    .applySetting(
                        "hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                    .build())
            .addAnnotatedClass(WorkspaceApplication.class)
            .addAnnotatedClass(WorkspaceApplicationPage.class)
            .addAnnotatedClass(WorkspaceApplicationReadModel.class)
            .buildMetadata()
            .buildSessionFactory();
  }

  @BeforeEach
  void data() {
    session = factory.openSession();
    session.beginTransaction();
    catalog = new WorkspaceApplicationCatalog(session);
    context("a", Map.of());
    for (int index = 0; index < 28; index++) {
      var app =
          catalog.create(
              new CreateApplication(
                  "App %02d".formatted(index), "Description", "Page %02d".formatted(index)));
      for (int page = 0; page < index % 4; page++) {
        catalog.addPage(app.application().id(), new CreatePage("Child " + page, null));
      }
      session
          .find(WorkspaceApplication.class, app.application().id())
          .setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    }
    catalog.create(new CreateApplication("Literal %_!", null, "Literal Home"));
    context("b", Map.of());
    var foreign =
        catalog.create(new CreateApplication("App foreign", "Description", "Page foreign"));
    for (int index = 0; index < 3; index++) {
      catalog.addPage(foreign.application().id(), new CreatePage("Foreign Child", null));
    }
    session.flush();
    session.clear();
    context("a", Map.of());
  }

  private void context(String tenant, Map<String, String> fields) {
    var context = new AuthorizationContext();
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setUserId("user");
    context.setAttributes(Map.of("X-Tenant-Id", tenant));
    context.setFieldPermissions(fields);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    session.getTransaction().rollback();
    session.close();
  }

  @AfterAll
  static void close() {
    factory.close();
  }

  @Test
  void aggregateAndJoinedTextFiltersAreAppliedBeforeBothCountAndPagination() {
    var filters = Map.of("attachedPageCount", "than:equal:greater:2", "mainPageName", "like:PAGE");
    var first =
        catalog.list(filters, PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "mainPageName")));
    assertThat(first.getTotalElements()).isEqualTo(14);
    assertThat(first.getContent())
        .extracting(ApplicationSummary::mainPageName)
        .containsExactly("Page 27", "Page 26", "Page 23", "Page 22", "Page 19");
    var second =
        catalog.list(filters, PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "mainPageName")));
    assertThat(second.getTotalElements()).isEqualTo(14);
    assertThat(second.getContent())
        .extracting(ApplicationSummary::mainPageName)
        .containsExactly("Page 18", "Page 15", "Page 14", "Page 11", "Page 10");
    var numeric =
        catalog.list(Map.of(), PageRequest.of(0, 10, Sort.by("attachedPageCount", "name")));
    assertThat(numeric.getContent())
        .extracting(ApplicationSummary::attachedPageCount)
        .containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 1L);
  }

  @Test
  void legacyKeywordIsLiteralAndCombinesWithHeaderFilters() {
    assertThat(catalog.list("%_!", 0, 20).getContent())
        .extracting(ApplicationSummary::name)
        .containsExactly("Literal %_!");
    assertThat(
            catalog
                .list(
                    Map.of("keyword", "APP", "attachedPageCount", "equals:3"),
                    PageRequest.of(0, 20))
                .getTotalElements())
        .isEqualTo(7);
    assertThat(
            catalog.list(Map.of("name", "like:foreign"), PageRequest.of(0, 20)).getTotalElements())
        .isZero();
  }

  @Test
  void equalValuesHaveStableNonOverlappingPagesAndUnknownFieldsFailClosed() {
    var first = catalog.list(Map.of("name", "like:App"), PageRequest.of(0, 10)).getContent();
    var next = catalog.list(Map.of("name", "like:App"), PageRequest.of(1, 10)).getContent();
    assertThat(first)
        .extracting(ApplicationSummary::id)
        .doesNotContainAnyElementsOf(next.stream().map(ApplicationSummary::id).toList());
    assertThat(catalog.list(Map.of("name", "like:App"), PageRequest.of(0, 10)).getContent())
        .isEqualTo(first);
    assertThatThrownBy(() -> catalog.list(Map.of("tenantId", "equals:b"), PageRequest.of(0, 20)))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> catalog.list(Map.of(), PageRequest.of(0, 101)))
        .isInstanceOf(InvalidQueryException.class);
  }

  @Test
  void querySchemaAndFieldPermissionsAgreeAndMissingScopeNeverListsAllTenants() {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var schema = json.valueToTree(catalog.schema());
    assertThat(schema.at("/schema/properties/attachedPageCount/type").asText())
        .isEqualTo("integer");
    assertThat(schema.at("/schema/properties/mainPageName/title").asText())
        .isEqualTo("i18n:app.application.mainPageName");
    assertThat(schema.at("/schema/properties/createdBy").isMissingNode()).isTrue();
    context("a", Map.of("ApplicationSummary#description", "HIDDEN"));
    assertThat(
            json.valueToTree(catalog.schema()).at("/schema/properties/description").isMissingNode())
        .isTrue();
    assertThatThrownBy(
            () -> catalog.list(Map.of("description", "like:Description"), PageRequest.of(0, 20)))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> catalog.list("Description", 0, 20))
        .isInstanceOf(InvalidQueryException.class);
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertThatThrownBy(() -> catalog.list(Map.of(), PageRequest.of(0, 20)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void mainPageAndChildCountCannotJoinForeignTenantOrAnotherApplication() {
    var own = catalog.list("App 00", 0, 20).getContent().getFirst();
    context("b", Map.of());
    var foreign = catalog.list("foreign", 0, 20).getContent().getFirst();
    var wrongMain = session.find(WorkspaceApplication.class, own.id());
    wrongMain.setMainPageId(foreign.mainPageId());
    var child =
        session
            .createQuery(
                "from WorkspaceApplicationPage p where p.applicationId = :id and p.kind ="
                    + " 'ATTACHED'",
                WorkspaceApplicationPage.class)
            .setParameter("id", foreign.id())
            .getResultList()
            .getFirst();
    // Deliberately corrupt old data at the SQL boundary; joins must still enforce ownership.
    session.flush();
    session
        .createNativeMutationQuery(
            "update simpoint_app_pages set application_id = :own where id = :child")
        .setParameter("own", own.id())
        .setParameter("child", child.getId())
        .executeUpdate();
    session.clear();
    context("a", Map.of());
    var result = catalog.list("App 00", 0, 20).getContent().getFirst();
    assertThat(result.mainPageName()).isNull();
    assertThat(result.attachedPageCount()).isZero();
  }
}
