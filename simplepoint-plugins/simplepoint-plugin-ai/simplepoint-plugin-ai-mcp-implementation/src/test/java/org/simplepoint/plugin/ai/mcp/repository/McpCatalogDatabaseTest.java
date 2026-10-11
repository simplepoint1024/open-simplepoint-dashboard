package org.simplepoint.plugin.ai.mcp.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.model.McpAuthenticationType;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerDeploymentType;
import org.simplepoint.plugin.ai.mcp.api.model.McpServerStatus;
import org.simplepoint.plugin.ai.mcp.api.model.McpTransportType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

class McpCatalogDatabaseTest {
  SessionFactory factory;
  Session session;
  JpaAiMcpServerDefinitionRepository repository;
  List<String> statements = new ArrayList<>();

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:mcp-catalog-" + java.util.UUID.randomUUID())
        .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .applySetting("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {statements.add(sql); return sql;})
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(AiMcpServerDefinition.class).buildMetadata().buildSessionFactory();
    session = factory.openSession(); session.beginTransaction();
    var repositories = new JpaRepositoryFactory(session);
    repositories.setRepositoryBaseClass(BaseRepositoryImpl.class);
    repository = repositories.getRepository(JpaAiMcpServerDefinitionRepository.class);
    for (int index = 0; index < 521; index++) server("Actual%03d".formatted(index), AiResourceScope.TENANT, "t1", false);
    for (int index = 0; index < 5; index++) server("Other%03d".formatted(index), AiResourceScope.TENANT, "t2", false);
    server("Actual deleted", AiResourceScope.TENANT, "t1", true);
    server("Platform", AiResourceScope.SYSTEM, null, false);
    server("Wrong platform owner", AiResourceScope.SYSTEM, "t1", false);
    server("Literal%_!", AiResourceScope.TENANT, "t2", false);
    session.flush(); session.clear(); factory.getStatistics().clear(); statements.clear();
  }

  void server(String name, AiResourceScope scope, String tenant, boolean deleted) {
    var entity = new AiMcpServerDefinition();
    entity.setName(name); entity.setCode(name); entity.setScopeType(scope); entity.setTenantId(tenant);
    entity.setDeploymentType(McpServerDeploymentType.REMOTE); entity.setTransportType(McpTransportType.STREAMABLE_HTTP);
    entity.setAuthenticationType(McpAuthenticationType.NONE); entity.setStatus(McpServerStatus.READY);
    entity.setEnabled(true); entity.setAllowPrivateNetwork(false);
    entity.setCredentialCiphertext("not-returned");
    if (deleted) entity.setDeletedAt(Instant.now());
    session.persist(entity);
  }

  @AfterEach
  void cleanup() {
    if (session != null) {if (session.getTransaction().isActive()) session.getTransaction().rollback(); session.close();}
    if (factory != null) factory.close();
  }

  @Test
  void countsAndPagesTheExactScopeBeyondFiveHundredWithoutLoadingEntitiesOrCredentials() {
    var page = repository.findCatalogInScope(AiResourceScope.TENANT, "t1", "%actual%", PageRequest.of(25, 20));
    assertThat(page.getTotalElements()).isEqualTo(521);
    assertThat(page.getContent()).hasSize(20);
    assertThat(page.getContent().getFirst().name()).isEqualTo("Actual500");
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    assertThat(statements).hasSize(2);
    assertThat(statements.getFirst()).contains("offset", "fetch first").doesNotContain("credential_ciphertext", "oauth_");
    var last = repository.findCatalogInScope(AiResourceScope.TENANT, "t1", "%actual%", PageRequest.of(26, 20));
    assertThat(last.getTotalElements()).isEqualTo(521);
    assertThat(last.getContent()).extracting(row -> row.name()).containsExactly("Actual520");
    var search = repository.findCatalogInScope(AiResourceScope.TENANT, "t1", "%actual520%", PageRequest.of(0, 20));
    assertThat(search.getTotalElements()).isEqualTo(1);
  }

  @Test
  void platformOwnershipSoftDeleteAndLiteralSearchRemainSqlPredicates() {
    var platform = repository.findCatalogInScope(AiResourceScope.SYSTEM, null, "", PageRequest.of(0, 20));
    assertThat(platform.getContent()).extracting(row -> row.name()).containsExactly("Platform");
    var literal = repository.findCatalogInScope(AiResourceScope.TENANT, "t2", "%!%!_!!%", PageRequest.of(0, 20));
    assertThat(literal.getContent()).extracting(row -> row.name()).containsExactly("Literal%_!");
    assertThat(repository.findCatalogInScope(AiResourceScope.TENANT, "missing", "", PageRequest.of(0, 20)).getTotalElements()).isZero();
  }
}
