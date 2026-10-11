package org.simplepoint.plugin.ai.catalog.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogEntry;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogEntryStatus;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogPackageKind;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;
import org.simplepoint.plugin.ai.catalog.entity.AiCatalogReadModel;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.model.McpAuthenticationType;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerDeploymentType;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerStatus;
import org.simplepoint.plugin.ai.mcp.api.model.McpTransportType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;

class AiCatalogQueriesTest {
  private static SessionFactory factory;
  private Session session;
  private AiCatalogQueries queries;
  private AiScopeAccessPolicy policy;

  @BeforeAll
  static void database() {
    factory =
        new MetadataSources(
                new StandardServiceRegistryBuilder()
                    .applySetting(
                        "hibernate.connection.url",
                        "jdbc:h2:mem:ai-catalog-query;DB_CLOSE_DELAY=-1")
                    .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                    .applySetting(
                        "hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                    .build())
            .addAnnotatedClass(AiCatalogEntry.class)
            .addAnnotatedClass(AiMcpServerDefinition.class)
            .addAnnotatedClass(AiCatalogReadModel.class)
            .buildMetadata()
            .buildSessionFactory();
  }

  @BeforeEach
  void data() {
    session = factory.openSession();
    session.beginTransaction();
    for (int index = 0; index < 40; index++) {
      String name = "Node %02d".formatted(index);
      if (index % 2 == 0) {
        internal("server-" + index, name, "tenant", AiResourceScope.TENANT);
      } else {
        official("entry-" + index, name);
      }
    }
    internal("other-tenant", "Node foreign", "other", AiResourceScope.TENANT);
    internal("system", "Node system", null, AiResourceScope.SYSTEM);
    internal("deleted", "Node deleted", "tenant", AiResourceScope.TENANT)
        .setDeletedAt(Instant.now());
    official("removed", "Node removed").setDeletedAt(Instant.now());
    official("inactive", "Node inactive").setStatus(CatalogEntryStatus.DELETED);
    official("literal", "Literal %_!");
    session.flush();
    session.clear();
    policy = mock(AiScopeAccessPolicy.class);
    when(policy.currentManagementScope())
        .thenReturn(new AiScopeAccessPolicy.ScopeAssignment(AiResourceScope.TENANT, "tenant"));
    queries = new AiCatalogQueries(session, policy);
  }

  private AiMcpServerDefinition internal(
      String id, String name, String tenant, AiResourceScope scope) {
    var row = new AiMcpServerDefinition();
    row.setId(id);
    row.setCode(id);
    row.setName(name);
    row.setDescription("Shared description");
    row.setScopeType(scope);
    row.setTenantId(tenant);
    row.setDeploymentType(McpServerDeploymentType.REMOTE);
    row.setTransportType(McpTransportType.STREAMABLE_HTTP);
    row.setAuthenticationType(McpAuthenticationType.NONE);
    row.setAllowPrivateNetwork(false);
    row.setEnabled(true);
    row.setStatus(McpServerStatus.READY);
    row.setRemoteServerVersion("1");
    row.setCredentialCiphertext("never exposed in a catalog");
    session.persist(row);
    return row;
  }

  private AiCatalogEntry official(String id, String title) {
    var row = new AiCatalogEntry();
    row.setId(id);
    row.setExternalKey(id);
    row.setRegistryName(id);
    row.setSource(CatalogSource.OFFICIAL_MCP);
    row.setKind(CatalogPackageKind.MCP_SERVER);
    row.setVersion("2");
    row.setTitle(title);
    row.setDescription("Shared description");
    row.setStatus(CatalogEntryStatus.ACTIVE);
    row.setRawJson("{\"notNeededForList\":true}");
    row.setRegistryUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    row.setTransportType("streamable-http");
    row.setEndpointUrl("https://example.test/mcp");
    session.persist(row);
    return row;
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
  void globalSortingInterleavesBothSourcesBeforePagingInsteadOfConcatenatingTwoPages() {
    var result =
        queries.page(
            Map.of("title", "like:NODE", "description", "equals:SHARED DESCRIPTION"),
            PageRequest.of(1, 5, Sort.by("title")));
    assertThat(result.getTotalElements()).isEqualTo(40);
    assertThat(result.getContent())
        .extracting(AiCatalogReadModel::getTitle)
        .containsExactly("Node 05", "Node 06", "Node 07", "Node 08", "Node 09");
    assertThat(result.getContent())
        .extracting(AiCatalogReadModel::getSource)
        .containsExactly("OFFICIAL_MCP", "INTERNAL", "OFFICIAL_MCP", "INTERNAL", "OFFICIAL_MCP");
    assertThat(
            queries
                .page(
                    Map.of("source", "OFFICIAL_MCP", "version", "equals:2"), PageRequest.of(0, 100))
                .getTotalElements())
        .isEqualTo(21);
    assertThat(queries.page(Map.of("query", "%_!"), PageRequest.of(0, 20)).getContent())
        .extracting(AiCatalogReadModel::getTitle)
        .containsExactly("Literal %_!");
  }

  @Test
  void scopeIsMandatoryEvenWithOrFiltersAndPlatformScopeDoesNotSeeTenantServers() {
    String filter =
        "{\"version\":1,\"where\":{\"op\":\"or\",\"children\":[{\"field\":\"title\",\"op\":\"like\",\"value\":\"foreign\"},{\"field\":\"source\",\"op\":\"equals\",\"value\":\"INTERNAL\"}]}}";
    assertThat(queries.page(Map.of("$query", filter), PageRequest.of(0, 100)).getTotalElements())
        .isEqualTo(20);
    when(policy.currentManagementScope())
        .thenReturn(new AiScopeAccessPolicy.ScopeAssignment(AiResourceScope.SYSTEM, null));
    assertThat(queries.page(Map.of("source", "INTERNAL"), PageRequest.of(0, 20)).getContent())
        .extracting(AiCatalogReadModel::getEntryId)
        .containsExactly("system");
    when(policy.currentManagementScope())
        .thenThrow(new AccessDeniedException("No management scope"));
    assertThatThrownBy(() -> queries.page(Map.of(), PageRequest.of(0, 20)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(queries::schema).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void dtoProjectionPreservesImportEligibilityAndNeverExposesCredentialsOrRawDescriptors() {
    var service =
        new AiCatalogServiceImpl(
            null,
            null,
            null,
            policy,
            null,
            null,
            null,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            queries);
    var page =
        service.query(Map.of("title", "like:Node 0"), PageRequest.of(0, 20, Sort.by("title")));
    assertThat(page.totalElements()).isEqualTo(10);
    assertThat(page.content().getFirst().id()).isEqualTo("internal:mcp:server-0");
    assertThat(page.content().getFirst().importable()).isFalse();
    assertThat(page.content().get(1).id()).isEqualTo("entry-1");
    assertThat(page.content().get(1).remoteImportable()).isTrue();
    assertThat(page.content().get(1).runtimeImportable()).isFalse();
    assertThat(page.content().toString()).doesNotContain("never exposed", "notNeededForList");
  }

  @Test
  void schemaFieldPoliciesAndQueryBudgetAreEnforced() {
    var context = new AuthorizationContext();
    context.setFieldPermissions(Map.of("AiCatalogItem#description", "MASKED"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var schema = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(queries.schema());
    assertThat(schema.at("/schema/x-query/fields/description").isMissingNode()).isTrue();
    for (String field :
        java.util.List.of(
            "tenantId",
            "scopeType",
            "credentialCiphertext",
            "rawJson",
            "descriptorJson",
            "description")) {
      assertThatThrownBy(() -> queries.page(Map.of(field, "like:x"), PageRequest.of(0, 20)))
          .isInstanceOf(InvalidQueryException.class);
    }
    assertThatThrownBy(() -> queries.page(Map.of(), PageRequest.of(0, 101)))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> queries.page(Map.of("query", "search"), PageRequest.of(0, 20)))
        .isInstanceOf(InvalidQueryException.class);
  }
}
