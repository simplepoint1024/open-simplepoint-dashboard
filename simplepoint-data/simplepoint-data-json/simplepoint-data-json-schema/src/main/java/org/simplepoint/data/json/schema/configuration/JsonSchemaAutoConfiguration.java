package org.simplepoint.data.json.schema.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import java.util.Set;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.data.json.schema.generator.DefaultJsonSchemaGenerator;
import org.simplepoint.data.json.schema.module.OpenApiModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for JSON Schema generation using Victools library.
 */
@AutoConfiguration
public class JsonSchemaAutoConfiguration {

  /**
   * Provides a Function bean that generates JSON schemas for given classes using the SchemaGenerator.
   *
   * @param schemaGenerator the SchemaGenerator to use for generating schemas
   * @return a Function that takes a Class and returns its JSON schema as an ObjectNode
   */
  @Bean
  @ConditionalOnMissingBean
  public JsonSchemaGenerator defaultJsonSchemaGenerator(SchemaGenerator schemaGenerator) {
    return new DefaultJsonSchemaGenerator(schemaGenerator);
  }

  /**
   * Configures and provides a SchemaGenerator bean.
   *
   * @param objectMapper the ObjectMapper to use for JSON processing
   * @param modules      a set of additional modules to enhance schema generation
   * @return a configured SchemaGenerator instance
   */
  @Bean
  @ConditionalOnMissingBean
  public SchemaGenerator schemaGenerator(ObjectMapper objectMapper, Set<Module> modules) {
    SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
        objectMapper, SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON
    );
    modules.forEach(configBuilder::with);
    return new SchemaGenerator(configBuilder.build());
  }

  /**
   * Provides the OpenApiModule bean for enhancing schema generation with OpenAPI annotations.
   *
   * @return an instance of OpenApiModule
   */
  @Bean
  @ConditionalOnMissingBean
  public OpenApiModule openApiModule() {
    return new OpenApiModule();
  }

  /**
   * Provides the JacksonModule bean for integrating Jackson annotations into schema generation.
   *
   * @return an instance of JacksonModule
   */
  @Bean
  @ConditionalOnMissingBean
  public JacksonModule jacksonModule() {
    return new JacksonModule();
  }

  /**
   * Provides the JakartaValidationModule bean for integrating Jakarta Bean Validation annotations into schema generation.
   *
   * @return an instance of JakartaValidationModule
   */
  @Bean
  @ConditionalOnMissingBean
  public JakartaValidationModule javaxValidationModule() {
    return new JakartaValidationModule();
  }
}
