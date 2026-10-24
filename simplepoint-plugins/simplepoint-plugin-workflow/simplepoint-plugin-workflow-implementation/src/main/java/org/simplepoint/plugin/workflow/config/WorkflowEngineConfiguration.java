package org.simplepoint.plugin.workflow.config;

import java.util.ArrayList;
import java.util.Map;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.simplepoint.events.api.ModuleEvents;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormWorkflowPort;
import org.simplepoint.plugin.notification.api.service.NotificationDeliveryService;
import org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory;
import org.simplepoint.plugin.rbac.tenant.api.service.MemberLabelDirectory;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantDirectory;
import org.simplepoint.plugin.workflow.api.service.WorkflowBusinessRuntimeService;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelProperties;
import org.simplepoint.plugin.workflow.service.WorkflowActivityBehaviorFactory;
import org.simplepoint.plugin.workflow.service.WorkflowEngineSignalListener;
import org.simplepoint.workflow.migration.FlowableProcessSchema;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Limits tenant-authored expressions to process variables, not the application's Spring beans. */
@ModuleEvents(WorkflowEventDeclarations.class)
@ModuleMigrations
@FlowableProcessSchema
@PlatformModule(
    id = "workflow",
    packages = "org.simplepoint.plugin.workflow",
    provides = WorkflowBusinessRuntimeService.class,
    consumes = {
      ApplicationFormWorkflowPort.class,
      NotificationDeliveryService.class,
      TenantDirectory.class,
      AuditIdentityDirectory.class,
      MemberLabelDirectory.class
    })
@EnableConfigurationProperties(WorkflowMemberLabelProperties.class)
@Configuration(proxyBeanMethods = false)
public class WorkflowEngineConfiguration {

  /**
   * Flowable's Spring integration exposes all beans when this map is null; an empty map exposes
   * none.
   */
  @Bean
  public WorkflowEngineCustomizer workflowExpressionIsolation() {
    return WorkflowEngineConfiguration::isolateExpressions;
  }

  static void isolateExpressions(SpringProcessEngineConfiguration configuration) {
    configuration.setBeans(Map.of());
    var expressions = configuration.getExpressionManager();
    if (expressions == null) {
      expressions = new org.flowable.engine.impl.el.ProcessExpressionManager(Map.of());
      configuration.setExpressionManager(expressions);
    }
    if (!(expressions
        instanceof org.flowable.common.engine.impl.el.DefaultExpressionManager standard)) {
      throw new IllegalStateException("Workflow requires the platform exact expression factory");
    }
    standard.setExpressionFactory(
        new org.simplepoint.plugin.workflow.service.ExactWorkflowExpressionFactory());
    if (configuration.getActivityBehaviorFactory() != null
        && !(configuration.getActivityBehaviorFactory()
            instanceof WorkflowActivityBehaviorFactory)) {
      throw new IllegalStateException(
          "Workflow execution limits require the platform behavior factory");
    }
    configuration.setActivityBehaviorFactory(new WorkflowActivityBehaviorFactory());
  }

  /**
   * Preserve other trusted engine listeners while installing the transactional notification bridge.
   */
  @Bean
  public WorkflowEngineCustomizer workflowEngineSignals(WorkflowEngineSignalListener listener) {
    return configuration -> {
      var listeners =
          configuration.getEventListeners() == null
              ? new ArrayList<org.flowable.common.engine.api.delegate.event.FlowableEventListener>()
              : new ArrayList<>(configuration.getEventListeners());
      listeners.add(listener);
      configuration.setEventListeners(listeners);
    };
  }
}
