package org.simplepoint.platform.bootstrap.registry.migration;

import jakarta.persistence.EntityManager;
import java.util.Set;
import org.simplepoint.data.jpa.transaction.CatalogWriteBoundary;
import org.simplepoint.platform.bootstrap.entity.PlatformContributionRecord;
import org.springframework.stereotype.Component;

/** Serializes runtime transitions and installer transfers in the registry's own database. */
@Component
public final class BootstrapRegistryWriteBoundary {
  private final CatalogWriteBoundary boundary;

  /** Requires the registry V2 migration before either old-host or module writes. */
  public BootstrapRegistryWriteBoundary(EntityManager entityManager) {
    boundary =
        new CatalogWriteBoundary(
            entityManager,
            "simpoint_bootstrap_registry_revision",
            Set.of(PlatformContributionRecord.class.getName()),
            failure -> new IllegalStateException("BOOTSTRAP_REGISTRY_" + failure.name()));
  }

  /** Enters before loading contribution entities; nested writes share the transaction lock. */
  public void enter() {
    boundary.enter();
  }
}
