package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.hibernate.Session;
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
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract.Variable;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract.Binding;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.entity.WorkflowDefinitionEntity;
import org.simplepoint.plugin.workflow.entity.WorkflowDefinitionVersionEntity;

/** Real persistence for draft/version boundaries; only the external engine deployment is stubbed. */
class WorkflowVersionContractTest {

  private SessionFactory factory;
  private Session session;
  private WorkflowCenterServiceImpl service;
  private final List<String> statements = new java.util.ArrayList<>();

  @BeforeEach
  void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:workflow-contract-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.halt_on_error", "true")
        .applySetting("hibernate.generate_statistics", "true")
        .applySetting("hibernate.session_factory.statement_inspector", (org.hibernate.resource.jdbc.spi.StatementInspector) statement -> {
          statements.add(statement);
          return statement;
        })
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build())
        .addAnnotatedClass(WorkflowDefinitionEntity.class)
        .addAnnotatedClass(WorkflowDefinitionVersionEntity.class).buildMetadata().buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("actor");
    context.setIsAdministrator(true);
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    RepositoryService repository = mock(RepositoryService.class);
    DeploymentBuilder builder = mock(DeploymentBuilder.class, RETURNS_SELF);
    ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class, RETURNS_SELF);
    AtomicInteger deployments = new AtomicInteger();
    when(repository.createDeployment()).thenReturn(builder);
    when(builder.deploy()).thenAnswer(invocation -> {
      Deployment deployment = mock(Deployment.class);
      when(deployment.getId()).thenReturn("deployment-" + deployments.incrementAndGet());
      return deployment;
    });
    when(repository.createProcessDefinitionQuery()).thenReturn(query);
    when(query.list()).thenAnswer(invocation -> {
      ProcessDefinition process = mock(ProcessDefinition.class);
      when(process.getKey()).thenReturn("approval");
      when(process.getId()).thenReturn("process-" + deployments.get());
      return List.of(process);
    });
    service = new WorkflowCenterServiceImpl(session, repository, mock(RuntimeService.class, RETURNS_DEEP_STUBS),
        mock(TaskService.class, RETURNS_DEEP_STUBS), mock(HistoryService.class), new BpmnDefinitionValidator(),
        new ObjectMapper(), mock(TenantService.class), List.of());
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {
      session.getTransaction().rollback();
      session.close();
    }
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void historyProjectsAllVersionSummariesWithoutLoadingTheirBpmnOrFormSnapshots() {
    var initial = service.createDefinition(new CreateDefinitionCommand("approval", "History", null, null));
    String id = initial.definition().id();
    for (int number = 1; number <= 522; number++) {
      var version = new WorkflowDefinitionVersionEntity();
      version.setId("history-" + number);
      version.setDefinitionId(id);
      version.setTenantId(number == 522 ? "another-tenant" : "tenant");
      version.setVersion(number);
      version.setBpmnXml(initial.bpmnXml() + " ".repeat(8192));
      version.setFormContract(" ".repeat(8192));
      version.setChecksum("checksum-" + number);
      version.setComment("release-" + number);
      version.setFlowableDeploymentId("history-deployment-" + number);
      version.setFlowableProcessDefinitionId("history-process-" + number);
      version.setPublishedBy("actor");
      version.setPublishedAt(Instant.parse("2026-10-10T00:00:00Z").plusSeconds(number));
      session.persist(version);
    }
    session.flush();
    session.clear();
    factory.getStatistics().clear();
    statements.clear();
    var history = service.definition(id).versions();
    assertThat(history).hasSize(521);
    assertThat(history.getFirst().version()).isEqualTo(521);
    assertThat(history.getLast().version()).isEqualTo(1);
    assertThat(history.getFirst().comment()).isEqualTo("release-521");
    assertThat(factory.getStatistics().getEntityStatistics(WorkflowDefinitionVersionEntity.class.getName()).getLoadCount()).isZero();
    assertThat(statements.stream().filter(statement -> statement.startsWith("select")
        && statement.contains("simpoint_workflow_definition_versions")).toList())
        .hasSize(1).allSatisfy(statement -> assertThat(statement).doesNotContain("bpmn_xml", "form_contract", "definition_name"));
    assertThat(service.version(id, 1).bpmnXml()).startsWith(initial.bpmnXml());
    assertThat(factory.getStatistics().getEntityStatistics(WorkflowDefinitionVersionEntity.class.getName()).getLoadCount()).isEqualTo(1);
  }

  @Test
  void publishesImmutableMetadataAndFormsEvenWhenBpmnDoesNotChange() {
    var initial = service.createDefinition(new CreateDefinitionCommand("approval", "Old name", "old", "old description"));
    String id = initial.definition().id();
    var first = service.publish(id, new PublishDefinitionCommand(1, "first"));
    var contract = new WorkflowFormContract(1,
        List.of(new Variable("approver", "Approver", "user", "start", true, null)), null, Map.of());
    var changed = service.updateDraft(id, new UpdateDraftCommand(1, "New name", "new", "new description",
        initial.bpmnXml(), contract));
    assertThat(changed.definition().unpublishedChanges()).isTrue();
    var second = service.publish(id, new PublishDefinitionCommand(2, "second"));
    assertThat(second.version()).isEqualTo(2);
    assertThat(second.checksum()).isNotEqualTo(first.checksum());
    assertThat(service.version(id, 1).name()).isEqualTo("Old name");
    assertThat(service.version(id, 1).formContract().variables()).isEmpty();
    assertThat(service.startForm(id, 1).formContract().variables()).isEmpty();
    assertThat(service.startForm(id, 2).formContract().variables()).hasSize(1);
    assertThat(service.definition(id).definition().unpublishedChanges()).isFalse();
    assertThat(service.publish(id, new PublishDefinitionCommand(2, "repeat")).id()).isEqualTo(second.id());
    var copied = service.copyVersionToDraft(id, 1, 2);
    assertThat(copied.definition().name()).isEqualTo("Old name");
    assertThat(copied.definition().category()).isEqualTo("old");
    assertThat(copied.formContract().variables()).isEmpty();
    assertThat(service.version(id, 2).name()).isEqualTo("New name");
  }

  @Test
  void metadataOnlyChangesCreateANewVersionAndStaleRevisionNeverOverwritesADraft() {
    var initial = service.createDefinition(new CreateDefinitionCommand("approval", "Initial", null, null));
    String id = initial.definition().id();
    service.publish(id, new PublishDefinitionCommand(1, null));
    service.updateDraft(id, new UpdateDraftCommand(1, "Renamed", null, null, initial.bpmnXml(), null));
    assertThat(service.publish(id, new PublishDefinitionCommand(2, null)).version()).isEqualTo(2);
    assertThatThrownBy(() -> service.updateDraft(id, new UpdateDraftCommand(1, "Stale", null, null,
        initial.bpmnXml(), null))).isInstanceOf(WorkflowException.class).hasMessageContaining("刷新");
    assertThat(service.definition(id).definition().name()).isEqualTo("Renamed");
  }

  @Test
  void anUnavailableFormProviderCannotBeBypassedByCallingTheStartApiDirectly() {
    var initial = service.createDefinition(new CreateDefinitionCommand("approval", "Bound form", null, null));
    String id = initial.definition().id();
    var binding = new Binding("future-forms", "form1", 1, Map.of());
    var contract = new WorkflowFormContract(1, List.of(), binding, Map.of());
    service.updateDraft(id, new UpdateDraftCommand(1, "Bound form", null, null, initial.bpmnXml(), contract));
    assertThatThrownBy(() -> service.publish(id, new PublishDefinitionCommand(2, null)))
        .isInstanceOf(WorkflowException.class).hasMessageContaining("尚未接入");
  }

  @Test
  void taskBindingsMustReferToAnActualUserTaskInTheBpmnSnapshot() {
    var initial = service.createDefinition(new CreateDefinitionCommand("approval", "Bound form", null, null));
    var contract = new WorkflowFormContract(1, List.of(), null,
        Map.of("deletedTask", new Binding("forms", "form1", 1, Map.of())));
    assertThatThrownBy(() -> service.updateDraft(initial.definition().id(), new UpdateDraftCommand(
        1, "Bound form", null, null, initial.bpmnXml(), contract)))
        .isInstanceOf(WorkflowException.class).hasMessageContaining("不存在的用户任务");
  }

  @Test
  void legacyXmlChecksumsUpgradeWithoutInventingHistoricalMetadata() throws Exception {
    var initial = service.createDefinition(new CreateDefinitionCommand("approval", "Legacy", null, null));
    WorkflowDefinitionEntity definition = session.find(WorkflowDefinitionEntity.class, initial.definition().id());
    definition.setDraftFormContract(null);
    String checksum = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(initial.bpmnXml().getBytes(StandardCharsets.UTF_8)));
    definition.setPublishedChecksum(checksum);
    definition.setLatestVersion(1);
    WorkflowDefinitionVersionEntity legacy = new WorkflowDefinitionVersionEntity();
    legacy.setId(UUID.randomUUID().toString());
    legacy.setDefinitionId(definition.getId());
    legacy.setTenantId("tenant");
    legacy.setVersion(1);
    legacy.setBpmnXml(initial.bpmnXml());
    legacy.setChecksum(checksum);
    legacy.setFlowableDeploymentId("old-deployment");
    legacy.setFlowableProcessDefinitionId("old-process");
    legacy.setPublishedBy("actor");
    legacy.setPublishedAt(Instant.now());
    session.persist(legacy);
    assertThat(service.definition(definition.getId()).definition().unpublishedChanges()).isFalse();
    assertThat(service.version(definition.getId(), 1).name()).isNull();
    service.publish(definition.getId(), new PublishDefinitionCommand(1, "upgrade"));
    assertThat(service.definition(definition.getId()).definition().unpublishedChanges()).isFalse();
    assertThat(service.version(definition.getId(), 1).name()).isNull();
  }
}
