package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.platform.resource.ResourceLifecycleEvents;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormStatus;
import org.simplepoint.plugin.app.form.api.service.ApplicationAccessPort;
import org.simplepoint.plugin.app.form.api.service.ApplicationPageAccessPort;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormPageBindingEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormSubmissionEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationResourceOfflineEntity;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

class ApplicationResourceLifecycleTest {
  private SessionFactory factory;
  private EntityManager em;
  private TransactionTemplate tx;
  private ApplicationResourceLifecycleService service;
  private final ApplicationPageAccessPort pages = mock(ApplicationPageAccessPort.class);
  private final List<EventMessage> events = new ArrayList<>();
  private final String operationId = UUID.randomUUID().toString();

  @BeforeEach
  void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:lifecycle-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build())
        .addAnnotatedClass(ApplicationFormEntity.class)
        .addAnnotatedClass(ApplicationFormPageBindingEntity.class)
        .addAnnotatedClass(ApplicationFormSubmissionEntity.class)
        .addAnnotatedClass(ApplicationResourceOfflineEntity.class)
        .buildMetadata().buildSessionFactory();
    em = SharedEntityManagerCreator.createSharedEntityManager(factory);
    tx = new TransactionTemplate(new JpaTransactionManager(factory));
    var actors = mock(FormRequestContext.class);
    when(actors.requireActor()).thenReturn(new FormRequestContext.FormActor("tenant", "actor"));
    service = new ApplicationResourceLifecycleService(em, actors, mock(ApplicationAccessPort.class), pages, events::add);
    tx.executeWithoutResult(status -> {
      var form = new ApplicationFormEntity();
      form.setId("form"); form.setTenantId("tenant"); form.setApplicationId("app");
      form.setCode("test"); form.setName("Test"); form.setStatus(ApplicationFormStatus.PUBLISHED);
      form.setCreatedAt(Instant.now()); form.setUpdatedAt(Instant.now()); em.persist(form);
      binding("binding", 1);
    });
  }

  @AfterEach
  void cleanup() {
    factory.close();
  }

  @Test
  void waitsForEveryRequiredParticipantAndDoesNotReviveRetiredBinding() {
    var accepted = tx.execute(status -> service.offline("app", "page", operationId, 1));
    assertThat(accepted.state()).isEqualTo("OFFLINING");
    assertThat(events).hasSize(1);
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> FormResourceLifecycleGuard.requireAvailable(em, "tenant", "page")))
        .hasMessageContaining("offline");
    tx.executeWithoutResult(status -> service.offline("app", "page", operationId, 1));
    assertThat(events).hasSize(1);
    EventMessage common = ResourceLifecycleEvents.completed(events.getFirst(), "common");
    tx.executeWithoutResult(status -> service.completed(common));
    tx.executeWithoutResult(status -> service.completed(common));
    assertThat(tx.execute(status -> service.inspect("app", "page")).state()).isEqualTo("OFFLINING");
    tx.executeWithoutResult(status -> service.completed(ResourceLifecycleEvents.completed(events.getFirst(), "dna")));
    assertThat(tx.execute(status -> service.inspect("app", "page")).state()).isEqualTo("OFFLINE");
    tx.executeWithoutResult(status -> binding("new-binding", 2));
    tx.executeWithoutResult(status -> service.completed(common));
    tx.executeWithoutResult(status -> FormResourceLifecycleGuard.requireAvailable(em, "tenant", "page"));
    assertThat(tx.execute(status -> service.inspect("app", "page")).state()).isEqualTo("ONLINE");
  }

  @Test
  void unsuccessfulPublicationDoesNotHideTheServingOrOfflineGeneration() {
    tx.executeWithoutResult(status -> {
      binding("cancelled", 2);
      em.find(ApplicationFormPageBindingEntity.class, "cancelled").setStatus(ApplicationFormPageBindingStatus.CANCELLED);
    });
    var online = tx.execute(status -> service.inspect("app", "page"));
    assertThat(online.state()).isEqualTo("ONLINE");
    assertThat(online.generation()).isEqualTo(1);
    tx.executeWithoutResult(status -> service.offline("app", "page", operationId, 1));
    tx.executeWithoutResult(status -> service.completed(ResourceLifecycleEvents.completed(events.getFirst(), "common")));
    tx.executeWithoutResult(status -> service.completed(ResourceLifecycleEvents.completed(events.getFirst(), "dna")));
    tx.executeWithoutResult(status -> {
      binding("failed", 3);
      em.find(ApplicationFormPageBindingEntity.class, "failed").setStatus(ApplicationFormPageBindingStatus.FAILED);
    });
    var offline = tx.execute(status -> service.inspect("app", "page"));
    assertThat(offline.state()).isEqualTo("OFFLINE");
    assertThat(offline.operationId()).isEqualTo(operationId);
    assertThat(offline.generation()).isEqualTo(1);
  }

  @Test
  void recoveryKeepsRootOperationAndDeduplicatesTheRetryRequest() {
    tx.executeWithoutResult(status -> service.offline("app", "page", operationId, 1));
    String retry = UUID.randomUUID().toString();
    tx.executeWithoutResult(status -> service.retry("app", "page", operationId, retry));
    tx.executeWithoutResult(status -> service.retry("app", "page", operationId, retry));
    assertThat(events).hasSize(2);
    assertThat(events.get(1).operationId()).isEqualTo(events.getFirst().operationId());
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.retry("app", "page", operationId, UUID.randomUUID().toString())))
        .hasMessageContaining("Wait before");
    tx.executeWithoutResult(status -> service.completed(ResourceLifecycleEvents.completed(events.getFirst(), "common")));
    tx.executeWithoutResult(status -> service.completed(ResourceLifecycleEvents.completed(events.get(1), "dna")));
    assertThat(tx.execute(status -> service.inspect("app", "page")).state()).isEqualTo("OFFLINE");
  }

  @Test
  void staleConfirmationAndCrossTenantCompletionAreRejected() {
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.offline("app", "page", operationId, 2)))
        .hasMessageContaining("Publication changed");
    tx.executeWithoutResult(status -> service.offline("app", "page", operationId, 1));
    EventMessage valid = ResourceLifecycleEvents.completed(events.getFirst(), "common");
    EventMessage wrong = new EventMessage(valid.eventId(), valid.eventType(), 1, valid.producer(), "other-tenant",
        valid.resourceId(), valid.generation(), valid.operationId(), valid.correlationId(), valid.occurredAt(), valid.payload());
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.completed(wrong)))
        .hasMessageContaining("does not match");
    assertThat(tx.execute(status -> service.inspect("app", "page")).completedParticipants()).isEmpty();
  }

  @Test
  void pendingSubmissionBlocksOfflineBeforeAnyEventOrEntryChange() {
    tx.executeWithoutResult(status -> {
      var submission = new ApplicationFormSubmissionEntity();
      submission.setId("a".repeat(64)); submission.setTenantId("tenant"); submission.setApplicationId("app");
      submission.setPageId("page"); submission.setFormId("form"); submission.setFormVersion(1);
      submission.setActorId("actor"); submission.setRequestFingerprint("b".repeat(64));
      submission.setIntentJson("{}"); submission.setDefinitionId("definition"); submission.setDefinitionVersion(1);
      submission.setTitle("Test"); submission.setStatus("ACCEPTED"); submission.setStep("PREPARED");
      submission.setCreatedAt(Instant.now()); submission.setUpdatedAt(Instant.now()); submission.setNextAttemptAt(Instant.now());
      em.persist(submission);
    });
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.offline("app", "page", operationId, 1)))
        .hasMessageContaining("Unfinished");
    assertThat(events).isEmpty();
    verify(pages, never()).offlineBinding(anyString(), anyString(), anyString(), anyBoolean());
  }

  private void binding(String id, long generation) {
    var binding = new ApplicationFormPageBindingEntity();
    binding.setId(id); binding.setTenantId("tenant"); binding.setApplicationId("app"); binding.setPageId("page");
    binding.setFormId("form"); binding.setFormVersion(1); binding.setSceneId("main");
    binding.setIdempotencyKey(id); binding.setGeneration(generation); binding.setPolicyProtocolVersion(1);
    binding.setStatus(ApplicationFormPageBindingStatus.ACTIVE); binding.setResourceManagedKey("page:page");
    binding.setResourceId("resource"); binding.setResourceCode("app.runtime.page.test");
    binding.setRuntimePath("/app/runtime/app/page"); binding.setCreatedAt(Instant.now()); binding.setUpdatedAt(Instant.now());
    em.persist(binding);
  }
}
