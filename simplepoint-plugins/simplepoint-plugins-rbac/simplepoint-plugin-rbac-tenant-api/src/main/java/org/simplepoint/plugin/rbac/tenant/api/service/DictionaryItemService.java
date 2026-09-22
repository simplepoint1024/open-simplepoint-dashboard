package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Map;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service for dictionary items.
 */
public interface DictionaryItemService extends BaseService<DictionaryItem, String> {
  /** Lists one dictionary's items, intersecting user conditions with the fixed parent scope. */
  Page<DictionaryItem> limitInDictionary(String dictionaryCode, Map<String, String> filters, Pageable pageable);
}
