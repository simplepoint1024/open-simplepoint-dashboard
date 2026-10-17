package org.simplepoint.plugin.app.form.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.simplepoint.plugin.app.form.api.service.FormComponentContributor;
import org.simplepoint.plugin.app.form.api.service.FormComponentRegistry;
import org.simplepoint.plugin.app.form.service.DefaultFormComponentRegistry;
import org.simplepoint.plugin.app.form.service.DnaInvocationContextFactory;
import org.simplepoint.plugin.app.form.service.FormDocumentCodec;
import org.simplepoint.plugin.app.form.service.FormDocumentValidator;
import org.simplepoint.plugin.app.form.service.FormRequestContext;
import org.simplepoint.plugin.app.form.service.FormStorageProjectionCompiler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Replaceable infrastructure used by the APP form domain. */
@Configuration
@EnableConfigurationProperties(ApplicationFormPublicationProperties.class)
public class ApplicationFormConfiguration {

  /** Provides a replaceable UTC clock for durable publication leases. */
  @Bean
  @ConditionalOnMissingBean
  public Clock applicationFormClock() {
    return Clock.systemUTC();
  }

  /** Provides the platform's trusted built-in form component catalog. */
  @Bean
  @ConditionalOnMissingBean(FormComponentRegistry.class)
  public FormComponentRegistry formComponentRegistry(
      final List<FormComponentContributor> contributors
  ) {
    return new DefaultFormComponentRegistry(contributors);
  }

  /** Provides complete server-side form document validation. */
  @Bean
  @ConditionalOnMissingBean
  public FormDocumentValidator formDocumentValidator(
      final FormComponentRegistry registry
  ) {
    return new FormDocumentValidator(registry);
  }

  /** Provides deterministic serialization for drafts and versions. */
  @Bean
  @ConditionalOnMissingBean
  public FormDocumentCodec formDocumentCodec(final ObjectMapper mapper) {
    return new FormDocumentCodec(mapper);
  }

  /** Provides deterministic APP-to-DNA data-schema compilation. */
  @Bean
  @ConditionalOnMissingBean
  public FormStorageProjectionCompiler formStorageProjectionCompiler(
      final ObjectMapper mapper
  ) {
    return new FormStorageProjectionCompiler(mapper);
  }

  /** Provides request-scoped identity extraction. */
  @Bean
  @ConditionalOnMissingBean
  public FormRequestContext formRequestContext() {
    return new FormRequestContext();
  }

  /** Provides trusted APP-to-DNA tenant and effective authorization propagation. */
  @Bean
  @ConditionalOnMissingBean
  public DnaInvocationContextFactory dnaInvocationContextFactory(
      final FormRequestContext requestContext
  ) {
    return new DnaInvocationContextFactory(requestContext);
  }
}
