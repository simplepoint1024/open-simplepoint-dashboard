package org.simplepoint.plugin.rbac.tenant.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;

/**
 * Repository for dictionaries.
 */
public interface DictionaryRepository extends BaseRepository<Dictionary, String> {

  boolean existsByCode(String code);
}
