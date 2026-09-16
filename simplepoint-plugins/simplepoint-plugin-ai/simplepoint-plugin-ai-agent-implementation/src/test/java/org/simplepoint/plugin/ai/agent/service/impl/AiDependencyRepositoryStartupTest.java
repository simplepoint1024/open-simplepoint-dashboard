package org.simplepoint.plugin.ai.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentDefinition;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
import org.simplepoint.plugin.ai.agent.repository.JpaAiAgentVersionRepository;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiModelType;
import org.simplepoint.plugin.ai.core.repository.JpaAiModelDefinitionRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.repository.JpaAiSkillVersionRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Starts real Spring Data proxies so every directory HQL query is parsed. */
class AiDependencyRepositoryStartupTest {

  @Test
  void dependencyRepositoriesStartWithValidatedQueries() {
    SpringApplication application = new SpringApplication(
        TestConfiguration.class
    );
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setDefaultProperties(Map.of(
        "spring.datasource.url",
        "jdbc:h2:mem:dependency-directory;MODE=PostgreSQL;"
            + "DB_CLOSE_DELAY=-1",
        "spring.datasource.username",
        "sa",
        "spring.datasource.password",
        "",
        "spring.jpa.hibernate.ddl-auto",
        "create-drop",
        "spring.sql.init.mode",
        "never"
    ));

    try (ConfigurableApplicationContext context = application.run()) {
      final ModelRepositoryUnderTest models = context.getBean(
          ModelRepositoryUnderTest.class
      );
      final AgentRepositoryUnderTest agents = context.getBean(
          AgentRepositoryUnderTest.class
      );
      final SkillRepositoryUnderTest skills = context.getBean(
          SkillRepositoryUnderTest.class
      );
      final PageRequest page = PageRequest.of(0, 20);

      AiModelDefinition model = new AiModelDefinition();
      model.setId("model-a");
      model.setProviderId("provider-a");
      model.setModelId("chat-model");
      model.setDisplayName("Chat Model");
      model.setModelType(AiModelType.LLM);
      model.setEnabled(true);
      model.setAvailable(true);
      model.setBillingEnabled(false);
      models.save(model);
      models.flush();

      assertThat(models.searchDependencyOptions(
          false,
          null,
          "%%",
          page
      ).getContent()).singleElement().satisfies(option -> {
        assertThat(option.getResourceId()).isEqualTo("model-a");
        assertThat(option.getResourceCode()).isEqualTo("chat-model");
        assertThat(option.getResourceName()).isEqualTo("Chat Model");
        assertThat(option.getResourceVersionId()).isNull();
        assertThat(option.getPublishedAt()).isNull();
      });
      assertThat(agents.searchDependencyOptions(
          false,
          null,
          "%%",
          page
      )).isEmpty();
      assertThat(skills.searchDependencyOptions(
          false,
          null,
          "%%",
          page
      )).isEmpty();
      assertThat(models.resolveDependencyOptions(
          false,
          null,
          List.of("model-a")
      )).singleElement().satisfies(option -> {
        assertThat(option.getSelectable()).isTrue();
        assertThat(option.getAvailabilityCode()).isNull();
      });
      model.setAvailable(false);
      models.save(model);
      models.flush();
      assertThat(models.searchDependencyOptions(
          false,
          null,
          "%%",
          page
      )).isEmpty();
      assertThat(models.resolveDependencyOptions(
          false,
          null,
          List.of("model-a")
      )).singleElement().satisfies(option -> {
        assertThat(option.getSelectable()).isFalse();
        assertThat(option.getAvailabilityCode())
            .isEqualTo("MODEL_UNAVAILABLE");
      });
      assertThat(agents.resolveDependencyOptions(
          false,
          null,
          List.of("missing-agent-version")
      )).isEmpty();
      assertThat(skills.resolveDependencyOptions(
          false,
          null,
          List.of("missing-skill-version")
      )).isEmpty();
    }
  }

  interface ModelRepositoryUnderTest
      extends JpaAiModelDefinitionRepository {
  }

  interface AgentRepositoryUnderTest
      extends JpaAiAgentVersionRepository {
  }

  interface SkillRepositoryUnderTest
      extends JpaAiSkillVersionRepository {
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = {
      AiModelDefinition.class,
      AiAgentDefinition.class,
      AiAgentVersion.class,
      AiSkillDefinition.class,
      AiSkillVersion.class
  })
  @EnableJpaRepositories(
      basePackageClasses = AiDependencyRepositoryStartupTest.class,
      considerNestedRepositories = true,
      repositoryBaseClass = BaseRepositoryImpl.class
  )
  static class TestConfiguration {
  }
}
