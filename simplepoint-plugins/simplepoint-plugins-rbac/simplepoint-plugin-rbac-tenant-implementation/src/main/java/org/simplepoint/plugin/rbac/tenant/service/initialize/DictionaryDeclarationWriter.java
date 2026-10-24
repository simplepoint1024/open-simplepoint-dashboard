package org.simplepoint.plugin.rbac.tenant.service.initialize;

import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.simplepoint.plugin.rbac.tenant.service.support.DictionaryCatalogWriteBoundary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Identity-owned atomic application of a bounded dictionary declaration. */
@Service
public class DictionaryDeclarationWriter {
  private final DictionaryCatalogWriteBoundary boundary;
  private final DictionaryService dictionaries;
  private final DictionaryRegistrationStore registrations;
  private final DictionaryItemService items;

  /** Receives local persistence collaborators; this writer is never an exported RPC provider. */
  public DictionaryDeclarationWriter(
      DictionaryCatalogWriteBoundary boundary,
      DictionaryService dictionaries,
      DictionaryItemService items,
      DictionaryRegistrationStore registrations) {
    this.boundary = boundary;
    this.dictionaries = dictionaries;
    this.items = items;
    this.registrations = registrations;
  }

  /**
   * Applies all dictionary/item updates in one transaction or rolls the entire declaration back.
   */
  @Transactional(rollbackFor = Exception.class)
  public void apply(DictionaryContribution declaration) {
    java.util.Objects.requireNonNull(declaration, "declaration");
    boundary.enter();
    registrations.requireLegacy(declaration);
    BuiltInDictionaryRegistrar.apply(declaration, dictionaries, items);
  }
}
