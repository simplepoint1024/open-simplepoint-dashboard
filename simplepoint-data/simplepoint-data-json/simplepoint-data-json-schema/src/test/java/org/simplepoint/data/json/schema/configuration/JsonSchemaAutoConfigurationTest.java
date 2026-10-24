package org.simplepoint.data.json.schema.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.data.json.schema.service.DefaultJsonSchemaDetailsService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JsonSchemaAutoConfigurationTest {
  private final ApplicationContextRunner context = new ApplicationContextRunner()
      .withBean(ObjectMapper.class, ObjectMapper::new)
      .withConfiguration(AutoConfigurations.of(JsonSchemaAutoConfiguration.class));

  @Test
  void selectedHostsReceiveTheDialectWithoutAnyPersistenceContextOrBusinessScan() {
    context.run(application -> {
      assertThat(application).hasNotFailed().hasSingleBean(JsonSchemaDetailsService.class);
      assertThat(application.getBean(JsonSchemaDetailsService.class))
          .isInstanceOf(DefaultJsonSchemaDetailsService.class);
      assertThat(application).doesNotHaveBean(jakarta.persistence.EntityManager.class);
    });
  }

  @Test
  void anExplicitDialectRemainsAuthoritative() {
    JsonSchemaDetailsService custom = new JsonSchemaDetailsService() {};
    context.withBean(JsonSchemaDetailsService.class, () -> custom).run(application -> {
      assertThat(application).hasNotFailed().hasSingleBean(JsonSchemaDetailsService.class);
      assertThat(application.getBean(JsonSchemaDetailsService.class)).isSameAs(custom);
    });
  }
}
