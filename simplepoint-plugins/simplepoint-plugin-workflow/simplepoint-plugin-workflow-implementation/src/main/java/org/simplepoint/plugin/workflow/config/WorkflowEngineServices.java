package org.simplepoint.plugin.workflow.config;

import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.spring.ProcessEngineFactoryBean;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes the owned engine services and closes the engine with the owning application context. */
@Configuration(proxyBeanMethods = false)
public class WorkflowEngineServices {
  @Bean
  ProcessEngineFactoryBean processEngine(SpringProcessEngineConfiguration configuration) {
    var factory = new ProcessEngineFactoryBean();
    factory.setProcessEngineConfiguration(configuration);
    return factory;
  }

  @Bean
  RepositoryService repositoryService(ProcessEngine engine) {
    return engine.getRepositoryService();
  }

  @Bean
  RuntimeService runtimeService(ProcessEngine engine) {
    return engine.getRuntimeService();
  }

  @Bean
  TaskService taskService(ProcessEngine engine) {
    return engine.getTaskService();
  }

  @Bean
  HistoryService historyService(ProcessEngine engine) {
    return engine.getHistoryService();
  }

  @Bean
  ManagementService managementService(ProcessEngine engine) {
    return engine.getManagementService();
  }
}
