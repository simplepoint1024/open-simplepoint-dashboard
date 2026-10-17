package org.simplepoint.plugin.dna.runtime.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.platform.resource.ResourceLifecycleEvents;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalResourceProjectionEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Retires only the requesting page's projection; physical tables and other page bindings survive. */
@Component
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
public class PhysicalResourceOfflineHandler implements EventHandler {
  private final EntityManager em;
  private final EventPublisher events;

  /** Uses the data module's existing projection persistence. */
  public PhysicalResourceOfflineHandler(EntityManager em, EventPublisher events) {
    this.em = em;
    this.events = events;
  }

  @Override
  public EventSubscription subscription() {
    return new EventSubscription("resource-offline.projection", "app", ResourceLifecycleEvents.OFFLINE_REQUESTED, 1);
  }

  @Override
  public void handle(EventMessage event) {
    subscription().validate(event);
    var request = ResourceLifecycleEvents.request(event);
    var projection = em.createQuery("from PhysicalResourceProjectionEntity p where p.tenantId = :tenant "
        + "and p.resourceCode = :resource", PhysicalResourceProjectionEntity.class)
        .setParameter("tenant", event.tenantId()).setParameter("resource", request.resourceCode())
        .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Resource projection is unavailable"));
    if (!(request.managedOwner() + ":" + request.managedKey()).equals(projection.getOwnerKey())) {
      throw new IllegalArgumentException("Offline request does not own the data projection");
    }
    if (event.generation() >= projection.getGeneration()) {
      em.createQuery("update PhysicalModelResourceBindingEntity b set b.deletedAt = :now "
          + "where b.tenantId = :tenant and b.resourceCode = :resource and b.deletedAt is null")
          .setParameter("now", Instant.now()).setParameter("tenant", event.tenantId())
          .setParameter("resource", request.resourceCode()).executeUpdate();
      projection.setGeneration(event.generation());
      projection.setFormallyOffline(true);
      projection.setUpdatedBy("app");
      projection.setUpdatedAt(Instant.now());
      em.flush();
    }
    events.publish(ResourceLifecycleEvents.completed(event, "dna"));
  }
}
