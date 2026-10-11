package org.simplepoint.plugin.ai.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogEntry;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogEntryStatus;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogPackageKind;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;
import org.simplepoint.plugin.ai.catalog.service.support.OffsetPageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

class OfficialCatalogDatabaseTest {
  SessionFactory factory;
  Session session;
  JpaAiCatalogEntryRepository repository;

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:official-catalog-" + java.util.UUID.randomUUID())
        .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(AiCatalogEntry.class).buildMetadata().buildSessionFactory();
    session = factory.openSession(); session.beginTransaction();
    var repositories = new JpaRepositoryFactory(session); repositories.setRepositoryBaseClass(BaseRepositoryImpl.class);
    repository = repositories.getRepository(JpaAiCatalogEntryRepository.class);
    for (int index = 0; index < 521; index++) entry("registry%03d".formatted(index), CatalogEntryStatus.ACTIVE, false);
    entry("registry deleted", CatalogEntryStatus.ACTIVE, true);
    entry("registry disabled", CatalogEntryStatus.DELETED, false);
    entry("literal%_!", CatalogEntryStatus.ACTIVE, false);
    session.flush(); session.clear(); factory.getStatistics().clear();
  }

  void entry(String name, CatalogEntryStatus status, boolean deleted) {
    var entity = new AiCatalogEntry(); entity.setExternalKey(java.util.UUID.randomUUID().toString());
    entity.setSource(CatalogSource.OFFICIAL_MCP); entity.setKind(CatalogPackageKind.MCP_SERVER); entity.setStatus(status);
    entity.setRegistryName(name); entity.setVersion("1.0"); entity.setRawJson("{}");
    entity.setRegistryUpdatedAt(Instant.parse("2026-10-10T00:00:00Z"));
    if (deleted) entity.setDeletedAt(Instant.now()); session.persist(entity);
  }

  @AfterEach
  void cleanup() {
    if (session != null) {if (session.getTransaction().isActive()) session.getTransaction().rollback(); session.close();}
    if (factory != null) factory.close();
  }

  @Test
  void arbitraryMixedBoundaryOffsetUsesSqlPagingAndCountDoesNotHydrateDescriptors() {
    assertThat(repository.countActiveBySource(CatalogSource.OFFICIAL_MCP, CatalogEntryStatus.ACTIVE, "%registry%"))
        .isEqualTo(521);
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    var mixed = repository.findAllActiveBySource(CatalogSource.OFFICIAL_MCP, CatalogEntryStatus.ACTIVE, "%registry%", new OffsetPageRequest(19, 20));
    assertThat(mixed.getTotalElements()).isEqualTo(521);
    assertThat(mixed.getContent()).extracting(row -> row.getRegistryName()).containsExactlyElementsOf(
        java.util.stream.IntStream.range(19, 39).mapToObj(index -> "registry%03d".formatted(index)).toList());
    assertThat(factory.getStatistics().getEntityLoadCount()).isEqualTo(20);
    var beyond = repository.findAllActiveBySource(CatalogSource.OFFICIAL_MCP, CatalogEntryStatus.ACTIVE, "%registry520%", new OffsetPageRequest(0, 20));
    assertThat(beyond.getContent()).extracting(row -> row.getRegistryName()).containsExactly("registry520");
  }

  @Test
  void escapedRegistrySearchTreatsPercentUnderscoreAndEscapeAsLiteralCharacters() {
    assertThat(repository.countActiveBySource(CatalogSource.OFFICIAL_MCP, CatalogEntryStatus.ACTIVE, "%!%!_!!%"))
        .isEqualTo(1);
    assertThat(repository.findAllActiveBySource(CatalogSource.OFFICIAL_MCP, CatalogEntryStatus.ACTIVE, "%!%!_!!%", new OffsetPageRequest(0, 20)).getContent())
        .extracting(row -> row.getRegistryName()).containsExactly("literal%_!");
  }
}
