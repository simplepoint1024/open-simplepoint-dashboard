package org.simplepoint.plugin.rbac.tenant.repository;

import java.util.Collection;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryItemRepository;
import org.simplepoint.plugin.rbac.tenant.api.vo.DictionaryOptionVo;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Jpa repository for dictionary items.
 */
@Repository
public interface JpaDictionaryItemRepository extends BaseRepository<DictionaryItem, String>, DictionaryItemRepository {

  @Override
  @Modifying
  @Query("""
      update DictionaryItem di
      set di.dictionaryCode = :newCode
      where di.dictionaryCode = :oldCode
      """)
  void updateDictionaryCode(@Param("oldCode") String oldCode, @Param("newCode") String newCode);

  @Override
  @Modifying
  @Query("delete from DictionaryItem di where di.dictionaryCode in :dictionaryCodes")
  void deleteAllByDictionaryCodes(@Param("dictionaryCodes") Collection<String> dictionaryCodes);

  @Override
  @Query("""
      select new org.simplepoint.plugin.rbac.tenant.api.vo.DictionaryOptionVo(di.value, di.name)
      from DictionaryItem di
      join Dictionary d on d.code = di.dictionaryCode
      where di.dictionaryCode = :dictionaryCode
        and coalesce(d.enabled, true) = true
        and coalesce(di.enabled, true) = true
      order by coalesce(di.sort, 2147483647), di.name, di.value
      """)
  Collection<DictionaryOptionVo> options(@Param("dictionaryCode") String dictionaryCode);

  @Override
  @Query("""
      select count(di)
      from DictionaryItem di
      where di.dictionaryCode = :dictionaryCode
      """)
  long countByDictionaryCode(@Param("dictionaryCode") String dictionaryCode);
}
