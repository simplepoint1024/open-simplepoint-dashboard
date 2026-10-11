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
