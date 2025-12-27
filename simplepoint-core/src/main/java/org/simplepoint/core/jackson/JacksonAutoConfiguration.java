package org.simplepoint.core.jackson;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuration class for Jackson ObjectMapper.
 */
@Slf4j
@AutoConfiguration
public class JacksonAutoConfiguration {

  /**
   * Configures and provides a primary ObjectMapper bean.
   *
   * @return the configured ObjectMapper
   */
  @Bean
  @Primary
  @ConditionalOnMissingBean
  public ObjectMapper objectMapper() {
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    //objectMapper.disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    //log.info("ObjectMapper modules: {}", objectMapper.getRegisteredModuleIds());
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    return objectMapper;
  }
}
