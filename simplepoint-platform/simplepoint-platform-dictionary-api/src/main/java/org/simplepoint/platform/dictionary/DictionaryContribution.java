package org.simplepoint.platform.dictionary;

import java.util.HashSet;
import java.util.List;

/** Declarative bootstrap metadata; owner labels do not establish caller authorization. */
public record DictionaryContribution(
    String moduleCode,
    String contributionKey,
    String version,
    int order,
    List<DictionaryDeclaration> dictionaries) {
  /** Freezes a bounded, unique batch while retaining original declaration and execution order. */
  public DictionaryContribution {
    DeclarationChecks.identifier(moduleCode, "moduleCode", 128);
    DeclarationChecks.identifier(contributionKey, "contributionKey", 128);
    DeclarationChecks.identifier(version, "version", 64);
    dictionaries = DeclarationChecks.list(dictionaries, "dictionaries", 1, 64);
    var codes = new HashSet<String>();
    int items = 0;
    for (var dictionary : dictionaries) {
      if (!codes.add(dictionary.code())) {
        throw DeclarationChecks.invalid("dictionaries.duplicate");
      }
      items += dictionary.items().size();
    }
    if (items > 4096) {
      throw DeclarationChecks.invalid("dictionaries.itemLimit");
    }
  }
}
