package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Real persistence predicates plus the authoritative runtime permission/binding boundary. */
class FormRelationTargetResolverTest {
  private SessionFactory factory;
  private Session session;
  private PublishedApplicationFormResolver forms;
  private FormRelationTargetResolver resolver;
  private PublishedApplicationForm source;
  private PublishedApplicationForm target;

  @BeforeEach
  void setup() {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting(
                "hibernate.connection.url", "jdbc:h2:mem:relation-target-" + UUID.randomUUID())
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .build();
    factory =
        new MetadataSources(registry)
            .addAnnotatedClass(ApplicationFormPageBindingEntity.class)
            .buildMetadata()
            .buildSessionFactory();
    session = factory.openSession();
    forms = mock(PublishedApplicationFormResolver.class);
    var context = mock(FormRequestContext.class);
    when(context.requireActor()).thenReturn(new FormRequestContext.FormActor("tenant-a", "user"));
    resolver = new FormRelationTargetResolver(session, context, forms);
    var relation =
        new FormRelationDefinition(
            "1",
            "customer",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField(null, "customer"),
            new FormRelationTarget("target-app", "target-form", "selected-resource"));
    source =
        form(
            "tenant-a",
            "source-app",
            "source-page",
            "source-form",
            "source-resource",
            List.of(relation));
    target =
        form(
            "tenant-a",
            "target-app",
            "selected-page",
            "target-form",
            "target-resource-code",
            List.of());
    when(forms.resolve("target-app", "selected-page")).thenReturn(target);
  }

  @AfterEach
  void cleanup() {
    if (session != null) {
      session.close();
    }
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void onlyExactTenantApplicationFormResourceAndActiveBindingIsEligible() {
    binding(
        "other-tenant",
        "target-app",
        "target-form",
        "selected-resource",
        "foreign-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    binding(
        "tenant-a",
        "other-app",
        "target-form",
        "selected-resource",
        "foreign-app-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    binding(
        "tenant-a",
        "target-app",
        "other-form",
        "selected-resource",
        "retargeted-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "other-resource",
        "alternative-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "retired-page",
        ApplicationFormPageBindingStatus.RETIRED);
    assertUnavailable(() -> resolver.resolve(source, "customer"));
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "selected-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    var resolved = resolver.resolve(source, "customer");
    assertThat(resolved.definition()).isEqualTo(source.document().relations().getFirst());
    assertThat(resolved.target()).isEqualTo(target);
    verify(forms).resolve("target-app", "selected-page");
    verify(forms, never()).resolve("target-app", "alternative-page");
  }

  @Test
  void authoringMetadataUsesTheSameExactEntryAndCurrentTargetAuthorization() {
    var identity = source.document().relations().getFirst().target();
    assertUnavailable(() -> resolver.describeTarget(identity));
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "selected-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    assertThat(resolver.describeTarget(identity)).isEqualTo(target);
    when(forms.resolve("target-app", "selected-page"))
        .thenThrow(
            new ApplicationFormException(
                ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "Revoked"));
    assertUnavailable(() -> resolver.describeTarget(identity));
  }

  @Test
  void currentEntryRebindingOrTenantChangeDoesNotRedirectTheRelation() {
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "selected-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    for (var changed :
        List.of(
            form(
                "tenant-a",
                "target-app",
                "selected-page",
                "other-form",
                "target-resource-code",
                List.of()),
            form(
                "tenant-b",
                "target-app",
                "selected-page",
                "target-form",
                "target-resource-code",
                List.of()),
            form(
                "tenant-a",
                "target-app",
                "selected-page",
                "target-form",
                "other-resource-code",
                List.of()))) {
      when(forms.resolve("target-app", "selected-page")).thenReturn(changed);
      assertUnavailable(() -> resolver.resolve(source, "customer"));
    }
  }

  @Test
  void bothSourceAndTargetEntryGrantsRemainMandatory() {
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "selected-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    var denied =
        new ApplicationFormException(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "revoked");
    when(forms.resolve("target-app", "selected-page")).thenThrow(denied);
    assertUnavailable(() -> resolver.resolve(source, "customer"));
    doThrow(denied).when(forms).requireRead(source);
    assertUnavailable(() -> resolver.resolve(source, "customer"));
    assertUnavailable(
        () ->
            resolver.resolve(
                form(
                    "other-tenant",
                    "source-app",
                    "source-page",
                    "source-form",
                    "source-resource",
                    source.document().relations()),
                "customer"));
  }

  @Test
  void freezeCapturesTheAuthorizedEntryAndRuntimeRejectsModelReplacement() {
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "selected-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    var field =
        new org.simplepoint.plugin.app.form.api.model.FormFieldDefinition(
            "customer",
            "customer_id",
            "Customer",
            "Customer",
            org.simplepoint.plugin.app.form.api.model.FormFieldType.UUID,
            org.simplepoint.plugin.app.form.api.model.FormStorageMode.REFERENCE,
            false,
            false,
            null,
            null,
            null,
            null,
            List.of());
    var document =
        new ApplicationFormDocument(
            "1",
            List.of(field),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            source.document().relations());
    assertThat(resolver.freeze(document)).containsExactlyElementsOf(source.relationBindings());
  }

  @Test
  void writeParticipantsMayOmitUnavailableTargetsButNeverHideSourceOrInfrastructureFailure() {
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "selected-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    assertThat(resolver.availableForWrite(source)).containsExactly(target);
    when(forms.resolve("target-app", "selected-page"))
        .thenThrow(
            new ApplicationFormException(
                ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "revoked"));
    assertThat(resolver.availableForWrite(source)).isEmpty();
    doThrow(
            new ApplicationFormException(
                ApplicationFormErrorCode.PERSISTENCE_FAILED, "storage unavailable"))
        .when(forms)
        .resolve("target-app", "selected-page");
    assertThatThrownBy(() -> resolver.availableForWrite(source))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.PERSISTENCE_FAILED));
    doThrow(
            new ApplicationFormException(
                ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "source revoked"))
        .when(forms)
        .requireRead(source);
    assertUnavailable(() -> resolver.availableForWrite(source));
  }

  @Test
  void freezingChecksQualificationAgainstTheResolvedTargetSchema() {
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "selected-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    var field =
        new org.simplepoint.plugin.app.form.api.model.FormFieldDefinition(
            "customer",
            "customer_id",
            "Customer",
            "Customer",
            org.simplepoint.plugin.app.form.api.model.FormFieldType.UUID,
            org.simplepoint.plugin.app.form.api.model.FormStorageMode.REFERENCE,
            false,
            false,
            null,
            null,
            null,
            null,
            List.of());
    var document =
        FormRelationQualificationTest.configure(
            new ApplicationFormDocument(
                "1",
                List.of(field),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                source.document().relations()),
            org.simplepoint.plugin.app.form.api.model.relation.FormRelationQualification.Recheck
                .ON_SUBMIT);
    assertThatThrownBy(() -> resolver.freeze(document))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.DOCUMENT_INVALID));
    var targetDocument =
        new ApplicationFormDocument(
            "1",
            List.of(
                FormRelationQualificationTest.field(
                    org.simplepoint.plugin.app.form.api.model.FormFieldType.BOOLEAN,
                    org.simplepoint.plugin.app.form.api.model.FormStorageMode.STORED)),
            List.of(),
            List.of(),
            List.of());
    var qualified =
        new PublishedApplicationForm(
            target.tenantId(),
            target.applicationId(),
            target.pageId(),
            target.pageName(),
            target.formId(),
            target.formName(),
            target.formVersion(),
            target.resourceCode(),
            target.physicalModelId(),
            target.physicalModelVersion(),
            target.storageChecksum(),
            targetDocument);
    when(forms.resolve("target-app", "selected-page")).thenReturn(qualified);
    var expected = source.relationBindings().getFirst();
    assertThat(resolver.freeze(document))
        .containsExactly(
            new FormRelationBinding(
                expected.relationId(),
                expected.target(),
                expected.targetPageId(),
                expected.targetResourceCode(),
                expected.targetPhysicalModelId(),
                List.of(
                    new org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField(
                        "active",
                        org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType.BOOLEAN))));
    var changed =
        new PublishedApplicationForm(
            target.tenantId(),
            target.applicationId(),
            target.pageId(),
            target.pageName(),
            target.formId(),
            target.formName(),
            target.formVersion(),
            target.resourceCode(),
            "replacement-model",
            target.physicalModelVersion(),
            target.storageChecksum(),
            target.document());
    when(forms.resolve("target-app", "selected-page")).thenReturn(changed);
    assertUnavailable(() -> resolver.resolve(source, "customer"));
  }

  @Test
  void missingFrozenIdentityCannotBeRecoveredByLookingUpTheCurrentTarget() {
    var legacy =
        new PublishedApplicationForm(
            source.tenantId(),
            source.applicationId(),
            source.pageId(),
            source.pageName(),
            source.formId(),
            source.formName(),
            source.formVersion(),
            source.resourceCode(),
            source.physicalModelId(),
            source.physicalModelVersion(),
            source.storageChecksum(),
            source.document());
    assertUnavailable(() -> resolver.resolve(legacy, "customer"));
    assertThatThrownBy(() -> resolver.availableForWrite(legacy))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.PERSISTENCE_FAILED));
    verify(forms, never()).resolve("target-app", "selected-page");
  }

  @Test
  void duplicateActiveBindingAndUnknownRelationFailClosed() {
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "selected-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "duplicate-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    assertUnavailable(() -> resolver.resolve(source, "customer"));
    assertUnavailable(() -> resolver.resolve(source, "injected"));
    verify(forms, never()).resolve("target-app", "selected-page");
  }

  @Test
  void optionalTargetDenialsDoNotMarkTheCallerTransactionForRollback() {
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "selected-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    when(forms.resolve("target-app", "selected-page"))
        .thenThrow(
            new ApplicationFormException(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "Revoked"));
    var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
    var transactions = new DataSourceTransactionManager(dataSource);
    var advice =
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource());
    var formsProxy = new ProxyFactory(forms);
    formsProxy.setProxyTargetClass(true);
    formsProxy.addAdvice(advice);
    var context = mock(FormRequestContext.class);
    when(context.requireActor()).thenReturn(new FormRequestContext.FormActor("tenant-a", "user"));
    var relationProxy = new ProxyFactory(new FormRelationTargetResolver(
        session, context, (PublishedApplicationFormResolver) formsProxy.getProxy()));
    relationProxy.setProxyTargetClass(true);
    relationProxy.addAdvice(advice);
    var scoped = (FormRelationTargetResolver) relationProxy.getProxy();
    var snapshotTarget = new RecordSnapshotTarget(
        "model-target-form", "target-app", "target-form", "target-resource-code");
    new TransactionTemplate(transactions).executeWithoutResult(status -> {
      assertThat(scoped.availableForWrite(source)).isEmpty();
      assertThat(scoped.snapshotTargets(source, List.of(snapshotTarget))).isEmpty();
      assertThat(status.isRollbackOnly()).isFalse();
    });
  }

  @Test
  void snapshotEntrancesRetainExactIdentityAfterTheSourceRelationWasRemoved() {
    var old =
        new org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget(
            "model-target-form", "target-app", "target-form", "target-resource-code");
    var current =
        form("tenant-a", "source-app", "source-page", "source-form", "source-resource", List.of());
    binding(
        "tenant-b",
        "target-app",
        "target-form",
        "selected-resource",
        "foreign-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "retired-page",
        ApplicationFormPageBindingStatus.RETIRED);
    assertThat(resolver.snapshotTargets(current, List.of(old))).isEmpty();
    binding(
        "tenant-a",
        "target-app",
        "target-form",
        "selected-resource",
        "selected-page",
        ApplicationFormPageBindingStatus.ACTIVE);
    assertThat(resolver.snapshotTargets(current, List.of(old))).containsExactly(target);
    var rebound =
        new org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget(
            "model-replaced", "target-app", "target-form", "target-resource-code");
    assertThat(resolver.snapshotTargets(current, List.of(rebound))).isEmpty();
    doThrow(
            new ApplicationFormException(
                ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "revoked"))
        .when(forms)
        .resolve("target-app", "selected-page");
    assertThat(resolver.snapshotTargets(current, List.of(old))).isEmpty();
    doThrow(
            new ApplicationFormException(
                ApplicationFormErrorCode.PERSISTENCE_FAILED, "storage failure"))
        .when(forms)
        .resolve("target-app", "selected-page");
    assertThatThrownBy(() -> resolver.snapshotTargets(current, List.of(old)))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.PERSISTENCE_FAILED));
  }

  private void binding(
      String tenant,
      String app,
      String form,
      String resource,
      String page,
      ApplicationFormPageBindingStatus status) {
    var value = new ApplicationFormPageBindingEntity();
    value.setId(UUID.randomUUID().toString());
    value.setTenantId(tenant);
    value.setApplicationId(app);
    value.setFormId(form);
    value.setPageId(page);
    value.setFormVersion(1);
    value.setSceneId("main");
    value.setIdempotencyKey(UUID.randomUUID().toString());
    value.setGeneration(1);
    value.setPolicyProtocolVersion(1);
    value.setStatus(status);
    value.setResourceManagedKey("managed-" + value.getId());
    value.setResourceId(resource);
    value.setResourceCode("target-resource-code");
    value.setCreatedAt(Instant.now());
    value.setUpdatedAt(Instant.now());
    session.beginTransaction();
    session.persist(value);
    session.getTransaction().commit();
    session.clear();
  }

  private static PublishedApplicationForm form(
      String tenant,
      String app,
      String page,
      String form,
      String resource,
      List<FormRelationDefinition> relations) {
    var document =
        new ApplicationFormDocument(
            "1", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, relations);
    return new PublishedApplicationForm(
        tenant,
        app,
        page,
        "Page",
        form,
        "Form",
        1,
        resource,
        "model-" + form,
        1,
        "a".repeat(64),
        document,
        1,
        0,
        List.of(),
        null,
        relations.stream()
            .map(
                relation ->
                    new FormRelationBinding(
                        relation.relationId(),
                        relation.target(),
                        "selected-page",
                        "target-resource-code",
                        "model-target-form"))
            .toList());
  }

  private static void assertUnavailable(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED));
  }
}
