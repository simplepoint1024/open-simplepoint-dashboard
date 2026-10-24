package org.simplepoint.app.catalog.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.api.locale.LocalizedText;
import org.simplepoint.core.*;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationNavigation;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplication;
import org.simplepoint.plugin.app.form.entity.WorkspaceApplicationPage;

class ApplicationNavigationStateTest {
  private SessionFactory factory;
  private Session session;
  private ApplicationNavigationState state;

  @BeforeEach
  void setup() {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", "jdbc:h2:mem:navigation-" + UUID.randomUUID())
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .build();
    factory =
        new MetadataSources(registry)
            .addAnnotatedClass(WorkspaceApplication.class)
            .addAnnotatedClass(WorkspaceApplicationPage.class)
            .buildMetadata()
            .buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    state = new ApplicationNavigationState(session, new ObjectMapper());
    var app = new WorkspaceApplication();
    app.setId("application");
    app.setTenantId("tenant");
    app.setName("Original");
    app.setMainPageId("page");
    app.setStatus("DRAFT");
    app.setCreatedAt(Instant.now());
    app.setUpdatedAt(Instant.now());
    session.persist(app);
    var page = new WorkspaceApplicationPage();
    page.setId("page");
    page.setTenantId("tenant");
    page.setApplicationId("application");
    page.setName("Main");
    page.setKind("MAIN");
    page.setStatus("DRAFT");
    page.setCreatedAt(Instant.now());
    page.setUpdatedAt(Instant.now());
    session.persist(page);
    session.flush();
    authorize("tenant", true);
  }

  @AfterEach
  void close() {
    if (session.getTransaction().isActive()) session.getTransaction().rollback();
    session.close();
    factory.close();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void durableIntentSurvivesReloadAndMustMatchBeforeCompletionOrCancellation() {
    var initial = state.view("application");
    var config =
        new ApplicationNavigation(
            null,
            text("Renamed"),
            List.of(
                new ApplicationNavigation.Directory(
                    "child", null, text("Orders"), "FolderOutlined")),
            List.of(new ApplicationNavigation.Page("page", "child", text("Transactions"))));
    var work =
        state.begin(
            "application", new ApplicationNavigationState.Update(initial.revision(), config));
    session.flush();
    session.clear();
    assertThat(state.view("application").pending()).isTrue();
    assertThat(session.find(WorkspaceApplication.class, "application").getName())
        .isEqualTo("Original");
    assertThatThrownBy(
            () ->
                state.begin(
                    "application",
                    new ApplicationNavigationState.Update(0, initial.configuration())))
        .isInstanceOf(ApplicationFormException.class);
    var retried = state.begin("application", new ApplicationNavigationState.Update(0, config));
    assertThat(retried).isEqualTo(work);
    var completed = state.complete(work);
    assertThat(completed.pending()).isFalse();
    assertThat(completed.revision()).isEqualTo(1);
    assertThat(state.begin("application", new ApplicationNavigationState.Update(0, config))).isEqualTo(work);
    assertThat(
            session
                .find(WorkspaceApplicationPage.class, "page")
                .getNameLocalization()
                .defaultValue())
        .isEqualTo("Transactions");
    var cancellation =
        state.begin(
            "application", new ApplicationNavigationState.Update(1, initial.configuration()));
    assertThat(state.cancelled(cancellation).configuration()).isEqualTo(config);
    assertThat(state.view("application").revision()).isEqualTo(2);
    assertThatThrownBy(() -> state.complete(cancellation))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void rejectsForeignTenantsMissingEditorCapabilityCyclesAndOmittedPages() {
    authorize("other", true);
    assertThatThrownBy(() -> state.view("application"))
        .isInstanceOf(ApplicationFormException.class);
    authorize("tenant", false);
    assertThatThrownBy(() -> state.view("application"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    authorize("tenant", true);
    var config = state.view("application").configuration();
    var cycle =
        new ApplicationNavigation(
            null,
            config.name(),
            List.of(new ApplicationNavigation.Directory("cycle", "cycle", text("Cycle"), null)),
            config.pages());
    assertThatThrownBy(
            () -> state.begin("application", new ApplicationNavigationState.Update(0, cycle)))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(
            () ->
                state.begin(
                    "application",
                    new ApplicationNavigationState.Update(
                        0, new ApplicationNavigation(null, config.name(), List.of(), List.of()))))
        .isInstanceOf(ApplicationFormException.class);
    assertThat(state.view("application").pending()).isFalse();
  }

  private static LocalizedText text(String label) {
    return new LocalizedText(label, "en-US", Map.of("ja", "翻訳"));
  }

  private static void authorize(String tenant, boolean editor) {
    var context = new AuthorizationContext();
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", tenant));
    context.setResources(editor ? List.of("app.workbench.forms.edit") : List.of());
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }
}
