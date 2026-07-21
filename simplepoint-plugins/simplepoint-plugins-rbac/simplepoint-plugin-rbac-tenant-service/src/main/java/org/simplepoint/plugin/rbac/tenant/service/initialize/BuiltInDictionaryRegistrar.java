package org.simplepoint.plugin.rbac.tenant.service.initialize;

import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;

/**
 * Shared registration support for dictionaries shipped by platform modules.
 */
final class BuiltInDictionaryRegistrar {

  private BuiltInDictionaryRegistrar() {
  }

  static Dictionary ensureDictionary(
      DictionaryService dictionaryService,
      String code,
      String name,
      String description,
      int sort
  ) {
    return dictionaryService.findAll(Map.of("code", code))
        .stream()
        .findFirst()
        .map(existing -> {
          existing.setName(name);
          existing.setDescription(description);
          existing.setSort(sort);
          existing.setEnabled(Boolean.TRUE);
          return (Dictionary) dictionaryService.modifyById(existing);
        })
        .orElseGet(() -> {
          Dictionary dictionary = new Dictionary();
          dictionary.setName(name);
          dictionary.setCode(code);
          dictionary.setDescription(description);
          dictionary.setSort(sort);
          dictionary.setEnabled(Boolean.TRUE);
          return dictionaryService.create(dictionary);
        });
  }

  static void ensureItem(
      DictionaryItemService dictionaryItemService,
      String dictionaryCode,
      String value,
      String name,
      String i18nKey,
      String description,
      int sort
  ) {
    DictionaryItem current = dictionaryItemService.findAll(Map.of("dictionaryCode", dictionaryCode))
        .stream()
        .filter(item -> Objects.equals(item.getValue(), value))
        .findFirst()
        .orElse(null);
    if (current == null) {
      current = new DictionaryItem();
      current.setDictionaryCode(dictionaryCode);
      current.setValue(value);
      applyItemMetadata(current, name, i18nKey, description, sort);
      current.setEnabled(Boolean.TRUE);
      dictionaryItemService.create(current);
      return;
    }
    current.setDictionaryCode(dictionaryCode);
    current.setValue(value);
    applyItemMetadata(current, name, i18nKey, description, sort);
    current.setEnabled(Boolean.TRUE);
    dictionaryItemService.modifyById(current);
  }

  private static void applyItemMetadata(
      DictionaryItem item,
      String name,
      String i18nKey,
      String description,
      int sort
  ) {
    item.setName(name);
    item.setI18nKey(i18nKey);
    item.setDescription(description);
    item.setSort(sort);
  }
}
