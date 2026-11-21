package org.simplepoint.plugin.app.form.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.simplepoint.events.api.ModuleEvents;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormWorkflowPort;
import org.simplepoint.plugin.app.form.api.service.FormComponentContributor;
import org.simplepoint.plugin.app.form.api.service.FormComponentRegistry;
import org.simplepoint.plugin.app.form.service.DefaultFormComponentRegistry;
import org.simplepoint.plugin.app.form.service.DnaInvocationContextFactory;
import org.simplepoint.plugin.app.form.service.FormDocumentCodec;
import org.simplepoint.plugin.app.form.service.FormDocumentValidator;
import org.simplepoint.plugin.app.form.service.FormRequestContext;
import org.simplepoint.plugin.app.form.service.FormStorageProjectionCompiler;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;
import org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory;
import org.simplepoint.plugin.rbac.tenant.api.service.ManagedTenantResourceService;
import org.simplepoint.plugin.storage.api.service.ObjectStorageSourceService;
import org.simplepoint.plugin.workflow.api.service.WorkflowBusinessRuntimeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Replaceable infrastructure used by the APP form domain. */
@ModuleMigrations
@ModuleEvents(FormEventDeclarations.class)
@PlatformModule(
    id = "app-form",
    packages = {
      "org.simplepoint.plugin.app.form",
      "org.simplepoint.app.catalog.service",
      "org.simplepoint.app.form.service",
      "org.simplepoint.app.form.entity"
    },
    provides = ApplicationFormWorkflowPort.class,
    consumes = {
      org.simplepoint.security.context.AuthorizationContextService.class,
      DnaFormRuntimeService.class,
      DnaOwnedRecordService.class,
      AuditIdentityDirectory.class,
      ManagedTenantResourceService.class,
      ObjectStorageSourceService.class,
      WorkflowBusinessRuntimeService.class
    })
@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
@EnableConfigurationProperties(ApplicationFormPublicationProperties.class)
public class ApplicationFormConfiguration {

  /** Isolates publication RPC latency from other scheduled platform work. */
  @Bean(name = "formPublicationRecoveryScheduler")
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      prefix = "simplepoint.app.form.publication",
      name = "recovery-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
      formPublicationRecoveryScheduler() {
    var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("form-publication-recovery-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(45);
    return scheduler;
  }

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
      final List<FormComponentContributor> contributors) {
    return new DefaultFormComponentRegistry(contributors);
  }

  /** Provides complete server-side form document validation. */
  @Bean
  @ConditionalOnMissingBean
  public FormDocumentValidator formDocumentValidator(final FormComponentRegistry registry) {
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
  public FormStorageProjectionCompiler formStorageProjectionCompiler(final ObjectMapper mapper) {
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
      final FormRequestContext requestContext,
      final org.simplepoint.plugin.app.form.service.FormRelationTargetResolver relations) {
    return new DnaInvocationContextFactory(requestContext, relations);
  }
}
