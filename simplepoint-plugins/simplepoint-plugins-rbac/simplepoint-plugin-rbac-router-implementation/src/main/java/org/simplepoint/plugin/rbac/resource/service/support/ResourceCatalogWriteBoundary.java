package org.simplepoint.plugin.rbac.resource.service.support;

import jakarta.persistence.EntityManager;
import java.util.Set;
import org.simplepoint.data.jpa.transaction.CatalogWriteBoundary;
import org.simplepoint.security.entity.MicroModule;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceAncestor;
import org.simplepoint.security.resource.ResourceRegistrationException;
import org.simplepoint.security.resource.ResourceRegistrationException.Code;
import org.springframework.stereotype.Component;

/** Resource-owned adapter for the shared database catalog mutation boundary. */
@Component
public final class ResourceCatalogWriteBoundary {
  private final CatalogWriteBoundary boundary;

  /** Uses the identity module's transaction-bound persistence resource and migration-owned row. */
  public ResourceCatalogWriteBoundary(EntityManager entityManager) {
    boundary =
        new CatalogWriteBoundary(
            entityManager,
            "simpoint_resource_catalog_revision",
            Set.of(
                Resource.class.getName(),
                org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource.class.getName(),
                org.simplepoint.security.entity.RoleResourceGrant.class.getName(),
                org.simplepoint.security.entity.DataScope.class.getName(),
                ResourceAncestor.class.getName(),
                MicroModule.class.getName()),
            failure ->
                new ResourceRegistrationException(
                    failure == CatalogWriteBoundary.Failure.CONTEXT_CONFLICT
                        ? Code.CATALOG_CONTEXT_CONFLICT
                        : Code.CATALOG_UNAVAILABLE));
  }

  /**
   * Enters before reading or modifying resources, retaining the existing domain failure contract.
   */
  public void enter() {
    boundary.enter();
  }
}
