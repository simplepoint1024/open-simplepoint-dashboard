package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.model.FormCalculation;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormWorkflowSubmissionCheck;
import org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormSubmissionEntity;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.model.WorkflowInvocationContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
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
  private final ApplicationFormRecordServiceImpl records =
      mock(ApplicationFormRecordServiceImpl.class);
  private final WorkflowBusinessRuntimeService workflow =
      mock(WorkflowBusinessRuntimeService.class);
  private final String id = "a".repeat(64);
  private AcceptedFormSubmission accepted;

  @BeforeEach
  void setup() {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting(
                "hibernate.connection.url",
                "jdbc:h2:mem:submission-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .build();
    factory =
        new MetadataSources(registry)
            .addAnnotatedClass(ApplicationFormEntity.class)
            .addAnnotatedClass(
                org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity.class)
            .addAnnotatedClass(
                org.simplepoint.plugin.app.form.entity.ApplicationResourceOfflineEntity.class)
            .addAnnotatedClass(
                org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity.class)
            .addAnnotatedClass(ApplicationFormSubmissionEntity.class)
            .addAnnotatedClass(org.simplepoint.plugin.app.form.entity.ApplicationRemovedSubmissionEntity.class)
            .buildMetadata()
            .buildSessionFactory();
    manager = SharedEntityManagerCreator.createSharedEntityManager(factory);
    var transactions = new JpaTransactionManager(factory);
    codec = new FormSubmissionCodec(new ObjectMapper().findAndRegisterModules());
    ProxyFactory proxy = new ProxyFactory(new FormSubmissionJournal(manager, codec, clock));
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    journal = (FormSubmissionJournal) proxy.getProxy();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              var form = new ApplicationFormEntity();
              form.setId("form");
              form.setTenantId("tenant");
              form.setApplicationId("app");
              form.setCode("request");
              form.setName("申请");
              form.setStatus(ApplicationFormStatus.PUBLISHED);
              form.setCreatedAt(clock.instant());
              form.setUpdatedAt(clock.instant());
              manager.persist(form);
            });
    var document =
        new ApplicationFormDocument(
            "1",
            List.of(
                new FormFieldDefinition(
                    "name",
                    "name",
                    "名称",
                    "名称",
                    FormFieldType.STRING,
                    FormStorageMode.STORED,
                    true,
                    true,
                    120,
                    null,
                    null,
                    null,
                    List.of())),
            List.of(),
            List.of(
                new FormSceneDefinition(
                    "main",
                    "main",
                    "申请",
                    FormSceneType.MAIN,
                    null,
                    new FormComponentDefinition(
                        "root",
                        "layout.form",
                        "1",
                        null,
                        24,
                        null,
                        null,
                        null,
                        null,
                        false,
                        Map.of(),
                        List.of(
                            new FormComponentDefinition(
                                "name-input",
                                "field.text",
                                "1",
                                "name",
                                24,
                                null,
                                null,
                                null,
                                null,
                                false,
                                Map.of(),
                                List.of()))))),
            List.of());
    var form =
        new PublishedApplicationForm(
            "tenant",
            "app",
            "page",
            "申请",
            "form",
            "申请",
            1,
            "app.page",
            "model",
            1,
            "f".repeat(64),
            document,
            1,
            1,
            List.of());
    var command =
        new SubmitApplicationFormCommand(
            new ApplicationFormRuntimeIdentity("form", 1),
            "request-key",
            null,
            null,
            "definition",
            1,
            null,
            "采购申请",
            Map.of("name", "原始申请"),
            Map.of(),
            List.of());
    var binding =
        new WorkflowFormContract.Binding("simplepoint-app", "form", 1, Map.of("name", "name"));
    var contract =
        new WorkflowFormContract(
            1,
            List.of(new WorkflowFormContract.Variable("name", "名称", "string", "both", true, null)),
            binding,
            Map.of("review", binding));
    accepted =
        new AcceptedFormSubmission(
            form,
            command,
            new DnaInvocationContext(
                "tenant",
                "actor",
                null,
                "app.page",
                false,
                Set.of("app.page", "app.page.records.create"),
                "SELF",
                Set.of(),
                false,
                List.of(),
                Map.of()),
            new WorkflowInvocationContext(
                "tenant", "actor", false, Set.of("workflow.instances.start"), Set.of()),
            new RuntimeForm(1, contract, binding, Map.of("name", "原始申请")),
            null);
    persistVersion(1, accepted.form().document());
    when(records.captureCreate(any(), any(), org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(
            new ApplicationFormRecordView(
                "record", 0, FormRecordState.SUBMITTED, Map.of("name", "提交快照")));
  }

  private void persistVersion(int number, ApplicationFormDocument document) {
    new TransactionTemplate(new JpaTransactionManager(factory))
        .executeWithoutResult(
            status -> {
              var encoded = new FormDocumentCodec(new ObjectMapper()).encode(document);
              var version =
                  new org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity();
              version.setId("version-" + number);
              version.setTenantId("tenant");
              version.setFormId("form");
              version.setVersionNumber(number);
              version.setDocumentJson(encoded.json());
              version.setChecksum(encoded.checksum());
              version.setStorageSchemaJson("{}");
              version.setStorageChecksum("f".repeat(64));
              version.setPhysicalModelVersion(number);
              version.setRendererVersion("1");
              version.setCreatedAt(clock.instant());
              manager.persist(version);
              var form = manager.find(ApplicationFormEntity.class, "form");
              form.setPublishedVersion(number);
              form.setLatestVersion(number);
              form.setPhysicalModelId("model");
            });
  }

  @AfterEach
  void close() {
    org.simplepoint.core.RequestContextHolder.clearContext(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void frozenCaptureRequirementsSurvivePersistenceAndDataResponseLoss() {
    var requirements =
        FormSubmissionMapping.captureRequirements(accepted.form(), accepted.workflow());
    assertThat(requirements)
        .containsExactly(
            new org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement(
                "name", true, 4000, null, false, false, false));
    var frozen =
        new AcceptedFormSubmission(
            accepted.form(),
            accepted.command(),
            accepted.dataAuthority(),
            accepted.workflowAuthority(),
            accepted.workflow(),
            accepted.previousInstanceId(),
            requirements);
    journal.accept(id, "frozen-fingerprint", frozen);
    var captured =
        new ApplicationFormRecordView(
            "record", 0, FormRecordState.SUBMITTED, Map.of("name", "最终值"));
    when(records.captureCreate(any(), any(), eq(requirements)))
        .thenThrow(new IllegalStateException("data response lost"))
        .thenReturn(captured);
    new FormSubmissionProcessor(journal, records, new FormWorkflowGateway(workflow)).advance(id);
    assertThat(journal.getOwned(id, "tenant", "actor").step()).isEqualTo("PREPARED");
    clock.advance(6);
    when(workflow.start(any(), eq("definition"), any())).thenReturn(process("RUNNING"));
    new FormSubmissionProcessor(journal, records, new FormWorkflowGateway(workflow)).advance(id);
    verify(records, times(2)).captureCreate(any(), any(), eq(requirements));
    assertThat(journal.getOwned(id, "tenant", "actor").processStatus()).isEqualTo("RUNNING");
  }

  @Test
  void relatedScopeSurvivesRestartAndCannotBeReadThroughAnotherParent() {
    var original = accepted.command();
    var command =
        new SubmitApplicationFormCommand(
            original.runtime(),
            original.idempotencyKey(),
            "record",
            3L,
            original.definitionId(),
            original.definitionVersion(),
            null,
            original.title(),
            Map.of(),
            original.variables(),
            original.ccUserIds());
    var scope =
        new org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope(
            "1",
            "customer",
            "customer-model",
            "aaaaaaaa-1111-2222-3333-000000000001",
            org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope.Mode.MAINTAIN,
            null);
    var frozen =
        new AcceptedFormSubmission(
            accepted.form(),
            command,
            accepted.dataAuthority(),
            accepted.workflowAuthority(),
            accepted.workflow(),
            null,
            null,
            scope);
    assertThat(codec.read(codec.write(frozen), AcceptedFormSubmission.class)).isEqualTo(frozen);
    journal.accept(id, "related-fingerprint", frozen);
    assertThat(journal.getOwnedRelated(id, "tenant", "actor", scope).step()).isEqualTo("PREPARED");
    var wrong =
        new org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope(
            "1",
            "customer",
            "customer-model",
            "aaaaaaaa-1111-2222-3333-000000000002",
            scope.mode(),
            null);
    assertThatThrownBy(() -> journal.getOwnedRelated(id, "tenant", "actor", wrong))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> journal.getOwnedRelated(id, "other", "actor", scope))
        .isInstanceOf(ApplicationFormException.class);
    when(records.captureRelatedUpdate(
            any(), eq("record"), any(), org.mockito.ArgumentMatchers.isNull(), eq(scope)))
        .thenThrow(new IllegalStateException("lost scoped capture response"))
        .thenReturn(
            new ApplicationFormRecordView(
                "record", 4, FormRecordState.SUBMITTED, Map.of("name", "关联快照")));
    new FormSubmissionProcessor(journal, records, new FormWorkflowGateway(workflow)).advance(id);
    assertThat(journal.getOwned(id, "tenant", "actor").step()).isEqualTo("PREPARED");
    clock.advance(6);
    when(workflow.start(any(), eq("definition"), any())).thenReturn(process("RUNNING"));
    new FormSubmissionProcessor(journal, records, new FormWorkflowGateway(workflow)).advance(id);
    var commands =
        org.mockito.ArgumentCaptor.forClass(
            org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand.class);
    verify(records, times(2))
        .captureRelatedUpdate(
            any(),
            eq("record"),
            commands.capture(),
            org.mockito.ArgumentMatchers.isNull(),
            eq(scope));
    assertThat(commands.getAllValues().get(0)).isEqualTo(commands.getAllValues().get(1));
    assertThat(commands.getValue().values()).isEmpty();
    assertThat(journal.getOwnedRelated(id, "tenant", "actor", scope).processStatus())
        .isEqualTo("RUNNING");
    org.mockito.Mockito.verify(records, org.mockito.Mockito.never())
        .captureUpdate(any(), any(), any(), any());
    org.mockito.Mockito.verify(records, org.mockito.Mockito.never())
        .captureCreate(any(), any(), any());
  }

  @Test
  void relatedSubmissionReplayCannotChangeParentOrDowngradeToAnOrdinarySubmission() {
    var original = accepted.command();
    var command =
        new SubmitApplicationFormCommand(
            original.runtime(),
            original.idempotencyKey(),
            "record",
            3L,
            original.definitionId(),
            original.definitionVersion(),
            null,
            original.title(),
            Map.of(),
            original.variables(),
            original.ccUserIds());
    var scope =
        new org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope(
            "1",
            "customer",
            "customer-model",
            "aaaaaaaa-1111-2222-3333-000000000001",
            org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope.Mode.MAINTAIN,
            null);
    var frozen =
        new AcceptedFormSubmission(
            accepted.form(),
            command,
            accepted.dataAuthority(),
            accepted.workflowAuthority(),
            accepted.workflow(),
            null,
            null,
            scope);
    String identity = codec.fingerprint(List.of("tenant", "app", "page", command.idempotencyKey()));
    String fingerprint =
        codec.fingerprint(List.of("app.submission.related.v1", "app", "page", command, scope));
    var saved = journal.accept(identity, fingerprint, frozen);
    var resolver = mock(PublishedApplicationFormResolver.class);
    when(resolver.resolve("app", "page")).thenReturn(accepted.form());
    var actors = mock(FormRequestContext.class);
    when(actors.requireActor()).thenReturn(new FormRequestContext.FormActor("tenant", "actor"));
    var contexts = mock(DnaInvocationContextFactory.class);
    var processor = mock(FormSubmissionProcessor.class);
    var service =
        new ApplicationFormSubmissionService(
            resolver,
            records,
            contexts,
            new FormWorkflowGateway(workflow),
            journal,
            processor,
            codec,
            actors);
    assertThat(service.submitRelated(accepted.form(), scope, command)).isEqualTo(saved);
    verify(processor).advance(identity);
    verifyNoInteractions(records, contexts, workflow);
    assertThatThrownBy(() -> service.submit("app", "page", command))
        .isInstanceOf(ApplicationFormException.class);
    var changed =
        new org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope(
            "1",
            "customer",
            "customer-model",
            "aaaaaaaa-1111-2222-3333-000000000002",
            scope.mode(),
            null);
    assertThatThrownBy(() -> service.submitRelated(accepted.form(), changed, command))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> service.submitRelated(accepted.form(), scope, original))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void acceptedLegacyShapeKeepsAbsentRequirementsWhileNewMetadataRoundTrips() {
    String legacy = codec.write(accepted);
    assertThat(legacy).doesNotContain("snapshotRequirements", "relatedScope");
    assertThat(codec.read(legacy, AcceptedFormSubmission.class).snapshotRequirements()).isNull();
    var requirements =
        FormSubmissionMapping.captureRequirements(accepted.form(), accepted.workflow());
    var frozen =
        new AcceptedFormSubmission(
            accepted.form(),
            accepted.command(),
            accepted.dataAuthority(),
            accepted.workflowAuthority(),
            accepted.workflow(),
            accepted.previousInstanceId(),
            requirements);
    assertThat(codec.read(codec.write(frozen), AcceptedFormSubmission.class)).isEqualTo(frozen);
    assertThat(codec.fingerprint(frozen)).isNotEqualTo(codec.fingerprint(accepted));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode.class,
      names = {"RECORD_OPERATION_REJECTED", "RECORD_REFERENCE_CONFLICT"})
  void onlyDurableDataRejectionEndsAnUncommittedIntentAndRetryCannotReviveIt(
      org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode rejectionCode) {
    journal.accept(id, "fingerprint", accepted);
    when(records.captureCreate(any(), any(), org.mockito.ArgumentMatchers.isNull()))
        .thenThrow(new ApplicationFormException(rejectionCode, "Durable negative receipt"));
    var processor =
        new FormSubmissionProcessor(journal, records, new FormWorkflowGateway(workflow));
    processor.advance(id);
    var failed = journal.getOwned(id, "tenant", "actor");
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.step()).isEqualTo("TERMINAL");
    assertThat(failed.recordId()).isNull();
    assertThat(failed.processInstanceId()).isNull();
    assertThat(failed.errorCode()).isEqualTo(rejectionCode.name());
    journal.retry(id, "tenant", "actor");
    clock.advance(600);
    processor.advance(id);
    assertThat(journal.recoverable(20)).doesNotContain(id);
    assertThat(journal.getOwned(id, "tenant", "actor")).isEqualTo(failed);
    verifyNoInteractions(workflow);
    verify(records, times(1)).captureCreate(any(), any(), org.mockito.ArgumentMatchers.isNull());
  }

  @Test
  void rejectionCannotEraseCapturedDataOrUseStaleLeases() {
    journal.accept(id, "fingerprint", accepted);
    var first = journal.claim(id);
    journal.rejected(id, "stale-token");
    assertThat(journal.getOwned(id, "tenant", "actor").step()).isEqualTo("PREPARED");
    journal.captured(
        id,
        first.token(),
        new ApplicationFormRecordView(
            "record", 0, FormRecordState.SUBMITTED, Map.of("name", "frozen")));
    var next = journal.claim(id);
    assertThatThrownBy(() -> journal.rejected(id, next.token()))
        .isInstanceOf(ApplicationFormException.class);
    assertThat(journal.getOwned(id, "tenant", "actor").step()).isEqualTo("DATA_COMMITTED");
    assertThat(journal.snapshot(id, "tenant").record().values()).containsEntry("name", "frozen");
  }

  @Test
  void restartAfterLostWorkflowResponseReusesTheCapturedDataAndTheSameStartKey() {
    journal.accept(id, "fingerprint", accepted);
    when(workflow.start(any(), eq("definition"), any()))
        .thenThrow(new IllegalStateException("response lost"))
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
    verify(records, times(1)).captureCreate(any(), any(), org.mockito.ArgumentMatchers.isNull());
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
    var properties =
        new org.simplepoint.plugin.app.form.configuration.ApplicationFormWorkflowRemoteProperties();
    properties.setAllowedCallers(Set.of("workflow"));
    var provider =
        new ApplicationFormWorkflowPortImpl(
            manager, new FormDocumentCodec(new ObjectMapper()), codec, properties);
    var principal =
        new org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal(
            "workflow",
            Map.of("client_id", "workflow", "grant_type", "client_credentials"),
            List.of());
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(
            new org.springframework.security.authentication.TestingAuthenticationToken(
                principal, null, "SCOPE_service-router.invoke"));
    var check =
        new FormWorkflowSubmissionCheck(
            "tenant",
            "actor",
            "definition",
            1,
            null,
            "app-form:" + id,
            "form",
            1,
            "start",
            null,
            Map.of("name", "提交快照"),
            false,
            Set.of("app.page"));
    assertThatThrownBy(() -> provider.validateSubmission(check))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    var lease = journal.claim(id);
    journal.captured(
        id,
        lease.token(),
        new ApplicationFormRecordView(
            "record", 0, FormRecordState.SUBMITTED, Map.of("name", "提交快照")));
    provider.validateSubmission(check);
    assertThatThrownBy(
            () ->
                provider.validateSubmission(
                    new FormWorkflowSubmissionCheck(
                        "tenant",
                        "actor",
                        "definition",
                        1,
                        null,
                        "app-form:" + id,
                        "form",
                        1,
                        "start",
                        null,
                        Map.of("name", "伪造值"),
                        false,
                        Set.of("app.page"))))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    assertThatThrownBy(
            () ->
                provider.validateSubmission(
                    new FormWorkflowSubmissionCheck(
                        "tenant",
                        "another",
                        "definition",
                        1,
                        null,
                        "app-form:" + id,
                        "form",
                        1,
                        "start",
                        null,
                        Map.of("name", "提交快照"),
                        false,
                        Set.of("app.page"))))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    assertThatThrownBy(
            () ->
                provider.validateSubmission(
                    new FormWorkflowSubmissionCheck(
                        "other-tenant",
                        "actor",
                        "definition",
                        1,
                        null,
                        "app-form:" + id,
                        "form",
                        1,
                        "start",
                        null,
                        Map.of("name", "提交快照"),
                        false,
                        Set.of("app.page"))))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    provider.validateSubmission(
        new FormWorkflowSubmissionCheck(
            "tenant",
            "approver",
            "definition",
            1,
            "process",
            "app-form:" + id,
            "form",
            1,
            "task",
            "review",
            Map.of(),
            false,
            Set.of("app.page")));
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(
            new org.springframework.security.authentication.TestingAuthenticationToken(
                "workflow", null, "SCOPE_service-router.invoke"));
    assertThatThrownBy(() -> provider.validateSubmission(check))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
  }

  @Test
  void taskSnapshotStillRequiresCurrentPagePermissionAndReappliesFieldVisibility() {
    journal.accept(id, "fingerprint", accepted);
    var lease = journal.claim(id);
    journal.captured(
        id,
        lease.token(),
        new ApplicationFormRecordView(
            "record", 0, FormRecordState.SUBMITTED, Map.of("name", "敏感名称")));
    var actors = new FormRequestContext();
    var resolver =
        new PublishedApplicationFormResolver(
            manager,
            actors,
            mock(org.simplepoint.plugin.app.form.api.service.ApplicationPageAccessPort.class),
            new FormDocumentCodec(new ObjectMapper()));
    var recordReader =
        mock(org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordService.class);
    var historyReader = mock(FormSubmissionHistory.class);
    when(historyReader.read(any()))
        .thenAnswer(call -> ((FormSubmissionJournal.Snapshot) call.getArgument(0)).record());
    final var snapshots =
        new FormSubmissionSnapshotService(
            new FormWorkflowGateway(workflow),
            journal,
            resolver,
            new FormRuntimeDocumentCompiler(),
            new DnaInvocationContextFactory(
                actors, org.mockito.Mockito.mock(FormRelationTargetResolver.class)),
            actors,
            new FormRecordIdentityLabels(
                mock(org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory.class)),
            historyReader,
            recordReader);
    when(workflow.taskForm(any(), eq("task")))
        .thenReturn(
            new RuntimeForm(
                1,
                accepted.workflow().formContract(),
                accepted.workflow().binding(),
                Map.of(),
                "process",
                "app-form:" + id,
                "definition",
                Set.of("name")));
    when(workflow.instance(any(), eq("process"))).thenReturn(process("COMPLETED"));
    var denied = new org.simplepoint.core.AuthorizationContext();
    denied.setUserId("approver");
    denied.setScopeType(org.simplepoint.core.AuthorizationScopeType.TENANT);
    denied.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    denied.setResources(Set.of());
    org.simplepoint.core.RequestContextHolder.setContext(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, denied);
    assertThatThrownBy(() -> snapshots.task("task"))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    assertThatThrownBy(() -> snapshots.instance("process"))
        .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
    for (String policy : List.of("HIDDEN", "MASKED", "VISIBLE")) {
      var reader = new org.simplepoint.core.AuthorizationContext();
      reader.setUserId("approver");
      reader.setScopeType(org.simplepoint.core.AuthorizationScopeType.TENANT);
      reader.setAttributes(Map.of("X-Tenant-Id", "tenant"));
      reader.setResources(Set.of("app.page"));
      reader.setResourceFieldPermissions(
          Map.of(
              "app.page",
              Map.of(
                  org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel.key("model")
                      + "#name",
                  policy)));
      org.simplepoint.core.RequestContextHolder.setContext(
          org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, reader);
      var result = snapshots.task("task");
      var history = snapshots.instance("process");
      assertThat(
              snapshots
                  .forRecord(
                      accepted.form().applicationId(), accepted.form().pageId(), "record", id)
                  .record()
                  .values())
          .isEqualTo(history.record().values());
      assertThatThrownBy(
              () ->
                  snapshots.forRecord(
                      accepted.form().applicationId(),
                      accepted.form().pageId(),
                      "wrong-record",
                      id))
          .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
      assertThatThrownBy(
              () -> snapshots.forRecord("wrong-app", accepted.form().pageId(), "record", id))
          .isInstanceOf(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class);
      assertThat(history.record().values()).isEqualTo(result.record().values());
      assertThat(history.form().document()).isEqualTo(result.form().document());
      assertThat(history.form().formVersion()).isEqualTo(1);
      if ("HIDDEN".equals(policy)) {
        assertThat(result.record().values()).doesNotContainKey("name");
        assertThat(result.form().document().fields()).isEmpty();
      } else {
        assertThat(result.record().values())
            .containsEntry("name", "MASKED".equals(policy) ? "***" : "敏感名称");
      }
    }
    // A new formula can introduce a protected dependency absent from the frozen submission
    // document.
    var capturedDocument = accepted.form().document();
    var computed =
        new FormFieldDefinition(
            "name",
            "name",
            "New label",
            "Name",
            FormFieldType.STRING,
            FormStorageMode.COMPUTED,
            false,
            false,
            120,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            new FormCalculation("1", "UPPER([private-source])"));
    var secret =
        new FormFieldDefinition(
            "private-source",
            "private_source",
            "Private",
            "Private",
            FormFieldType.STRING,
            FormStorageMode.STORED,
            false,
            false,
            120,
            null,
            null,
            null,
            List.of());
    persistVersion(
        2,
        new ApplicationFormDocument(
            "1", List.of(computed, secret), List.of(), capturedDocument.scenes(), List.of()));
    var reader = new org.simplepoint.core.AuthorizationContext();
    reader.setUserId("approver");
    reader.setScopeType(org.simplepoint.core.AuthorizationScopeType.TENANT);
    reader.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    reader.setResources(Set.of("app.page"));
    reader.setResourceFieldPermissions(
        Map.of(
            "app.page",
            Map.of(
                org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel.key("model")
                    + "#"
                    + org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel.field(
                        "private-source"),
                "MASKED")));
    org.simplepoint.core.RequestContextHolder.setContext(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, reader);
    assertThat(snapshots.instance("process").record().values()).doesNotContainKey("name");
    assertThat(snapshots.task("task").form().document().fields()).isEmpty();
    var unrestricted = new org.simplepoint.core.AuthorizationContext();
    unrestricted.setUserId("approver");
    unrestricted.setScopeType(org.simplepoint.core.AuthorizationScopeType.TENANT);
    unrestricted.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    unrestricted.setResources(Set.of("app.page"));
    org.simplepoint.core.RequestContextHolder.setContext(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, unrestricted);
    var restored = snapshots.instance("process");
    assertThat(restored.record().values()).containsEntry("name", "敏感名称");
    assertThat(restored.form().formVersion()).isEqualTo(1);
    assertThat(restored.form().document().fields().getFirst().label()).isEqualTo("名称");
    assertThat(restored.form().document().fields())
        .noneMatch(field -> field.fieldId().equals("private-source"));

    for (var binding :
        List.of(
            new WorkflowFormContract.Binding("simplepoint-app", "form", 2, Map.of()),
            new WorkflowFormContract.Binding("simplepoint-app", "other-form", 1, Map.of()),
            new WorkflowFormContract.Binding("other-provider", "form", 1, Map.of()),
            new WorkflowFormContract.Binding(
                "simplepoint-app", "form", 1, Map.of(), "missing-scene"))) {
      when(workflow.taskForm(any(), eq("task")))
          .thenReturn(
              new RuntimeForm(
                  1,
                  accepted.workflow().formContract(),
                  binding,
                  Map.of(),
                  "process",
                  "app-form:" + id,
                  "definition",
                  Set.of("name")));
      assertThatThrownBy(() -> snapshots.task("task")).isInstanceOf(ApplicationFormException.class);
    }
  }

  @Test
  void operationIdentityAndTenantActorRemainBoundAcrossRetries() {
    var original = journal.accept(id, "fingerprint", accepted);
    assertThat(journal.accept(id, "fingerprint", accepted)).isEqualTo(original);
    assertThatThrownBy(() -> journal.findExact(id, "other", "actor", "fingerprint"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> journal.findExact(id, "tenant", "other", "fingerprint"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> journal.findExact(id, "tenant", "actor", "changed"))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> journal.getOwned(id, "other", "actor"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void staleWorkersCannotOverwriteTheNewLeaseOrTheCommittedSnapshot() {
    journal.accept(id, "fingerprint", accepted);
    var first = journal.claim(id);
    assertThat(journal.claim(id)).isNull();
    clock.advance(91);
    var next = journal.claim(id);
    journal.captured(
        id,
        first.token(),
        new ApplicationFormRecordView("wrong", 99, FormRecordState.SUBMITTED, Map.of()));
    assertThat(journal.getOwned(id, "tenant", "actor").step()).isEqualTo("PREPARED");
    journal.captured(
        id,
        next.token(),
        new ApplicationFormRecordView(
            "record", 0, FormRecordState.SUBMITTED, Map.of("name", "saved")));
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
      var results =
          java.util.Arrays.asList(
              first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertThat(results.stream().filter(java.util.Objects::nonNull).count()).isEqualTo(1);
    }
  }

  private ProcessInstanceSummary process(String status) {
    return new ProcessInstanceSummary(
        "process",
        "definition",
        "request",
        "申请",
        1,
        "app-form:" + id,
        "采购申请",
        "actor",
        status,
        Instant.EPOCH,
        "RUNNING".equals(status) ? null : clock.instant());
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-14T00:00:00Z");

    void advance(long seconds) {
      now = now.plusSeconds(seconds);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
