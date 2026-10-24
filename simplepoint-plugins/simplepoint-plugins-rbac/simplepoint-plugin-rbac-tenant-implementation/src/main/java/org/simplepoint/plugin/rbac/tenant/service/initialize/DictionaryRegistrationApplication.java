package org.simplepoint.plugin.rbac.tenant.service.initialize;

import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationReceipt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies durable installation authority and commits content plus completion in one transaction.
 */
@Service
public class DictionaryRegistrationApplication {
  private final DictionaryRegistrationStore store;
  private final org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService dictionaries;
  private final org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService items;

  /** Uses identity-owned collaborators; the transport establishes the service identity first. */
  public DictionaryRegistrationApplication(
      DictionaryRegistrationStore store,
      org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService dictionaries,
      org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService items) {
    this.store = store;
    this.dictionaries = dictionaries;
    this.items = items;
  }

  /** Applies only the approved content and commits its receipt atomically with dictionary rows. */
  @Transactional(rollbackFor = Exception.class)
  public DictionaryRegistrationReceipt apply(
      DictionaryContribution declaration, String authenticatedClient) {
    var approval = store.requireApproved(declaration, authenticatedClient);
    if (!approval.applied()) {
      DictionaryRegistrationWrites.apply(
          declaration, () -> BuiltInDictionaryRegistrar.apply(declaration, dictionaries, items));
    }
    return store.complete(declaration, approval);
  }
}
