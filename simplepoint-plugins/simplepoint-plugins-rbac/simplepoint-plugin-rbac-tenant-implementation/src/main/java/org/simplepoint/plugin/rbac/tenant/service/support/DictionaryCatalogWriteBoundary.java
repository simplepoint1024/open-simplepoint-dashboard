package org.simplepoint.plugin.rbac.tenant.service.support;

import jakarta.persistence.EntityManager;
import java.util.Set;
import org.simplepoint.data.jpa.transaction.CatalogWriteBoundary;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.error.DictionaryWriteException;
import org.springframework.stereotype.Component;

/** Serializes dictionary and item mutations on one identity-owned transactional revision. */
@Component
public final class DictionaryCatalogWriteBoundary {
  private final CatalogWriteBoundary boundary;

  /** No optional fallback: the catalog migration must exist before any dictionary write. */
  public DictionaryCatalogWriteBoundary(EntityManager entityManager) {
    boundary =
        new CatalogWriteBoundary(
            entityManager,
            "simpoint_dictionary_catalog_revision",
            Set.of(Dictionary.class.getName(), DictionaryItem.class.getName()),
            failure ->
                new DictionaryWriteException(
                    failure == CatalogWriteBoundary.Failure.CONTEXT_CONFLICT
                        ? DictionaryWriteException.Code.CONTEXT_CONFLICT
                        : DictionaryWriteException.Code.UNAVAILABLE));
  }

  /** Must be called before reading dictionaries/items inside a writable transaction. */
  public void enter() {
    boundary.enter();
  }
}
