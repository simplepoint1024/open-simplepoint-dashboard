package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalResourceProjectionEntity;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

class RecordResourceFenceTest {
  private SessionFactory factory;
  private EntityManager em;
  private TransactionTemplate tx;
  private RecordResourceFence fence;

  @BeforeEach
  void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:fence-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build())
        .addAnnotatedClass(PhysicalResourceProjectionEntity.class).buildMetadata().buildSessionFactory();
    em = SharedEntityManagerCreator.createSharedEntityManager(factory);
    var manager = new JpaTransactionManager(factory);
    tx = new TransactionTemplate(manager); fence = new RecordResourceFence(em, manager);
    tx.executeWithoutResult(status -> {
      var row = new PhysicalResourceProjectionEntity();
      row.setId("projection"); row.setTenantId("tenant"); row.setResourceCode("resource");
      row.setPhysicalModelId("model"); row.setOwnerKey("app:page"); row.setGeneration(1);
      row.setCreatedBy("app"); row.setUpdatedBy("app"); row.setCreatedAt(Instant.now()); row.setUpdatedAt(Instant.now());
      em.persist(row);
    });
    ResourcePolicyContext.set("resource");
  }

  @AfterEach
  void cleanup() {
    ResourcePolicyContext.clear(); factory.close();
  }

  @Test
  void stalePreparedWriteCannotCrossOfflineAndReonline() {
    assertThat(fence.generation("tenant", "model", "resource")).isEqualTo(1);
    tx.executeWithoutResult(status -> em.find(PhysicalResourceProjectionEntity.class, "projection").setFormallyOffline(true));
    assertThatThrownBy(() -> fence.execute("tenant", "model", 1, () -> "write"))
        .isInstanceOf(AccessDeniedException.class);
    tx.executeWithoutResult(status -> {
      var row = em.find(PhysicalResourceProjectionEntity.class, "projection");
      row.setFormallyOffline(false); row.setGeneration(2);
    });
    assertThatThrownBy(() -> fence.execute("tenant", "model", 1, () -> "old-write"))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(fence.execute("tenant", "model", 2, () -> "new-write")).isEqualTo("new-write");
  }

  @Test
  void offlineWaitsUntilAnAdmittedPhysicalWriteFinishes() throws Exception {
    var admitted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attemptingOffline = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var write = workers.submit(() -> {
        ResourcePolicyContext.set("resource");
        try {
          return fence.execute("tenant", "model", 1, () -> {
            admitted.countDown();
            try {assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();}
            catch (InterruptedException interrupted) {throw new IllegalStateException(interrupted);}
            return "committed";
          });
        } finally {ResourcePolicyContext.clear();}
      });
      assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
      var offline = workers.submit(() -> tx.executeWithoutResult(status -> {
        attemptingOffline.countDown();
        var row = em.find(PhysicalResourceProjectionEntity.class, "projection", LockModeType.PESSIMISTIC_WRITE);
        row.setFormallyOffline(true);
      }));
      assertThat(attemptingOffline.await(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> offline.get(150, TimeUnit.MILLISECONDS))
          .isInstanceOf(java.util.concurrent.TimeoutException.class);
      release.countDown();
      assertThat(write.get(5, TimeUnit.SECONDS)).isEqualTo("committed");
      offline.get(5, TimeUnit.SECONDS);
      assertThatThrownBy(() -> fence.execute("tenant", "model", 1, () -> "write"))
          .isInstanceOf(AccessDeniedException.class);
    } finally {release.countDown();}
  }
}
