package org.simplepoint.platform.dictionary;

import java.util.HashSet;
import java.util.List;

/** Immutable dictionary metadata with ordered, uniquely valued item declarations. */
public record DictionaryDeclaration(
    String code, String name, String description, int sort, List<DictionaryItemDeclaration> items) {
  /** Rejects ambiguous or oversized declarations before any registration side effects. */
  public DictionaryDeclaration {
    DeclarationChecks.identifier(code, "dictionary.code", 128);
    DeclarationChecks.text(name, "dictionary.name", 128, true);
    DeclarationChecks.text(description, "dictionary.description", 512, false);
    items = DeclarationChecks.list(items, "dictionary.items", 0, 1024);
    var values = new HashSet<String>();
    for (var item : items) {
      if (!values.add(item.value())) {
        throw DeclarationChecks.invalid("dictionary.items.duplicate");
      }
    }
  }
}
