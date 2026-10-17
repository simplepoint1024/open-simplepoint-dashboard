package org.simplepoint.dna.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPlanService;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPublicationService;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

class RuntimeGovernanceCatalogTest {

  private SessionFactory factory;
  private Session session;
  private RuntimeGovernanceCatalog catalog;

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:dna-runtime-ui-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build();
    factory = new MetadataSources(registry)
        .addAnnotatedClass(PhysicalModelEntity.class)
        .addAnnotatedClass(org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity.class)
        .buildMetadata()
        .buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    catalog = new RuntimeGovernanceCatalog(
        session,
        mock(SchemaPlanService.class),
        mock(SchemaPublicationService.class)
    );
    authorize();
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {
      if (session.getTransaction().isActive()) {
        session.getTransaction().rollback();
      }
      session.close();
    }
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void schemaPublishesThePlatformQueryContractAndPermissionFilteredActions() {
    var schema = catalog.schema(RuntimeGovernanceCatalog.Resource.MIGRATIONS);
    var json = new ObjectMapper().valueToTree(schema.schema());

    assertThat(json.path("x-query").path("maxPageSize").asInt()).isEqualTo(100);
    assertThat(json.path("x-query").path("fields").has("status")).isTrue();
    assertThat(json.path("x-query").path("fields").has("targetVersion")).isTrue();
    assertThat(schema.buttons()).extracting(RuntimeGovernanceCatalog.Action::key)
        .containsExactly("view", "recover", "retry");
  }

  @Test
  void modelPageAppliesTenantAndSoftDeleteBeforeFilterSortCountAndPaging() {
    persist("tenant-a", "Orders", false);
    persist("tenant-a", "Customers", false);
    persist("tenant-a", "Deleted orders", true);
    persist("tenant-b", "Other tenant", false);
    session.flush();

    var page = catalog.models(
        Map.of("displayName", "like:Orders"),
        PageRequest.of(0, 200)
    );

    assertThat(page.getSize()).isEqualTo(100);
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent()).extracting(RuntimeGovernanceCatalog.ModelSummary::displayName)
        .containsExactly("Orders");
  }

  @Test
  void modelDetailIsTenantBoundAndDoesNotNeedADataSourceOrPlan() {
    persist("tenant-a", "Visible", false);
    persist("tenant-a", "Deleted", true);
    persist("tenant-b", "Foreign", false);
    session.flush();
    var models = session.createQuery("from PhysicalModelEntity", PhysicalModelEntity.class)
        .getResultList();
    for (var model : models) {
      if (model.getDisplayName().equals("Visible")) {
        var detail = catalog.model(model.getId());
        assertThat(detail.model().displayName()).isEqualTo("Visible");
        assertThat(detail.schemaJson()).isNull();
      } else {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> catalog.model(model.getId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
      }
    }
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> catalog.model("missing"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void storagePreparationPinsTheActiveVersionAndRequiresRecoveryPermission() throws Exception {
    persist("tenant-a", "Preparation", false);
    session.flush();
    var model = session.createQuery("from PhysicalModelEntity", PhysicalModelEntity.class).getSingleResult();
    model.setLatestVersion(2);
    var publication = mock(SchemaPublicationService.class);
    var adapter = new RuntimeGovernanceCatalog(session, mock(SchemaPlanService.class), publication);
    adapter.prepareStorage(model.getId(), 7);
    var httpCatalog = mock(RuntimeGovernanceCatalog.class);
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
        .standaloneSetup(new RuntimeGovernanceController(httpCatalog)).build();
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
        "/platform/dna/runtime/models/" + model.getId() + "/prepare-storage").param("expectedVersion", "7"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    org.mockito.Mockito.verify(publication).publish(model.getId(),
        new org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand(1, 7));
    org.mockito.Mockito.verify(httpCatalog).prepareStorage(model.getId(), 7);
    var context = new AuthorizationContext();
    context.setUserId("user-a");
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    context.setResources(List.of("dna.runtime.models.view"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.prepareStorage(model.getId(), 7))
        .isInstanceOf(AccessDeniedException.class);
  }

  private void persist(final String tenantId, final String displayName, final boolean deleted) {
    PhysicalModelEntity model = new PhysicalModelEntity();
    model.setTenantId(tenantId);
    model.setAppId("app-" + displayName);
    model.setFormId("form-" + displayName);
    model.setDisplayName(displayName);
    model.setStatus(PhysicalModelStatus.ACTIVE);
    model.setLatestVersion(1);
    model.setActiveVersion(1);
    model.setCreatedBy("user-a");
    model.setUpdatedBy("user-a");
    if (deleted) {
      model.setDeletedAt(Instant.now());
    }
    session.persist(model);
  }

  private static void authorize() {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("user-a");
    // The host attaches the selected organization's authorization context to
    // platform module requests. Governance must keep tenant isolation while
    // accepting that standard browser request scope.
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setResources(List.of("dna.runtime.models.view", "dna.runtime.models.retry"));
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }
}
