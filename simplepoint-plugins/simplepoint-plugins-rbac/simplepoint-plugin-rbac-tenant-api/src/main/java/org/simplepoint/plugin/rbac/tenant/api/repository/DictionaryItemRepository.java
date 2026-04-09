package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.vo.DictionaryOptionVo;

/**
 * Repository for dictionary items.
 */
public interface DictionaryItemRepository extends BaseRepository<DictionaryItem, String> {

  /**
   * Updates all item records to use a renamed dictionary code.
   *
   * @param oldCode the previous dictionary code
   * @param newCode the new dictionary code
   */
  void updateDictionaryCode(String oldCode, String newCode);

  /**
   * Deletes all dictionary items that belong to the supplied dictionary codes.
   *
   * @param dictionaryCodes dictionary codes whose items should be removed
   */
  void deleteAllByDictionaryCodes(Collection<String> dictionaryCodes);

  /**
   * Loads enabled option values for the supplied dictionary code.
   *
   * @param dictionaryCode the dictionary code
   * @return the enabled option list
   */
  Collection<DictionaryOptionVo> options(String dictionaryCode);

  /**
   * Counts how many items belong to the supplied dictionary code.
   *
   * @param dictionaryCode the dictionary code
   * @return the number of items
   */
  long countByDictionaryCode(String dictionaryCode);
}
