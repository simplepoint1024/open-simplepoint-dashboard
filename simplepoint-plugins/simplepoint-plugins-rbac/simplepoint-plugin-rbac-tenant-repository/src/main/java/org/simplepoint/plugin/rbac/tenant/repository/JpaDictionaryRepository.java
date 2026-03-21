package org.simplepoint.plugin.rbac.tenant.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Jpa repository for dictionaries.
 */
@Repository
public interface JpaDictionaryRepository extends BaseRepository<Dictionary, String>, DictionaryRepository {

  @Override
  @Query("""
      select case when count(d) > 0 then true else false end
      from Dictionary d
      where d.code = :code
      """)
  boolean existsByCode(@Param("code") String code);
}
