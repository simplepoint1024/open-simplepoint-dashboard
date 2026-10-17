package org.simplepoint.plugin.workflow.service;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.config.WorkflowEngineConfiguration;
import org.simplepoint.plugin.workflow.entity.WorkflowActionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowCandidateReadModel;
import org.simplepoint.plugin.workflow.entity.WorkflowDefinitionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowDefinitionVersionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowEngineSignalEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowInboxReadModel;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceWatcherEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowMatterReadModel;
import org.simplepoint.plugin.workflow.entity.WorkflowMemberReadModel;
import org.simplepoint.plugin.workflow.entity.WorkflowNotificationOutboxEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowRoleReadModel;
import org.simplepoint.plugin.workflow.entity.WorkflowStartableReadModel;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared real JPA/Flowable transaction fixture for workflow runtime contracts. */
abstract class WorkflowEngineFixture {

  protected SessionFactory factory;
  protected ProcessEngine engine;
  private StaticApplicationContext context;
  protected TransactionTemplate transaction;
  protected WorkflowCenterServiceImpl service;
  protected TenantService tenants;
  protected JpaTransactionManager manager;
  protected DriverManagerDataSource source;
  private DriverManagerDataSource postgresAdmin;
  private String postgresSchema;

  @BeforeEach
  void setup() throws SQLException {
    source = testDataSource();
    factory =
        new MetadataSources(
                new StandardServiceRegistryBuilder()
                    .applySetting("hibernate.connection.datasource", source)
                    .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                    .applySetting("hibernate.hbm2ddl.halt_on_error", "true")
                    .build())
            .addAnnotatedClass(WorkflowDefinitionEntity.class)
            .addAnnotatedClass(WorkflowDefinitionVersionEntity.class)
            .addAnnotatedClass(WorkflowInstanceEntity.class)
            .addAnnotatedClass(WorkflowInstanceWatcherEntity.class)
            .addAnnotatedClass(WorkflowNotificationOutboxEntity.class)
            .addAnnotatedClass(WorkflowActionEntity.class)
            .addAnnotatedClass(org.simplepoint.plugin.workflow.entity.WorkflowOperationReceiptEntity.class)
            .addAnnotatedClass(org.simplepoint.plugin.workflow.entity.WorkflowBusinessLinkEntity.class)
            .addAnnotatedClass(org.simplepoint.plugin.workflow.entity.WorkflowFormBindingEntity.class)
            .addAnnotatedClass(WorkflowEngineSignalEntity.class)
            .addAnnotatedClass(WorkflowInboxReadModel.class)
            .addAnnotatedClass(WorkflowCandidateReadModel.class)
            .addAnnotatedClass(WorkflowMatterReadModel.class)
            .addAnnotatedClass(WorkflowStartableReadModel.class)
            .addAnnotatedClass(WorkflowMemberReadModel.class)
            .addAnnotatedClass(WorkflowRoleReadModel.class)
            .buildMetadata()
            .buildSessionFactory();
    manager = new JpaTransactionManager(factory);
    // A native SessionFactory lacks Spring's EntityManagerFactoryInfo; supply the same dialect
    // Spring Boot uses so Flowable JDBC can join the Hibernate-owned connection.
    manager.setJpaDialect(new HibernateJpaDialect());
    manager.setDataSource(source);
    transaction = new TransactionTemplate(manager);
    context = new StaticApplicationContext();
    var configuration = new SpringProcessEngineConfiguration();
    configuration.setDataSource(source);
    configuration.setTransactionManager(manager);
    configuration.setApplicationContext(context);
    configuration.setDatabaseSchemaUpdate("true");
    if (postgresSchema != null) {
      configuration.setDatabaseSchema(postgresSchema);
    }
    configuration.setAsyncExecutorActivate(false);
    new WorkflowEngineConfiguration().workflowExpressionIsolation().configure(configuration);
    var workflowProvider =
        new org.springframework.beans.factory.ObjectProvider<WorkflowCenterServiceImpl>() {
          @Override
          public WorkflowCenterServiceImpl getObject() {
            return service;
          }
        };
    new WorkflowEngineConfiguration()
        .workflowEngineSignals(new WorkflowEngineSignalListener(source, workflowProvider))
        .configure(configuration);
    engine = configuration.buildProcessEngine();
    var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
    tenants = mock(TenantService.class);
    when(tenants.resolveActiveMemberIds(eq("tenant"), anyCollection()))
        .thenAnswer(
            invocation -> {
              Collection<String> requested = invocation.getArgument(1);
              return requested.stream().filter("actor"::equals).toList();
            });
    when(tenants.resolveActiveRoleAuthorities(eq("tenant"), anyCollection()))
        .thenAnswer(
            invocation -> {
              Collection<String> requested = invocation.getArgument(1);
              return requested.stream()
                  .filter(role -> List.of("tenant.approver", "部门/审核").contains(role))
                  .toList();
            });
    service =
        new WorkflowCenterServiceImpl(
            entityManager,
            engine.getRepositoryService(),
            engine.getRuntimeService(),
            engine.getTaskService(),
            engine.getHistoryService(),
            new BpmnDefinitionValidator(),
            new ObjectMapper(),
            tenants,
            formProviders());
    var authorization = new AuthorizationContext();
    authorization.setScopeType(AuthorizationScopeType.TENANT);
    authorization.setUserId("actor");
    authorization.setIsAdministrator(true);
    authorization.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
  }

  @AfterEach
  void cleanup() throws SQLException {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    try {
      if (engine != null) {
        engine.close();
      }
    } finally {
      try {
        if (factory != null) {
          factory.close();
        }
        if (context != null) {
          context.close();
        }
      } finally {
        if (postgresAdmin != null && postgresSchema != null) {
          if (!postgresSchema.matches("workflow_test_[0-9a-f]{32}")) {
            throw new IllegalStateException("Invalid disposable test schema");
          }
          try (var connection = postgresAdmin.getConnection();
              var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + postgresSchema + " cascade");
          }
        }
      }
    }
  }

  protected List<org.simplepoint.plugin.workflow.api.service.WorkflowFormProvider> formProviders() {
    return List.of();
  }

  /**
   * Opt-in real dialect gate, hard-pinned to a disposable localhost database; never accepts a
   * production URL.
   */
  private DriverManagerDataSource testDataSource() throws SQLException {
    if (!"1".equals(System.getenv("WORKFLOW_TEST_POSTGRES"))) {
      return new DriverManagerDataSource(
          "jdbc:h2:mem:assignment-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }
    String url = "jdbc:postgresql://127.0.0.1:45439/workflow_test";
    postgresAdmin = new DriverManagerDataSource(url, "workflow_test", "workflow_test");
    postgresSchema = "workflow_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = postgresAdmin.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("create schema " + postgresSchema);
    }
    return new DriverManagerDataSource(
        url + "?currentSchema=" + postgresSchema, "workflow_test", "workflow_test");
  }

  protected String publishRole(final String candidates) {
    return transaction.execute(
        status -> {
          var initial =
              service.createDefinition(
                  new CreateDefinitionCommand("roleApproval", "Approval", null, null));
          String xml =
              initial
                  .bpmnXml()
                  .replace(
                      "flowable:assignee=\"${initiator}\"",
                      "flowable:candidateGroups=\"" + candidates + "\"");
          service.updateDraft(
              initial.definition().id(),
              new UpdateDraftCommand(1, "Approval", null, null, xml, null));
          service.publish(initial.definition().id(), new PublishDefinitionCommand(2, null));
          return initial.definition().id();
        });
  }

  protected String publish(final String assignee, final String candidates) {
    return transaction.execute(
        status -> {
          var initial =
              service.createDefinition(
                  new CreateDefinitionCommand("approval", "Approval", null, null));
          String xml =
              assignee != null
                  ? initial.bpmnXml().replace("${initiator}", assignee)
                  : initial
                      .bpmnXml()
                      .replace(
                          "flowable:assignee=\"${initiator}\"",
                          "flowable:candidateUsers=\"" + candidates + "\"");
          service.updateDraft(
              initial.definition().id(),
              new UpdateDraftCommand(1, "Approval", null, null, xml, null));
          service.publish(initial.definition().id(), new PublishDefinitionCommand(2, null));
          return initial.definition().id();
        });
  }

  protected long count(final Class<?> type) {
    return transaction.execute(
        status ->
            SharedEntityManagerCreator.createSharedEntityManager(factory)
                .createQuery("select count(e) from " + type.getSimpleName() + " e", Long.class)
                .getSingleResult());
  }

  protected void authorize(
      final String user,
      final String tenant,
      final boolean administrator,
      final Collection<String> resources) {
    var authorization = new AuthorizationContext();
    authorization.setScopeType(AuthorizationScopeType.TENANT);
    authorization.setUserId(user);
    authorization.setIsAdministrator(administrator);
    authorization.setAttributes(Map.of("X-Tenant-Id", tenant));
    authorization.setResources(resources);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
  }
}
