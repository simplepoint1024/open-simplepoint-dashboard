package org.simplepoint.plugin.workflow.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.simplepoint.core.scheduling.PeriodicWorker;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelRefresh;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelRefreshProperties;
import org.simplepoint.plugin.workflow.directory.WorkflowMemberLabelRefreshQueue;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selected-module lifecycle wiring; the worker never captures a browser or tenant session. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowMemberLabelRefreshProperties.class)
public class WorkflowMemberLabelRefreshConfiguration {
  @Bean
  MeterBinder workflowMemberLabelRefreshMetrics(WorkflowMemberLabelRefreshQueue queue) {
    return registry -> {
      for (var state : WorkflowMemberLabelRefreshQueue.State.values()) {
        Gauge.builder(
                "simplepoint.workflow.member.labels.refresh", queue, value -> value.count(state))
            .tag("state", state.name())
            .register(registry);
      }
    };
  }

  @Bean
  PeriodicWorker workflowMemberLabelRefreshWorker(
      WorkflowMemberLabelRefresh refresh, WorkflowMemberLabelRefreshProperties properties) {
    return new PeriodicWorker(
        "workflow-member-label-refresh", properties.delay(), refresh::runBatch);
  }
}
