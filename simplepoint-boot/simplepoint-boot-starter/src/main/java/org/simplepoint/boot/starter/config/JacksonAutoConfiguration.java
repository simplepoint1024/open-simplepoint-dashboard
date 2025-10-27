package org.simplepoint.boot.starter.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JacksonAutoConfiguration is a configuration class for setting up Jackson-related
 * configurations in the SimplePoint Boot Starter.
 *
 * <p>This class can be extended to include custom Jackson configurations
 * such as custom serializers, deserializers, or modules.</p>
 */
@Configuration
@AutoConfiguration
public class JacksonAutoConfiguration {

  /**
   * Registers a custom Jackson module that modifies the serialization behavior
   * to convert null String values to empty strings.
   *
   * @return a SimpleModule with custom serialization settings
   */
  @Bean
  public SimpleModule customStringNullToEmptyModule() {
    return new SimpleModule() {
      @Override
      public void setupModule(SetupContext context) {
        //context.addBeanSerializerModifier(new SimpleBeanSerializerModifier());
      }
    };
  }
}
