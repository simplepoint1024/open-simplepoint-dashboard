package org.simplepoint.plugin.ai.core.service.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.plugin.ai.core.api.entity.AiGatewayResource;
import org.simplepoint.plugin.ai.core.repository.AiGatewayResourceRepository;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

class AiGatewayResourcePersistenceTest {
  @Test
  void migrationAndProductionRepositoryPersistBindingsAcrossSessions() throws Exception {
    String url = "jdbc:h2:mem:openai-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    try (var connection = DriverManager.getConnection(url);
        var statement = connection.createStatement()) {
      statement.execute(Files.readString(Path.of("../../scripts/sql/ai-openai-gateway-resources.sql")));
    }
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", url)
        .applySetting("hibernate.hbm2ddl.auto", "validate")
        .build();
    try (var factory = new MetadataSources(registry).addAnnotatedClass(AiGatewayResource.class)
        .buildMetadata().buildSessionFactory()) {
      String id = NativeOpenAiGateway.bindingId("key-a", "response", "resp_1");
      try (var session = factory.openSession()) {
        var repositories = new JpaRepositoryFactory(session);
        repositories.setRepositoryBaseClass(BaseRepositoryImpl.class);
        final var repository = repositories.getRepository(AiGatewayResourceRepository.class);
        session.beginTransaction();
        AiGatewayResource binding = new AiGatewayResource();
        binding.setId(id);
        binding.setApiKeyId("key-a");
        binding.setProviderDefinitionId("provider-1");
        binding.setModelDefinitionId("model-1");
        binding.setPublicModelId("public-model");
        binding.setResourceType("response");
        binding.setUpstreamId("resp_1");
        binding.setCreatedAt(Instant.now());
        repository.saveAndFlush(binding);
        session.getTransaction().commit();
      }
      try (var session = factory.openSession()) {
        var repositories = new JpaRepositoryFactory(session);
        repositories.setRepositoryBaseClass(BaseRepositoryImpl.class);
        final var repository = repositories.getRepository(AiGatewayResourceRepository.class);
        assertThat(repository.findById(id)).isPresent().get()
            .extracting(AiGatewayResource::getModelDefinitionId).isEqualTo("model-1");
        assertThat(repository.findById(NativeOpenAiGateway.bindingId("key-b", "response", "resp_1")))
            .isEmpty();
      }
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }
}
