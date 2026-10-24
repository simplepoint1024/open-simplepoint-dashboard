package org.simplepoint.platform.dictionary;

/**
 * Value metadata only: no database IDs, tenant/audit fields, enabled state or executable action.
 */
public record DictionaryItemDeclaration(
    String value, String name, String i18nKey, String description, int sort) {
  /** Validates database-compatible Unicode lengths without silently normalizing values. */
  public DictionaryItemDeclaration {
    DeclarationChecks.text(value, "item.value", 128, true);
    DeclarationChecks.text(name, "item.name", 128, true);
    DeclarationChecks.text(i18nKey, "item.i18nKey", 255, false);
    DeclarationChecks.text(description, "item.description", 512, false);
    if (!value.equals(value.strip())) {
      throw DeclarationChecks.invalid("item.value");
    }
  }
}
