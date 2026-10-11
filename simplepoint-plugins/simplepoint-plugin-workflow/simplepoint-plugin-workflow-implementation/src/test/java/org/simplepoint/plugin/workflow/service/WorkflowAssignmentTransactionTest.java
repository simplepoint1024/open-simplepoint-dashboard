package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.config.WorkflowEngineConfiguration;
import org.simplepoint.plugin.workflow.entity.WorkflowDefinitionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowDefinitionVersionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowInstanceWatcherEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowNotificationOutboxEntity;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.support.TransactionTemplate;

/** Real shared JPA/Flowable transaction: invalid resolved assignees roll back without leaking notifications. */
class WorkflowAssignmentTransactionTest {

  private SessionFactory factory;
  private ProcessEngine engine;
  private StaticApplicationContext context;
  private TransactionTemplate transaction;
  private WorkflowCenterServiceImpl service;

  @BeforeEach
  void setup() {
    var source = new DriverManagerDataSource("jdbc:h2:mem:assignment-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.datasource", source)
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.hbm2ddl.halt_on_error", "true").build())
        .addAnnotatedClass(WorkflowDefinitionEntity.class).addAnnotatedClass(WorkflowDefinitionVersionEntity.class)
        .addAnnotatedClass(WorkflowInstanceEntity.class).addAnnotatedClass(WorkflowInstanceWatcherEntity.class)
        .addAnnotatedClass(WorkflowNotificationOutboxEntity.class).buildMetadata().buildSessionFactory();
    var manager = new JpaTransactionManager(factory);
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
    configuration.setAsyncExecutorActivate(false);
    new WorkflowEngineConfiguration().workflowExpressionIsolation().configure(configuration);
    engine = configuration.buildProcessEngine();
    var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
    TenantService tenants = mock(TenantService.class);
    when(tenants.resolveActiveMemberIds(eq("tenant"), anyCollection())).thenAnswer(invocation -> {
      Collection<String> requested = invocation.getArgument(1);
      return requested.stream().filter("actor"::equals).toList();
    });
    when(tenants.resolveActiveRoleAuthorities(eq("tenant"), anyCollection())).thenAnswer(invocation -> {
      Collection<String> requested = invocation.getArgument(1);
      return requested.stream().filter(role -> List.of("tenant.approver", "部门/审核").contains(role)).toList();
    });
    service = new WorkflowCenterServiceImpl(entityManager, engine.getRepositoryService(), engine.getRuntimeService(),
        engine.getTaskService(), engine.getHistoryService(), new BpmnDefinitionValidator(), new ObjectMapper(), tenants, List.of());
    var authorization = new AuthorizationContext();
    authorization.setScopeType(AuthorizationScopeType.TENANT);
    authorization.setUserId("actor");
    authorization.setIsAdministrator(true);
    authorization.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (engine != null) engine.close();
    if (factory != null) factory.close();
    if (context != null) context.close();
  }

  @Test
  void aForeignAssigneeResolvedFromLegacyAdvancedVariablesRollsBackEngineInstanceAndOutbox() {
    String id = publish("${approver}", null);
    assertThatThrownBy(() -> transaction.execute(status -> service.start(id,
        new StartProcessCommand(1, null, "Private matter", Map.of("approver", "foreign-user"), List.of()))))
        .isInstanceOf(WorkflowException.class).hasMessageContaining("有效成员");
    assertNoRuntimeOrOutbox();
  }

  @Test
  void aForeignCandidateUserRollsBackInTheSameTransaction() {
    String id = publish(null, "${reviewers}");
    assertThatThrownBy(() -> transaction.execute(status -> service.start(id,
        new StartProcessCommand(1, null, "Private matter", Map.of("reviewers", List.of("foreign-user")), List.of()))))
        .isInstanceOf(WorkflowException.class).hasMessageContaining("有效成员");
    assertNoRuntimeOrOutbox();
  }

  @Test
  void validTenantAssigneesCommitTheEngineInstancePlatformProjectionAndNotificationTogether() {
    String id = publish("${approver}", null);
    var started = transaction.execute(status -> service.start(id,
        new StartProcessCommand(1, null, "Allowed matter", Map.of("approver", "actor"), List.of())));
    assertThat(started).isNotNull();
    assertThat(engine.getTaskService().createTaskQuery().taskAssignee("actor").count()).isEqualTo(1);
    assertThat(count(WorkflowInstanceEntity.class)).isEqualTo(1);
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isEqualTo(1);
  }

  @Test
  void foreignOrDeletedDynamicCandidateRolesRollBackInsteadOfCreatingAnUnclaimableTask() {
    String id = publishRole("${reviewRoles}");
    assertThatThrownBy(() -> transaction.execute(status -> service.start(id,
        new StartProcessCommand(1, null, "Private matter", Map.of("reviewRoles", List.of("foreign.approver")), List.of()))))
        .isInstanceOf(WorkflowException.class).hasMessageContaining("角色");
    assertNoRuntimeOrOutbox();
  }

  @Test
  void existingTenantRoleAuthoritiesAreValidCandidateGroups() {
    String id = publishRole("${reviewRoles}");
    transaction.execute(status -> service.start(id,
        new StartProcessCommand(1, null, "Allowed matter", Map.of("reviewRoles", List.of("tenant.approver")), List.of())));
    assertThat(engine.getTaskService().createTaskQuery().taskCandidateGroup("tenant.approver").count()).isEqualTo(1);
    assertThat(count(WorkflowInstanceEntity.class)).isEqualTo(1);
  }

  @Test
  void fixedNonAsciiAuthoritiesRemainRealFlowableCandidateGroups() {
    String id = publishRole("部门/审核");
    transaction.execute(status -> service.start(id,
        new StartProcessCommand(1, null, "Allowed matter", Map.of(), List.of())));
    assertThat(engine.getTaskService().createTaskQuery().taskCandidateGroup("部门/审核").count()).isEqualTo(1);
    assertThat(count(WorkflowInstanceEntity.class)).isEqualTo(1);
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isZero();
  }

  private String publishRole(final String candidates) {
    return transaction.execute(status -> {
      var initial = service.createDefinition(new CreateDefinitionCommand("roleApproval", "Approval", null, null));
      String xml = initial.bpmnXml().replace("flowable:assignee=\"${initiator}\"",
          "flowable:candidateGroups=\"" + candidates + "\"");
      service.updateDraft(initial.definition().id(), new UpdateDraftCommand(1, "Approval", null, null, xml, null));
      service.publish(initial.definition().id(), new PublishDefinitionCommand(2, null));
      return initial.definition().id();
    });
  }

  private String publish(final String assignee, final String candidates) {
    return transaction.execute(status -> {
      var initial = service.createDefinition(new CreateDefinitionCommand("approval", "Approval", null, null));
      String xml = assignee != null ? initial.bpmnXml().replace("${initiator}", assignee)
          : initial.bpmnXml().replace("flowable:assignee=\"${initiator}\"", "flowable:candidateUsers=\"" + candidates + "\"");
      service.updateDraft(initial.definition().id(), new UpdateDraftCommand(1, "Approval", null, null, xml, null));
      service.publish(initial.definition().id(), new PublishDefinitionCommand(2, null));
      return initial.definition().id();
    });
  }

  private void assertNoRuntimeOrOutbox() {
    assertThat(engine.getRuntimeService().createProcessInstanceQuery().count()).isZero();
    assertThat(engine.getTaskService().createTaskQuery().count()).isZero();
    assertThat(count(WorkflowInstanceEntity.class)).isZero();
    assertThat(count(WorkflowNotificationOutboxEntity.class)).isZero();
  }

  private long count(final Class<?> type) {
    return transaction.execute(status -> SharedEntityManagerCreator.createSharedEntityManager(factory)
        .createQuery("select count(e) from " + type.getSimpleName() + " e", Long.class).getSingleResult());
  }
}
