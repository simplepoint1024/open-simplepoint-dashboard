package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.app.form.api.model.*;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormSubmissionEntity;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.model.WorkflowInvocationContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.*;
import org.simplepoint.plugin.workflow.api.service.WorkflowBusinessRuntimeService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Real control-plane commits, leases and restart; remote ports deliberately lose responses. */
class FormSubmissionRecoveryTest {
  private SessionFactory factory;
  private EntityManager manager;
  private FormSubmissionCodec codec;
  private FormSubmissionJournal journal;
  private final MutableClock clock = new MutableClock();
  private final ApplicationFormRecordServiceImpl records = mock(ApplicationFormRecordServiceImpl.class);
  private final WorkflowBusinessRuntimeService workflow = mock(WorkflowBusinessRuntimeService.class);
  private final String id = "a".repeat(64);
  private AcceptedFormSubmission accepted;

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:submission-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
    factory = new MetadataSources(registry).addAnnotatedClass(ApplicationFormEntity.class)
        .addAnnotatedClass(org.simplepoint.plugin.app.form.entity.ApplicationResourceOfflineEntity.class)
        .addAnnotatedClass(org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity.class)
        .addAnnotatedClass(ApplicationFormSubmissionEntity.class).buildMetadata().buildSessionFactory();
    manager = SharedEntityManagerCreator.createSharedEntityManager(factory);
    var transactions = new JpaTransactionManager(factory);
    codec = new FormSubmissionCodec(new ObjectMapper().findAndRegisterModules());
    ProxyFactory proxy = new ProxyFactory(new FormSubmissionJournal(manager, codec, clock));
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    journal = (FormSubmissionJournal) proxy.getProxy();
    new TransactionTemplate(transactions).executeWithoutResult(status -> {
      var form = new ApplicationFormEntity();
      form.setId("form"); form.setTenantId("tenant"); form.setApplicationId("app");
      form.setCode("request"); form.setName("申请"); form.setStatus(ApplicationFormStatus.PUBLISHED);
      form.setCreatedAt(clock.instant()); form.setUpdatedAt(clock.instant()); manager.persist(form);
    });
    var document = new ApplicationFormDocument("1", List.of(new FormFieldDefinition("name", "name", "名称", "名称",
        FormFieldType.STRING, FormStorageMode.STORED, true, true, 120, null, null, null, List.of())), List.of(), List.of(new FormSceneDefinition("main", "main", "申请", FormSceneType.MAIN, null,
            new FormComponentDefinition("root", "layout.form", "1", null, 24, null, null, null, null, false, Map.of(), List.of(
                new FormComponentDefinition("name-input", "field.text", "1", "name", 24, null, null, null, null, false, Map.of(), List.of()))))), List.of());
    var form = new PublishedApplicationForm("tenant", "app", "page", "申请", "form", "申请", 1, "app.page",
        "model", 1, "f".repeat(64), document, 1, 1, List.of());
    var command = new SubmitApplicationFormCommand(new ApplicationFormRuntimeIdentity("form", 1), "request-key",
        null, null, "definition", 1, null, "采购申请", Map.of("name", "原始申请"), Map.of(), List.of());
    var binding = new WorkflowFormContract.Binding("simplepoint-app", "form", 1, Map.of("name", "name"));
    var contract = new WorkflowFormContract(1, List.of(new WorkflowFormContract.Variable("name", "名称", "string", "both", true, null)), binding, Map.of("review", binding));
    accepted = new AcceptedFormSubmission(form, command,
        new DnaInvocationContext("tenant", "actor", null, "app.page", false,
            Set.of("app.page", "app.page.records.create"), "SELF", Set.of(), false, List.of(), Map.of()),
        new WorkflowInvocationContext("tenant", "actor", false, Set.of("workflow.instances.start"), Set.of()),
        new RuntimeForm(1, contract, binding, Map.of("name", "原始申请")), null);
    when(records.captureCreate(any(), any())).thenReturn(new ApplicationFormRecordView("record", 0,
        FormRecordState.SUBMITTED, Map.of("name", "提交快照")));
  }

  @AfterEach
  void close() {
    org.simplepoint.core.RequestContextHolder.clearContext(org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    if (factory != null) factory.close();
  }

  @Test
  void restartAfterLostWorkflowResponseReusesTheCapturedDataAndTheSameStartKey() {
    journal.accept(id, "fingerprint", accepted);
    when(workflow.start(any(), eq("definition"), any())).thenThrow(new IllegalStateException("response lost"))
        .thenReturn(process("RUNNING"));
    new FormSubmissionProcessor(journal, records, new FormWorkflowGateway(workflow)).advance(id);
    var pending = journal.getOwned(id, "tenant", "actor");
    assertThat(pending.step()).isEqualTo("DATA_COMMITTED");
    assertThat(pending.status()).isEqualTo("RETRY");
    assertThat(pending.recordId()).isEqualTo("record");
    assertThat(AuthorizationContextHolder.getContext()).isNull();
    clock.advance(6);
    new FormSubmissionProcessor(journal, records, new FormWorkflowGateway(workflow)).advance(id);
    assertThat(journal.getOwned(id, "tenant", "actor").processStatus()).isEqualTo("RUNNING");
    verify(records, times(1)).captureCreate(any(), any());
    var commands = org.mockito.ArgumentCaptor.forClass(StartProcessCommand.class);
    verify(workflow, times(2)).start(any(), eq("definition"), commands.capture());
    assertThat(commands.getAllValues().get(0)).isEqualTo(commands.getAllValues().get(1));
    assertThat(commands.getValue().variables()).containsEntry("name", "提交快照");
    when(workflow.instance(any(), eq("process"))).thenReturn(process("REJECTED"));
    clock.advance(21);
    new FormSubmissionProcessor(journal, records, new FormWorkflowGateway(workflow)).advance(id);
    var terminal = journal.getOwned(id, "tenant", "actor");
    assertThat(terminal.step()).isEqualTo("TERMINAL");
    assertThat(terminal.processStatus()).isEqualTo("REJECTED");
    assertThat(journal.recoverable(20)).doesNotContain(id);
  }

  @Test
  void engineCallbacksMustReferenceTheCommittedOriginalIntentAndCannotOverrideMappedFacts() {
    journal.accept(id, "fingerprint", accepted);
    var properties = new org.simplepoint.plugin.app.form.configuration.ApplicationFormWorkflowRemoteProperties();
    properties.setAllowedCallers(Set.of("workflow"));
    var provider = new ApplicationFormWorkflowPortImpl(manager, new FormDocumentCodec(new ObjectMapper()), codec, properties);
    var principal = new org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal("workflow",
        Map.of("client_id", "workflow", "grant_type", "client_credentials"), List.of());
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
        new org.springframework.security.authentication.TestingAuthenticationToken(principal, null, "SCOPE_service-router.invoke"));
    var check = new FormWorkflowSubmissionCheck("tenant", "actor", "definition", 1, null, "app-form:" + id,
        "form", 1, "start", null, Map.of("name", "提交快照"), false, Set.of("app.page"));
    assertThatThrownBy(() -> provider.validateSubmission(check)).isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    var lease = journal.claim(id);
    journal.captured(id, lease.token(), new ApplicationFormRecordView("record", 0, FormRecordState.SUBMITTED, Map.of("name", "提交快照")));
    provider.validateSubmission(check);
    assertThatThrownBy(() -> provider.validateSubmission(new FormWorkflowSubmissionCheck("tenant", "actor", "definition", 1,
        null, "app-form:" + id, "form", 1, "start", null, Map.of("name", "伪造值"), false, Set.of("app.page"))))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    assertThatThrownBy(() -> provider.validateSubmission(new FormWorkflowSubmissionCheck("tenant", "another", "definition", 1,
        null, "app-form:" + id, "form", 1, "start", null, Map.of("name", "提交快照"), false, Set.of("app.page"))))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    assertThatThrownBy(() -> provider.validateSubmission(new FormWorkflowSubmissionCheck("other-tenant", "actor", "definition", 1,
        null, "app-form:" + id, "form", 1, "start", null, Map.of("name", "提交快照"), false, Set.of("app.page"))))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    provider.validateSubmission(new FormWorkflowSubmissionCheck("tenant", "approver", "definition", 1,
        "process", "app-form:" + id, "form", 1, "task", "review", Map.of(), false, Set.of("app.page")));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
        new org.springframework.security.authentication.TestingAuthenticationToken("workflow", null, "SCOPE_service-router.invoke"));
    assertThatThrownBy(() -> provider.validateSubmission(check)).isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
  }

  @Test
  void taskSnapshotStillRequiresCurrentPagePermissionAndReappliesFieldVisibility() {
    journal.accept(id, "fingerprint", accepted);
    var lease = journal.claim(id);
    journal.captured(id, lease.token(), new ApplicationFormRecordView("record", 0, FormRecordState.SUBMITTED, Map.of("name", "敏感名称")));
    var actors = new FormRequestContext();
    var resolver = new PublishedApplicationFormResolver(manager, actors,
        mock(org.simplepoint.plugin.app.form.api.service.ApplicationPageAccessPort.class), new FormDocumentCodec(new ObjectMapper()));
    var snapshots = new FormSubmissionSnapshotService(new FormWorkflowGateway(workflow), journal, resolver,
        new FormRuntimeDocumentCompiler(), new DnaInvocationContextFactory(actors), actors);
    when(workflow.taskForm(any(), eq("task"))).thenReturn(new RuntimeForm(1, accepted.workflow().formContract(),
        accepted.workflow().binding(), Map.of(), "process", "app-form:" + id, "definition", Set.of("name")));
    var denied = new org.simplepoint.core.AuthorizationContext();
    denied.setUserId("approver"); denied.setScopeType(org.simplepoint.core.AuthorizationScopeType.TENANT);
    denied.setAttributes(Map.of("X-Tenant-Id", "tenant")); denied.setResources(Set.of());
    org.simplepoint.core.RequestContextHolder.setContext(org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, denied);
    assertThatThrownBy(() -> snapshots.task("task")).isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    for (String policy : List.of("HIDDEN", "MASKED", "VISIBLE")) {
      var reader = new org.simplepoint.core.AuthorizationContext();
      reader.setUserId("approver"); reader.setScopeType(org.simplepoint.core.AuthorizationScopeType.TENANT);
      reader.setAttributes(Map.of("X-Tenant-Id", "tenant")); reader.setResources(Set.of("app.page"));
      reader.setResourceFieldPermissions(Map.of("app.page", Map.of(
          org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel.key("model") + "#name", policy)));
      org.simplepoint.core.RequestContextHolder.setContext(org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, reader);
      var result = snapshots.task("task");
      if ("HIDDEN".equals(policy)) {
        assertThat(result.record().values()).doesNotContainKey("name");
        assertThat(result.form().document().fields()).isEmpty();
      } else assertThat(result.record().values()).containsEntry("name", "MASKED".equals(policy) ? "***" : "敏感名称");
    }
  }

  @Test
  void operationIdentityAndTenantActorRemainBoundAcrossRetries() {
    var original = journal.accept(id, "fingerprint", accepted);
    assertThat(journal.accept(id, "fingerprint", accepted)).isEqualTo(original);
    assertThatThrownBy(() -> journal.findExact(id, "other", "actor", "fingerprint")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> journal.findExact(id, "tenant", "other", "fingerprint")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> journal.findExact(id, "tenant", "actor", "changed")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> journal.getOwned(id, "other", "actor")).isInstanceOf(RuntimeException.class);
  }

  @Test
  void staleWorkersCannotOverwriteTheNewLeaseOrTheCommittedSnapshot() {
    journal.accept(id, "fingerprint", accepted);
    var first = journal.claim(id);
    assertThat(journal.claim(id)).isNull();
    clock.advance(91);
    var next = journal.claim(id);
    journal.captured(id, first.token(), new ApplicationFormRecordView("wrong", 99, FormRecordState.SUBMITTED, Map.of()));
    assertThat(journal.getOwned(id, "tenant", "actor").step()).isEqualTo("PREPARED");
    journal.captured(id, next.token(), new ApplicationFormRecordView("record", 0, FormRecordState.SUBMITTED, Map.of("name", "saved")));
    journal.failed(id, first.token(), "LATE_FAILURE", true);
    assertThat(journal.getOwned(id, "tenant", "actor").step()).isEqualTo("DATA_COMMITTED");
    assertThat(journal.getOwned(id, "tenant", "actor").errorCode()).isNull();
  }

  @Test
  void concurrentWorkersAcquireOnlyOneLease() throws Exception {
    journal.accept(id, "fingerprint", accepted);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> journal.claim(id));
      var second = workers.submit(() -> journal.claim(id));
      var results = java.util.Arrays.asList(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertThat(results.stream().filter(java.util.Objects::nonNull).count()).isEqualTo(1);
    }
  }

  private ProcessInstanceSummary process(String status) {
    return new ProcessInstanceSummary("process", "definition", "request", "申请", 1, "app-form:" + id, "采购申请",
        "actor", status, Instant.EPOCH, "RUNNING".equals(status) ? null : clock.instant());
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-14T00:00:00Z");
    void advance(long seconds) { now = now.plusSeconds(seconds); }
    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
  }
}
