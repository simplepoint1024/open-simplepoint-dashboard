package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ActivateApplicationPageBinding;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStep;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationPageBindingTarget;
import org.simplepoint.plugin.app.form.api.model.ApplicationPageTarget;
import org.simplepoint.plugin.app.form.api.model.PublishApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationPageAccessPort;
import org.simplepoint.plugin.app.form.configuration.ApplicationFormPublicationProperties;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPublicationEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormReleaseKeyEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormWritePolicyPublicationEntity;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;
import org.simplepoint.plugin.app.form.service.FormPageResourceCoordinator.PreparedPageResource;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyCancellationView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationView;

class ApplicationFormPageBindingStateServiceTest {

  private static final Instant START = Instant.parse("2026-10-13T00:00:00Z");

  private SessionFactory sessionFactory;
  private Session session;
  private ApplicationPageAccessPort pageAccess;
  private FormRequestContext requestContext;

  @BeforeEach
  void setUp() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:app-page-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build();
    sessionFactory = new MetadataSources(registry)
        .addAnnotatedClass(ApplicationFormEntity.class)
        .addAnnotatedClass(ApplicationFormVersionEntity.class)
        .addAnnotatedClass(ApplicationFormPublicationEntity.class)
        .addAnnotatedClass(ApplicationFormPageBindingEntity.class)
        .addAnnotatedClass(ApplicationFormReleaseKeyEntity.class)
        .addAnnotatedClass(ApplicationFormWritePolicyPublicationEntity.class)
        .buildMetadata()
        .buildSessionFactory();
    session = sessionFactory.openSession();
    pageAccess = mock(ApplicationPageAccessPort.class);
    requestContext = new FormRequestContext();
    authorize();
    when(pageAccess.requirePageForUpdate("tenant-a", "app-a", "page-a"))
        .thenReturn(new ApplicationPageTarget("page-a", "Orders", "Order entry", 10));
    when(pageAccess.requirePageForUpdate("tenant-a", "app-a", "page-b"))
        .thenReturn(new ApplicationPageTarget("page-b", "Order search", null, 20));
    inTransaction(this::seedReadyVersion);
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {
      if (session.getTransaction().isActive()) {
        session.getTransaction().rollback();
      }
      session.close();
    }
    if (sessionFactory != null) {
      sessionFactory.close();
    }
  }

  @Test
  void generationsBelongToThePageAcrossFormsAndRetriesRetainTheirOriginalGeneration() {
    inTransaction(() -> seedReadyForm("b"));
    var state = stateAt(START);
    var command = new PublishApplicationFormCommand(1, "page-a", "original");
    var original = inTransaction(() -> state.claim("app-a", "form-a", command));
    inTransaction(() -> state.fail(original.work().bindingId(), original.work().executionToken(),
        ApplicationFormErrorCode.PUBLICATION_FAILED));
    var retry = inTransaction(() -> state.claim("app-a", "form-a", command));
    assertThat(retry.work().generation()).isEqualTo(original.work().generation()).isEqualTo(1);
    assertThat(retry.work().executionToken()).isNotEqualTo(original.work().executionToken());
    complete(state, retry, "page-a");
    var replacement = inTransaction(() -> state.claim("app-a", "form-b",
        new PublishApplicationFormCommand(1, "page-a", "replacement")));
    assertThat(replacement.work().generation()).isEqualTo(2);
    assertThat(replacement.work().physicalModelId()).isEqualTo("model-b");
    complete(state, replacement, "page-a");
    assertThat(inTransaction(() -> state.reserve("app-a", "form-a", command)).status())
        .isEqualTo(ApplicationFormPageBindingStatus.RETIRED);
  }

  @Test
  void activeProjectionRequiresMatchingCatalogAndOriginalTenantOwnership() {
    var state = stateAt(START);
    var claim = inTransaction(() -> state.claim("app-a", "form-a",
        new PublishApplicationFormCommand(1, "page-a", "publish-page-a")));
    assertThatThrownBy(() -> inTransaction(() -> state.activeProjection("app-a", "form-a", claim.view().id())))
        .isInstanceOfSatisfying(ApplicationFormException.class, failure -> assertThat(failure.errorCode())
            .isEqualTo(ApplicationFormErrorCode.PAGE_BINDING_SUPERSEDED));
    session.clear();
    complete(state, claim, "page-a");
    when(pageAccess.requireActiveBinding("tenant-a", "app-a", "page-a")).thenReturn(
        new ApplicationPageBindingTarget("page-a", "Orders", "form-a", 1, "app.runtime.page.page-a"));
    var projection = inTransaction(() -> state.activeProjection("app-a", "form-a", claim.view().id()));
    assertThat(projection.work().generation()).isEqualTo(claim.work().generation());
    assertThat(projection.work().physicalModelId()).isEqualTo("model-a");
    assertThat(projection.work().executionToken()).isNull();
    assertThat(projection.resource()).isEqualTo(resource("page-a", true));
    assertThatThrownBy(() -> inTransaction(() -> state.activeProjection("wrong-app", "form-a", claim.view().id())))
        .isInstanceOf(ApplicationFormException.class);
    session.clear();
    assertThatThrownBy(() -> inTransaction(() -> state.activeProjection("app-a", "wrong-form", claim.view().id())))
        .isInstanceOf(ApplicationFormException.class);
    session.clear();
    when(pageAccess.requireActiveBinding("tenant-a", "app-a", "page-a")).thenReturn(
        new ApplicationPageBindingTarget("page-a", "Orders", "other-form", 1, "app.runtime.page.page-a"));
    assertThatThrownBy(() -> inTransaction(() -> state.activeProjection("app-a", "form-a", claim.view().id())))
        .isInstanceOfSatisfying(ApplicationFormException.class, failure -> assertThat(failure.errorCode())
            .isEqualTo(ApplicationFormErrorCode.PAGE_BINDING_CONFLICT));
    session.clear();
    AuthorizationContext foreign = new AuthorizationContext();
    foreign.setUserId("other-user");
    foreign.setAttributes(Map.of("X-Tenant-Id", "tenant-b"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, foreign);
    assertThatThrownBy(() -> inTransaction(() -> state.activeProjection("app-a", "form-a", claim.view().id())))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void supersededProjectionRetiresTheIntentAndCannotBeReopenedByLateFailures() {
    var state = stateAt(START);
    var command = new PublishApplicationFormCommand(1, "page-a", "publish-page-a");
    var claim = inTransaction(() -> state.claim("app-a", "form-a", command));
    var retired = inTransaction(() -> state.fail(claim.work().bindingId(), claim.work().executionToken(),
        ApplicationFormErrorCode.PAGE_BINDING_SUPERSEDED));
    assertThat(retired.status()).isEqualTo(ApplicationFormPageBindingStatus.RETIRED);
    inTransaction(() -> state.preparationFailed(retired.id(), ApplicationFormErrorCode.PUBLICATION_FAILED));
    inTransaction(() -> state.fail(retired.id(), claim.work().executionToken(), ApplicationFormErrorCode.PUBLICATION_FAILED));
    assertThat(inTransaction(() -> state.reserve("app-a", "form-a", command)).status())
        .isEqualTo(ApplicationFormPageBindingStatus.RETIRED);
    assertThat(inTransaction(() -> state.claim("app-a", "form-a", command)).execute()).isFalse();
    inTransaction(() -> {
      var saved = session.find(ApplicationFormPageBindingEntity.class, retired.id());
      assertThat(saved.getExecutionToken()).isNull();
      assertThat(saved.getLeaseUntil()).isNull();
      assertThat(saved.getErrorCode()).isEqualTo(ApplicationFormErrorCode.PAGE_BINDING_SUPERSEDED.name());
    });
  }

  @Test
  void recoversTheOriginalTargetAfterReopeningWithoutAnyDataPublication() {
    inTransaction(() -> session.remove(session.find(ApplicationFormPublicationEntity.class, "publication-a")));
    var command = new PublishApplicationFormCommand(1, "page-a", "original-intent");
    var intent = inTransaction(() -> stateAt(START).reserve("app-a", "form-a", command));
    assertThat(intent.status()).isEqualTo(ApplicationFormPageBindingStatus.DATA_PREPARING);
    assertThat(intent.attempt()).isZero();
    session.close();
    session = sessionFactory.openSession();

    var recovered = inTransaction(() -> stateAt(START).resumeCommand("app-a", "form-a", intent.id()));
    assertThat(recovered).isEqualTo(command);
    var replay = inTransaction(() -> stateAt(START).reserve("app-a", "form-a", recovered));
    assertThat(replay.id()).isEqualTo(intent.id());
    assertThat(inTransaction(() -> session.createQuery(
        "select count(p) from ApplicationFormPublicationEntity p", Long.class).getSingleResult())).isZero();
  }

  @Test
  void rejectsReuseOfAnIntentKeyForAnotherPage() {
    var state = stateAt(START);
    inTransaction(() -> state.reserve("app-a", "form-a",
        new PublishApplicationFormCommand(1, "page-a", "original-intent")));
    assertThatThrownBy(() -> inTransaction(() -> state.reserve("app-a", "form-a",
        new PublishApplicationFormCommand(1, "page-b", "original-intent"))))
        .isInstanceOfSatisfying(ApplicationFormException.class, failure -> assertThat(failure.errorCode())
            .isEqualTo(ApplicationFormErrorCode.PUBLICATION_IDEMPOTENCY_CONFLICT));
  }

  @Test
  void recoveryRequiresTheOwningApplicationFormAndTenant() {
    var state = stateAt(START);
    var intent = inTransaction(() -> state.reserve("app-a", "form-a",
        new PublishApplicationFormCommand(1, "page-a", "original-intent")));
    assertThatThrownBy(() -> inTransaction(() -> state.resumeCommand("other-app", "form-a", intent.id())))
        .isInstanceOf(ApplicationFormException.class);
    session.clear();
    assertThatThrownBy(() -> inTransaction(() -> state.resumeCommand("app-a", "other-form", intent.id())))
        .isInstanceOf(ApplicationFormException.class);
    session.clear();
    AuthorizationContext foreign = new AuthorizationContext();
    foreign.setUserId("other-user");
    foreign.setAttributes(Map.of("X-Tenant-Id", "tenant-b"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, foreign);
    assertThatThrownBy(() -> inTransaction(() -> state.resumeCommand("app-a", "form-a", intent.id())))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void lateDataFailureCannotOverwriteAnActivatedBinding() {
    var state = stateAt(START);
    var command = new PublishApplicationFormCommand(1, "page-a", "original-intent");
    var intent = inTransaction(() -> state.reserve("app-a", "form-a", command));
    inTransaction(() -> state.preparationFailed(intent.id(), ApplicationFormErrorCode.PUBLICATION_FAILED));
    session.clear();
    assertThat(inTransaction(() -> session.find(ApplicationFormPageBindingEntity.class, intent.id())).getStatus())
        .isEqualTo(ApplicationFormPageBindingStatus.FAILED);
    assertThat(inTransaction(() -> state.reserve("app-a", "form-a", command)).status())
        .isEqualTo(ApplicationFormPageBindingStatus.DATA_PREPARING);
    var claim = inTransaction(() -> state.claim("app-a", "form-a", command));
    complete(state, claim, "page-a");
    inTransaction(() -> state.preparationFailed(intent.id(), ApplicationFormErrorCode.PUBLICATION_FAILED));
    session.clear();
    assertThat(inTransaction(() -> state.reserve("app-a", "form-a", command)).status())
        .isEqualTo(ApplicationFormPageBindingStatus.ACTIVE);
  }

  @Test
  void newerActivationRetiresOldWorkAndFencesLateFailureCallbacks() {
    var oldState = stateAt(START);
    var original = new PublishApplicationFormCommand(1, "page-a", "original-intent");
    final var oldClaim = inTransaction(() -> oldState.claim("app-a", "form-a", original));
    inTransaction(() -> {
      var version = new ApplicationFormVersionEntity();
      version.setId("version-b");
      version.setTenantId("tenant-a");
      version.setFormId("form-a");
      version.setVersionNumber(2);
      version.setDocumentJson("{}");
      version.setChecksum("c".repeat(64));
      version.setStorageSchemaJson("{}");
      version.setStorageChecksum("b".repeat(64));
      version.setPhysicalModelVersion(1);
      version.setRendererVersion("1");
      version.setCreatedAt(START);
      session.persist(version);
      var publication = new ApplicationFormPublicationEntity();
      publication.setId("publication-b");
      publication.setTenantId("tenant-a");
      publication.setFormId("form-a");
      publication.setFormVersion(2);
      publication.setIdempotencyKey("publish-data-b");
      publication.setStatus(ApplicationFormPublicationStatus.DATA_READY);
      publication.setStep(ApplicationFormPublicationStep.PAGE_RESOURCE);
      publication.setCreatedAt(START);
      publication.setUpdatedAt(START);
      session.persist(publication);
    });
    var newer = new PublishApplicationFormCommand(2, "page-a", "newer-intent");
    assertThatThrownBy(() -> inTransaction(() -> oldState.reserve("app-a", "form-a", newer)))
        .isInstanceOfSatisfying(ApplicationFormException.class, failure -> assertThat(failure.errorCode())
            .isEqualTo(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS));
    session.clear();
    var recoveredState = stateAt(START.plusSeconds(3600));
    var replacement = inTransaction(() -> recoveredState.claim("app-a", "form-a", newer));
    assertThat(oldClaim.work().generation()).isEqualTo(1);
    assertThat(replacement.work().generation()).isEqualTo(2);
    complete(recoveredState, replacement, "page-a");
    var late = inTransaction(() -> recoveredState.fail(oldClaim.work().bindingId(),
        oldClaim.work().executionToken(), ApplicationFormErrorCode.PUBLICATION_FAILED));
    assertThat(late.status()).isEqualTo(ApplicationFormPageBindingStatus.RETIRED);
    var replay = inTransaction(() -> recoveredState.claim("app-a", "form-a", original));
    assertThat(replay.execute()).isFalse();
    assertThat(replay.view().status()).isEqualTo(ApplicationFormPageBindingStatus.RETIRED);
    assertThatThrownBy(() -> inTransaction(() -> recoveredState.resourceReady(oldClaim.work().bindingId(),
        oldClaim.work().executionToken(), resource("page-a", false))))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void activatesOneBindingAndReplaysItIdempotently() {
    ApplicationFormPageBindingStateService state = stateAt(START);
    PublishApplicationFormCommand command = new PublishApplicationFormCommand(
        1,
        "page-a",
        "publish-page-a"
    );
    var claim = inTransaction(() -> state.claim("app-a", "form-a", command));
    assertThat(claim.execute()).isTrue();
    assertThat(claim.view().status())
        .isEqualTo(ApplicationFormPageBindingStatus.RESOURCE_PREPARING);

    PreparedPageResource prepared = resource("page-a", false);
    var activating = inTransaction(() -> state.resourceReady(
        claim.work().bindingId(),
        claim.work().executionToken(),
        prepared
    ));
    assertThat(activating.status()).isEqualTo(ApplicationFormPageBindingStatus.ACTIVATING);

    var active = inTransaction(() -> state.complete(
        claim.work().bindingId(),
        claim.work().executionToken(),
        resource("page-a", true)
    ));
    assertThat(active.status()).isEqualTo(ApplicationFormPageBindingStatus.ACTIVE);
    assertThat(active.activatedAt()).isEqualTo(START);

    ArgumentCaptor<ActivateApplicationPageBinding> activation =
        ArgumentCaptor.forClass(ActivateApplicationPageBinding.class);
    verify(pageAccess).activateBinding(activation.capture());
    assertThat(activation.getValue())
        .extracting(
            ActivateApplicationPageBinding::pageId,
            ActivateApplicationPageBinding::formId,
            ActivateApplicationPageBinding::formVersion,
            ActivateApplicationPageBinding::resourceCode
        )
        .containsExactly("page-a", "form-a", 1, "app.runtime.page.page-a");

    var replay = inTransaction(() -> state.claim("app-a", "form-a", command));
    assertThat(replay.execute()).isFalse();
    assertThat(replay.view().id()).isEqualTo(active.id());

    inTransaction(() -> {
      ApplicationFormEntity form = session.find(ApplicationFormEntity.class, "form-a");
      ApplicationFormPublicationEntity publication = session.find(
          ApplicationFormPublicationEntity.class,
          "publication-a"
      );
      assertThat(form.getStatus()).isEqualTo(ApplicationFormStatus.PUBLISHED);
      assertThat(form.getPublishedVersion()).isEqualTo(1);
      assertThat(publication.getStatus()).isEqualTo(ApplicationFormPublicationStatus.ACTIVE);
      assertThat(publication.getStep()).isEqualTo(ApplicationFormPublicationStep.ACTIVATE);
    });
  }

  @Test
  void supportsTheSameImmutableVersionOnMultiplePages() {
    ApplicationFormPageBindingStateService state = stateAt(START);
    var first = inTransaction(() -> state.claim(
        "app-a",
        "form-a",
        new PublishApplicationFormCommand(1, "page-a", "publish-page-a")
    ));
    complete(state, first, "page-a");

    var second = inTransaction(() -> state.claim(
        "app-a",
        "form-a",
        new PublishApplicationFormCommand(1, "page-b", "publish-page-b")
    ));

    assertThat(second.execute()).isTrue();
    assertThat(second.work().pageId()).isEqualTo("page-b");
    assertThat(second.work().generation()).isEqualTo(1);
    assertThat(inTransaction(() -> session.createQuery(
            "select count(binding) from ApplicationFormPageBindingEntity binding",
            Long.class
        ).getSingleResult())).isEqualTo(2);
  }

  @Test
  void activatingAnOlderPageCannotLowerTheFormWidePolicyVersion() {
    inTransaction(() -> {
      var form = session.find(ApplicationFormEntity.class, "form-a");
      form.setLatestVersion(2);
      form.setPublishedVersion(2);
    });
    var state = stateAt(START);
    var oldPage = inTransaction(() -> state.claim("app-a", "form-a",
        new PublishApplicationFormCommand(1, "page-a", "old-page-after-new-policy")));
    complete(state, oldPage, "page-a");
    assertThat(inTransaction(() -> session.find(ApplicationFormEntity.class, "form-a").getPublishedVersion()))
        .isEqualTo(2);
  }

  @Test
  void pendingPolicySurvivesFailureAndRetriesTheOriginalIntentBeforeOtherPages() {
    var state = stateAt(START);
    var command = new PublishApplicationFormCommand(1, "page-a", "policy-page");
    var original = inTransaction(() -> state.claim("app-a", "form-a", command));
    inTransaction(() -> state.resourceReady(original.work().bindingId(), original.work().executionToken(), resource("page-a", false)));
    var check = new RecordCompletenessQuery("model-a", "app-a", "form-a",
        new RecordSchemaReference(1, "b".repeat(64)), List.of("title"), null);
    var prepared = inTransaction(() -> policies().prepare(original.work(), new FormWritePolicyImpact(0, 1, Set.of(), Map.of("old-page", check))));
    assertThat(prepared.command().expectedVersion()).isZero();
    assertThat(prepared.command().targetVersion()).isEqualTo(1);
    session.clear();
    var replay = inTransaction(() -> policies().prepare(original.work(), new FormWritePolicyImpact(0, 1, Set.of(), Map.of())));
    assertThat(replay.command()).isEqualTo(prepared.command());
    inTransaction(() -> state.fail(original.work().bindingId(), original.work().executionToken(), ApplicationFormErrorCode.PUBLICATION_FAILED));
    assertThatThrownBy(() -> inTransaction(() -> stateAt(START.plusSeconds(86_400)).reserve("app-a", "form-a",
        new PublishApplicationFormCommand(1, "page-b", "other-page"))))
        .isInstanceOfSatisfying(ApplicationFormException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS));
    var retry = inTransaction(() -> state.claim("app-a", "form-a", command));
    inTransaction(() -> state.resourceReady(retry.work().bindingId(), retry.work().executionToken(), resource("page-a", false)));
    var confirmed = new RecordWritePolicyPublicationView(true, 1, "c".repeat(64), Map.of());
    assertThatThrownBy(() -> inTransaction(() -> policies().recordResult(original.work(), confirmed)))
        .isInstanceOf(ApplicationFormException.class);
    session.clear();
    var blocked = new RecordWritePolicyPublicationView(false, 0, "c".repeat(64),
        Map.of("old-page", new RecordCompletenessView(2, Map.of("title", 1L))));
    assertThat(inTransaction(() -> policies().recordResult(retry.work(), blocked)).inspection()).isEqualTo(blocked);
    assertThat(inTransaction(() -> policies().recordResult(retry.work(), confirmed)).status())
        .isEqualTo(FormWritePolicyPublicationStatus.TARGET_READY);
    assertThat(inTransaction(() -> policies().recordResult(retry.work(), blocked)).inspection()).isNull();
    inTransaction(() -> state.complete(retry.work().bindingId(), retry.work().executionToken(), resource("page-a", true)));
    assertThat(inTransaction(() -> session.find(ApplicationFormEntity.class, "form-a").getWritePolicyVersion())).isEqualTo(1);
    assertThat(inTransaction(() -> policies().prepare(retry.work(), new FormWritePolicyImpact(0, 1, Set.of(), Map.of()))).status())
        .isEqualTo(FormWritePolicyPublicationStatus.ACTIVE);
    var next = inTransaction(() -> state.claim("app-a", "form-a", new PublishApplicationFormCommand(1, "page-b", "next")));
    inTransaction(() -> state.resourceReady(next.work().bindingId(), next.work().executionToken(), resource("page-b", false)));
    assertThatThrownBy(() -> inTransaction(() -> state.complete(next.work().bindingId(), next.work().executionToken(), resource("page-b", true))))
        .isInstanceOf(ApplicationFormException.class);
    session.clear();
    assertThatThrownBy(() -> inTransaction(() -> policies().prepare(next.work(), new FormWritePolicyImpact(1, 1, Set.of(), Map.of()))))
        .isInstanceOf(ApplicationFormException.class);
    var nextImpact = new FormWritePolicyImpact(1, 1, Set.of(original.work().bindingId()), Map.of());
    assertThat(inTransaction(() -> policies().prepare(next.work(), nextImpact)).command().expectedVersion()).isEqualTo(1);
  }

  @Test
  void pageActivationAndPolicyActivationRollBackAsOneControlTransaction() {
    var state = stateAt(START);
    var claim = inTransaction(() -> state.claim("app-a", "form-a", new PublishApplicationFormCommand(1, "page-a", "atomic")));
    inTransaction(() -> state.resourceReady(claim.work().bindingId(), claim.work().executionToken(), resource("page-a", false)));
    inTransaction(() -> policies().prepare(claim.work(), new FormWritePolicyImpact(0, 1, Set.of(), Map.of())));
    assertThatThrownBy(() -> inTransaction(() -> state.complete(claim.work().bindingId(), claim.work().executionToken(), resource("page-a", true))))
        .isInstanceOf(ApplicationFormException.class);
    session.clear();
    inTransaction(() -> policies().recordResult(claim.work(), new RecordWritePolicyPublicationView(true, 1, "d".repeat(64), Map.of())));
    assertThatThrownBy(() -> inTransaction((Runnable) () -> {
      state.complete(claim.work().bindingId(), claim.work().executionToken(), resource("page-a", true));
      throw new IllegalStateException("Activation transaction interrupted");
    })).isInstanceOf(IllegalStateException.class);
    session.clear();
    assertThat(inTransaction(() -> session.find(ApplicationFormEntity.class, "form-a").getWritePolicyVersion())).isZero();
    assertThat(inTransaction(() -> session.find(ApplicationFormPageBindingEntity.class, claim.work().bindingId()).getStatus()))
        .isEqualTo(ApplicationFormPageBindingStatus.ACTIVATING);
    inTransaction(() -> state.complete(claim.work().bindingId(), claim.work().executionToken(), resource("page-a", true)));
    assertThat(inTransaction(() -> session.find(ApplicationFormEntity.class, "form-a").getWritePolicyVersion())).isEqualTo(1);
  }

  @Test
  void rebindingAnotherPageCannotRemoveAnAffectedVersionWhilePolicyPublicationIsPending() {
    var state = stateAt(START);
    var active = inTransaction(() -> state.claim("app-a", "form-a", new PublishApplicationFormCommand(1, "page-a", "active")));
    complete(state, active, "page-a");
    inTransaction(() -> seedReadyForm("b"));
    var pending = inTransaction(() -> state.claim("app-a", "form-a", new PublishApplicationFormCommand(1, "page-b", "pending")));
    inTransaction(() -> state.resourceReady(pending.work().bindingId(), pending.work().executionToken(), resource("page-b", false)));
    var impact = new FormWritePolicyImpact(0, 1, Set.of(active.work().bindingId()), Map.of());
    inTransaction(() -> policies().prepare(pending.work(), impact));
    assertThatThrownBy(() -> inTransaction(() -> state.reserve("app-a", "form-b",
        new PublishApplicationFormCommand(1, "page-a", "replace-affected-page"))))
        .isInstanceOfSatisfying(ApplicationFormException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS));
  }

  @Test
  void concurrentPagesCannotReserveDifferentPolicyIntentsForTheSameForm() throws Exception {
    var state = stateAt(START);
    var one = inTransaction(() -> state.claim("app-a", "form-a", new PublishApplicationFormCommand(1, "page-a", "one")));
    var two = inTransaction(() -> state.claim("app-a", "form-a", new PublishApplicationFormCommand(1, "page-b", "two")));
    inTransaction(() -> state.resourceReady(one.work().bindingId(), one.work().executionToken(), resource("page-a", false)));
    inTransaction(() -> state.resourceReady(two.work().bindingId(), two.work().executionToken(), resource("page-b", false)));
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> prepareConcurrently(one.work(), gate));
      var second = pool.submit(() -> prepareConcurrently(two.work(), gate));
      gate.countDown();
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
    }
    assertThat(inTransaction(() -> session.createQuery("select count(p) from ApplicationFormWritePolicyPublicationEntity p", Long.class)
        .getSingleResult())).isEqualTo(1);
  }

  @Test
  void cancellationBeforeResourceWorkDoesNotRequirePhysicalModelAndRejectsLateClaims() {
    var state = stateAt(START);
    var command = new PublishApplicationFormCommand(1, "page-a", "cancel-before-data");
    var saved = inTransaction(() -> state.reserve("app-a", "form-a", command));
    inTransaction(() -> session.find(ApplicationFormEntity.class, "form-a").setPhysicalModelId(null));
    var cancelled = inTransaction(() -> state.claimCancellation("app-a", "form-a", saved.id()));
    assertThat(cancelled.execute()).isFalse();
    assertThat(cancelled.view().status()).isEqualTo(ApplicationFormPageBindingStatus.CANCELLED);
    assertThat(inTransaction(() -> state.claim("app-a", "form-a", command)).execute()).isFalse();
    assertThat(inTransaction(() -> state.claimCancellation("app-a", "form-a", saved.id())).view()).isEqualTo(cancelled.view());
    assertThat(inTransaction(() -> session.find(ApplicationFormEntity.class, "form-a").getWritePolicyVersion())).isZero();
  }

  @Test
  void cancellationClaimFencesOldWorkersAndRecoversItsDecisionAfterFailure() {
    var state = stateAt(START);
    var pending = inTransaction(() -> state.claim("app-a", "form-a", new PublishApplicationFormCommand(1, "page-a", "cancel-claim")));
    inTransaction(() -> state.resourceReady(pending.work().bindingId(), pending.work().executionToken(), resource("page-a", false)));
    inTransaction(() -> policies().prepare(pending.work(), new FormWritePolicyImpact(0, 1, Set.of(), Map.of())));
    var cancel = inTransaction(() -> state.claimCancellation("app-a", "form-a", pending.view().id()));
    assertThat(cancel.execute()).isTrue();
    assertThat(cancel.work().executionToken()).isNotEqualTo(pending.work().executionToken());
    assertThat(inTransaction(() -> policies().current(cancel.work())).status()).isEqualTo(FormWritePolicyPublicationStatus.CANCELLING);
    assertThat(inTransaction(() -> state.claimCancellation("app-a", "form-a", pending.view().id())).execute()).isFalse();
    assertThatThrownBy(() -> inTransaction(() -> policies().recordResult(pending.work(),
        new RecordWritePolicyPublicationView(true, 1, "a".repeat(64), Map.of())))).isInstanceOf(ApplicationFormException.class);
    session.clear();
    inTransaction(() -> state.fail(cancel.view().id(), cancel.work().executionToken(), ApplicationFormErrorCode.PUBLICATION_FAILED));
    session.close();
    session = sessionFactory.openSession();
    var retry = inTransaction(() -> stateAt(START).claimPolicyRecovery("app-a", "form-a", pending.view().id()));
    assertThat(retry.execute()).isTrue();
    assertThat(retry.work().executionToken()).isNotEqualTo(cancel.work().executionToken());
    assertThat(inTransaction(() -> policies().current(retry.work())).status()).isEqualTo(FormWritePolicyPublicationStatus.CANCELLING);
    inTransaction(() -> policies().recordCancellation(retry.work(),
        new RecordWritePolicyCancellationView(true, 1, "a".repeat(64), "b".repeat(64))));
    assertThat(inTransaction(() -> stateAt(START).get("app-a", "form-a", pending.view().id())).status())
        .isEqualTo(ApplicationFormPageBindingStatus.CANCELLED);
  }

  @Test
  void cancellationClaimRollsBackWithItsDecisionAndRejectsLegacyOrForeignWork() {
    var state = stateAt(START);
    var pending = inTransaction(() -> state.claim("app-a", "form-a", new PublishApplicationFormCommand(1, "page-a", "cancel-rollback")));
    assertThatThrownBy(() -> inTransaction(() -> state.claimCancellation("app-a", "form-a", pending.view().id())))
        .isInstanceOf(ApplicationFormException.class);
    session.clear();
    inTransaction(() -> state.resourceReady(pending.work().bindingId(), pending.work().executionToken(), resource("page-a", false)));
    inTransaction(() -> policies().prepare(pending.work(), new FormWritePolicyImpact(0, 1, Set.of(), Map.of())));
    assertThatThrownBy(() -> inTransaction((Runnable) () -> {
      state.claimCancellation("app-a", "form-a", pending.view().id());
      throw new IllegalStateException("Commit interrupted");
    })).isInstanceOf(IllegalStateException.class);
    session.clear();
    assertThat(inTransaction(() -> policies().current(pending.work())).status()).isEqualTo(FormWritePolicyPublicationStatus.PREPARED);
    assertThatThrownBy(() -> inTransaction(() -> state.claimCancellation("app-other", "form-a", pending.view().id())))
        .isInstanceOf(ApplicationFormException.class);
    session.clear();
    assertThatThrownBy(() -> inTransaction(() -> state.claimCancellation("app-a", "form-other", pending.view().id())))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void cancelledReleasesRetainAllRequestKeysAndAllowTheSameVersionInNewAttempts() {
    var state = stateAt(START);
    var active = inTransaction(() -> state.claim("app-a", "form-a", new PublishApplicationFormCommand(1, "page-b", "old-page")));
    complete(state, active, "page-b");
    var original = new PublishApplicationFormCommand(1, "page-a", "original-cancel");
    var alias = new PublishApplicationFormCommand(1, "page-a", "deduplicated-cancel");
    var pending = inTransaction(() -> state.claim("app-a", "form-a", original));
    assertThat(inTransaction(() -> state.reserve("app-a", "form-a", alias)).id()).isEqualTo(pending.view().id());
    inTransaction(() -> state.resourceReady(pending.work().bindingId(), pending.work().executionToken(), resource("page-a", false)));
    inTransaction(() -> policies().prepare(pending.work(), new FormWritePolicyImpact(0, 1, Set.of(active.view().id()), Map.of())));
    inTransaction(() -> policies().requestCancellation(pending.work()));
    assertThat(inTransaction(() -> session.find(ApplicationFormEntity.class, "form-a").getWritePolicyVersion())).isZero();
    var confirmed = new RecordWritePolicyCancellationView(true, 1, "a".repeat(64), "b".repeat(64));
    assertThat(inTransaction(() -> policies().recordCancellation(pending.work(), confirmed)).status())
        .isEqualTo(FormWritePolicyPublicationStatus.CANCELLED);
    session.close();
    session = sessionFactory.openSession();
    assertThat(inTransaction(() -> policies().requestCancellation(pending.work())).cancellation()).isEqualTo(confirmed);
    assertThat(inTransaction(() -> stateAt(START).reserve("app-a", "form-a", original)).status())
        .isEqualTo(ApplicationFormPageBindingStatus.CANCELLED);
    assertThat(inTransaction(() -> stateAt(START).reserve("app-a", "form-a", alias)).id()).isEqualTo(pending.view().id());
    inTransaction(() -> {
      assertThat(session.find(ApplicationFormEntity.class, "form-a").getWritePolicyVersion()).isEqualTo(1);
      assertThat(session.find(ApplicationFormEntity.class, "form-a").getPublishedVersion()).isEqualTo(1);
      assertThat(session.find(ApplicationFormPageBindingEntity.class, active.view().id()).getStatus())
          .isEqualTo(ApplicationFormPageBindingStatus.ACTIVE);
    });
    var next = inTransaction(() -> stateAt(START).claim("app-a", "form-a", new PublishApplicationFormCommand(1, "page-a", "fresh-intent")));
    assertThat(next.execute()).isTrue();
    assertThat(next.view().id()).isNotEqualTo(pending.view().id());
    assertThat(next.work().generation()).isGreaterThan(pending.work().generation());
    assertThat(inTransaction(() -> stateAt(START).reserve("app-a", "form-a", alias)).id()).isEqualTo(pending.view().id());
    assertThat(inTransaction(() -> stateAt(START).fail(pending.view().id(), pending.work().executionToken(),
        ApplicationFormErrorCode.PUBLICATION_FAILED)).status()).isEqualTo(ApplicationFormPageBindingStatus.CANCELLED);
    var late = new RecordWritePolicyPublicationView(false, 0, "a".repeat(64),
        Map.of("missing", new RecordCompletenessView(1, Map.of("title", 1L))));
    assertThat(inTransaction(() -> policies().recordResult(pending.work(), late)).status()).isEqualTo(FormWritePolicyPublicationStatus.CANCELLED);
    inTransaction(() -> stateAt(START).resourceReady(next.work().bindingId(), next.work().executionToken(), resource("page-a", false)));
    assertThat(inTransaction(() -> policies().prepare(next.work(),
        new FormWritePolicyImpact(1, 1, Set.of(active.view().id()), Map.of()))).command().targetVersion()).isEqualTo(2);
  }

  @Test
  void cancellationAdmissionAndTerminalStateRollBackTogetherAndKeepOtherPublishersBlocked() {
    var state = stateAt(START);
    var pending = inTransaction(() -> state.claim("app-a", "form-a", new PublishApplicationFormCommand(1, "page-a", "cancel-atomic")));
    inTransaction(() -> state.resourceReady(pending.work().bindingId(), pending.work().executionToken(), resource("page-a", false)));
    inTransaction(() -> policies().prepare(pending.work(), new FormWritePolicyImpact(0, 1, Set.of(), Map.of())));
    inTransaction(() -> policies().requestCancellation(pending.work()));
    inTransaction(() -> seedReadyForm("b"));
    assertThatThrownBy(() -> inTransaction(() -> state.claim("app-a", "form-b", new PublishApplicationFormCommand(1, "page-a", "overtake"))))
        .isInstanceOf(ApplicationFormException.class);
    session.clear();
    var confirmed = new RecordWritePolicyCancellationView(true, 1, "a".repeat(64), "b".repeat(64));
    assertThatThrownBy(() -> inTransaction((Runnable) () -> {
      policies().recordCancellation(pending.work(), confirmed);
      throw new IllegalStateException("Control commit interrupted");
    })).isInstanceOf(IllegalStateException.class);
    session.clear();
    assertThat(inTransaction(() -> session.find(ApplicationFormEntity.class, "form-a").getWritePolicyVersion())).isZero();
    assertThat(inTransaction(() -> session.find(ApplicationFormPageBindingEntity.class, pending.view().id()).getStatus()))
        .isEqualTo(ApplicationFormPageBindingStatus.ACTIVATING);
    assertThat(inTransaction(() -> policies().prepare(pending.work(), null)).status()).isEqualTo(FormWritePolicyPublicationStatus.CANCELLING);
    assertThat(inTransaction(() -> policies().recordCancellation(pending.work(), confirmed)).status())
        .isEqualTo(FormWritePolicyPublicationStatus.CANCELLED);
    assertThat(inTransaction(() -> state.claim("app-a", "form-b", new PublishApplicationFormCommand(1, "page-a", "overtake"))).execute()).isTrue();
  }

  @Test
  void cancellationResolvesCommittedTargetByCompletingItsOriginalPublication() {
    var state = stateAt(START);
    var pending = inTransaction(() -> state.claim("app-a", "form-a", new PublishApplicationFormCommand(1, "page-a", "published-first")));
    inTransaction(() -> state.resourceReady(pending.work().bindingId(), pending.work().executionToken(), resource("page-a", false)));
    inTransaction(() -> policies().prepare(pending.work(), new FormWritePolicyImpact(0, 1, Set.of(), Map.of())));
    inTransaction(() -> policies().requestCancellation(pending.work()));
    var confirmed = new RecordWritePolicyCancellationView(false, 1, "a".repeat(64), "a".repeat(64));
    assertThat(inTransaction(() -> policies().recordCancellation(pending.work(), confirmed)).status())
        .isEqualTo(FormWritePolicyPublicationStatus.TARGET_READY);
    assertThat(inTransaction(() -> session.find(ApplicationFormEntity.class, "form-a").getWritePolicyVersion())).isZero();
    inTransaction(() -> state.complete(pending.work().bindingId(), pending.work().executionToken(), resource("page-a", true)));
    assertThat(inTransaction(() -> policies().requestCancellation(pending.work())).status()).isEqualTo(FormWritePolicyPublicationStatus.ACTIVE);
    assertThat(inTransaction(() -> session.find(ApplicationFormEntity.class, "form-a").getWritePolicyVersion())).isEqualTo(1);
  }

  private boolean prepareConcurrently(final BindingWork work, final CountDownLatch gate) throws Exception {
    authorize();
    try (var concurrent = sessionFactory.openSession()) {
      var service = new FormWritePolicyPublicationStateService(concurrent, pageAccess, new FormRequestContext(),
          new ObjectMapper(), Clock.fixed(START, ZoneOffset.UTC));
      gate.await();
      concurrent.beginTransaction();
      try {
        service.prepare(work, new FormWritePolicyImpact(0, 1, Set.of(), Map.of()));
        concurrent.getTransaction().commit();
        return true;
      } catch (ApplicationFormException failure) {
        concurrent.getTransaction().rollback();
        assertThat(failure.errorCode()).isEqualTo(ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS);
        return false;
      }
    } finally {
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  private void complete(
      final ApplicationFormPageBindingStateService state,
      final ApplicationFormPageBindingStateService.BindingClaim claim,
      final String pageId
  ) {
    inTransaction(() -> state.resourceReady(
        claim.work().bindingId(),
        claim.work().executionToken(),
        resource(pageId, false)
    ));
    inTransaction(() -> state.complete(
        claim.work().bindingId(),
        claim.work().executionToken(),
        resource(pageId, true)
    ));
  }

  private ApplicationFormPageBindingStateService stateAt(final Instant instant) {
    return new ApplicationFormPageBindingStateService(
        session,
        requestContext,
        pageAccess,
        Clock.fixed(instant, ZoneOffset.UTC),
        new ApplicationFormPublicationProperties(),
        policies()
    );
  }

  private FormWritePolicyPublicationStateService policies() {
    return new FormWritePolicyPublicationStateService(session, pageAccess, requestContext,
        new ObjectMapper().findAndRegisterModules(), Clock.fixed(START, ZoneOffset.UTC));
  }

  private void seedReadyVersion() {
    seedReadyForm("a");
  }

  private void seedReadyForm(final String suffix) {
    ApplicationFormEntity form = new ApplicationFormEntity();
    form.setId("form-" + suffix);
    form.setTenantId("tenant-a");
    form.setApplicationId("app-a");
    form.setCode("orders_" + suffix);
    form.setName("Orders");
    form.setStatus(ApplicationFormStatus.DRAFT);
    form.setDraftRevision(1);
    form.setLatestVersion(1);
    form.setPhysicalModelId("model-" + suffix);
    form.setLockVersion(0);
    form.setCreatedAt(START);
    form.setUpdatedAt(START);
    session.persist(form);

    ApplicationFormVersionEntity version = new ApplicationFormVersionEntity();
    version.setId("version-" + suffix);
    version.setTenantId("tenant-a");
    version.setFormId(form.getId());
    version.setVersionNumber(1);
    version.setDocumentJson("{}");
    version.setChecksum("a".repeat(64));
    version.setStorageSchemaJson("{}");
    version.setStorageChecksum("b".repeat(64));
    version.setPhysicalModelVersion(1);
    version.setRendererVersion("1");
    version.setCreatedAt(START);
    session.persist(version);

    ApplicationFormPublicationEntity publication = new ApplicationFormPublicationEntity();
    publication.setId("publication-" + suffix);
    publication.setTenantId("tenant-a");
    publication.setFormId(form.getId());
    publication.setFormVersion(1);
    publication.setIdempotencyKey("publish-data-" + suffix);
    publication.setStatus(ApplicationFormPublicationStatus.DATA_READY);
    publication.setStep(ApplicationFormPublicationStep.PAGE_RESOURCE);
    publication.setAttempt(1);
    publication.setCreatedAt(START);
    publication.setUpdatedAt(START);
    session.persist(publication);
  }

  private static PreparedPageResource resource(final String pageId, final boolean enabled) {
    return new PreparedPageResource(
        "tenant-a",
        "page:" + pageId,
        "resource-" + pageId,
        "app.runtime.page." + pageId,
        "/app/runtime/app-a/" + pageId,
        enabled
    );
  }

  private <T> T inTransaction(final java.util.concurrent.Callable<T> work) {
    session.beginTransaction();
    try {
      T result = work.call();
      session.getTransaction().commit();
      return result;
    } catch (RuntimeException exception) {
      session.getTransaction().rollback();
      throw exception;
    } catch (Exception exception) {
      session.getTransaction().rollback();
      throw new IllegalStateException(exception);
    }
  }

  private void inTransaction(final Runnable work) {
    inTransaction(() -> {
      work.run();
      return null;
    });
  }

  private static void authorize() {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("user-a");
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }
}
