package org.simplepoint.dna.model.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.dna.model.entity.DnaDataModel;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class DnaDataModelCatalogTest {
  private SessionFactory factory;
  private Session session;
  private DnaDataModelCatalog catalog;

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:dna-model-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
    factory = new MetadataSources(registry).addAnnotatedClass(DnaDataModel.class)
        .addAnnotatedClass(JdbcDataSourceDefinition.class)
        .buildMetadata().buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    catalog = new DnaDataModelCatalog(session);
    authorize(AuthorizationScopeType.PLATFORM);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) session.close();
    if (factory != null) factory.close();
  }

  @Test
  void draftLifecycleIsPagedVersionedAndSoftDeleted() {
    var created = catalog.create(new DnaDataModelCatalog.Command(
        " Orders ", "Business orders", "DNA_SQL", "ignored", "select * from orders", null));
    session.flush();
    assertThat(created.model().name()).isEqualTo("Orders");
    assertThat(created.dataSourceId()).isNull();
    assertThat(catalog.list("order", "DNA_SQL", 0, 10).getTotalElements()).isEqualTo(1);
    assertThat(catalog.list("order", "NATIVE_SQL", 0, 10).getTotalElements()).isZero();

    assertThatThrownBy(() -> catalog.update(created.model().id(),
        new DnaDataModelCatalog.Command("Other", null, "DNA_SQL", null, null, 99L)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");

    var updated = catalog.update(created.model().id(),
        new DnaDataModelCatalog.Command("Orders v2", null, "DNA_SQL", null, "select 1", 0L));
    assertThat(updated.model().version()).isEqualTo(1);
    assertThat(catalog.detail(created.model().id()).sqlText()).isEqualTo("select 1");
    catalog.delete(created.model().id(), updated.model().version());
    assertThat(catalog.list("", "", 0, 10).getTotalElements()).isZero();
  }

  @Test
  void nativeModelRequiresExistingEnabledSourceAndTenantCannotUsePlatformCatalog() {
    assertThatThrownBy(() -> catalog.create(new DnaDataModelCatalog.Command(
        "Native", null, "NATIVE_SQL", null, "select 1", null)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    JdbcDataSourceDefinition source = new JdbcDataSourceDefinition();
    source.setId(UUID.randomUUID().toString());
    source.setName("Orders database");
    source.setCode("orders");
    source.setDriverId("driver");
    source.setJdbcUrl("jdbc:h2:mem:orders");
    source.setPassword("not-exposed");
    source.setEnabled(true);
    session.persist(source);
    session.flush();
    var options = catalog.dataSources("{\"version\":1,\"where\":{\"field\":\"name\",\"op\":\"like\",\"value\":\"Orders\"}}",
        null, 0, 20);
    assertThat(options.getContent()).containsExactly(new DnaDataModelCatalog.SourceOption(
        source.getId(), "Orders database"));
    var nativeModel = catalog.create(new DnaDataModelCatalog.Command(
        "Native", null, "NATIVE_SQL", source.getId(), "select 1", null));
    assertThat(nativeModel.model().dataSourceName()).isEqualTo("Orders database");
    authorize(AuthorizationScopeType.TENANT);
    assertThatThrownBy(() -> catalog.list("", "", 0, 10))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void actionDescriptorsOnlyContainGrantedOperations() {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("editor");
    context.setScopeType(AuthorizationScopeType.PLATFORM);
    context.setResources(List.of("dna.models.data-models.edit"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertThat(catalog.permissions().actions()).extracting(DnaDataModelCatalog.Action::key)
        .containsExactly("view", "edit");
    assertThat(catalog.schema().buttons()).extracting(DnaDataModelCatalog.Action::key)
        .containsExactly("view", "edit");
    var schema = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(catalog.schema().schema());
    assertThat(schema.path("x-query").path("fields").has("name")).isTrue();
    assertThat(schema.path("x-query").path("fields").has("sqlText")).isFalse();
    assertThat(schema.path("properties").path("dataSourceId").path("x-ui")
        .path("x-list-visible").asBoolean()).isFalse();
    assertThat(schema.path("properties").path("sqlText").path("x-ui")
        .path("x-list-visible").asBoolean()).isFalse();
  }

  @Test
  void standardTableQueryFiltersAndSortsBeforePagingWithoutExposingSqlText() {
    catalog.create(new DnaDataModelCatalog.Command(
        "Orders", "Sales orders", "DNA_SQL", null, "select 1", null));
    catalog.create(new DnaDataModelCatalog.Command(
        "Customers", "Customer data", "DNA_SQL", null, null, null));
    session.flush();

    var filtered = catalog.query(Map.of("name", "{\"version\":1,\"where\":{\"field\":\"name\",\"op\":\"like\",\"value\":\"Orders\"}}"),
        PageRequest.of(0, 20));
    assertThat(filtered.getTotalElements()).isEqualTo(1);
    assertThat(filtered.getContent()).extracting(DnaDataModelCatalog.Summary::name)
        .containsExactly("Orders");

    var sorted = catalog.query(Map.of("$query", "{\"version\":1,\"where\":{\"op\":\"or\",\"children\":["
        + "{\"field\":\"name\",\"op\":\"equals\",\"value\":\"Orders\"},"
        + "{\"field\":\"name\",\"op\":\"equals\",\"value\":\"Customers\"}]}}"),
        PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "name")));
    assertThat(sorted.getTotalElements()).isEqualTo(2);
    assertThat(sorted.getContent()).extracting(DnaDataModelCatalog.Summary::name)
        .containsExactly("Orders");

    assertThatThrownBy(() -> catalog.query(Map.of("sqlText", "like:select"), PageRequest.of(0, 20)))
        .isInstanceOf(InvalidQueryException.class);
  }

  private static void authorize(AuthorizationScopeType scope) {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("test-user");
    context.setScopeType(scope);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }
}
