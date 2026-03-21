package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.vo.DictionaryOptionVo;

/**
 * Repository for dictionary items.
 */
public interface DictionaryItemRepository extends BaseRepository<DictionaryItem, String> {

  void updateDictionaryCode(String oldCode, String newCode);

  void deleteAllByDictionaryCodes(Collection<String> dictionaryCodes);

  Collection<DictionaryOptionVo> options(String dictionaryCode);

  long countByDictionaryCode(String dictionaryCode);
}
