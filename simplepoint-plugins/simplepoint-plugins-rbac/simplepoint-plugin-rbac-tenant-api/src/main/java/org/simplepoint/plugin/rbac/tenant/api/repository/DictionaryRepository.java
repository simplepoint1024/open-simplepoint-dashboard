package org.simplepoint.plugin.rbac.tenant.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;

/**
 * Repository for dictionaries.
 */
public interface DictionaryRepository extends BaseRepository<Dictionary, String> {

  /**
   * Determines whether a dictionary exists for the supplied code.
   *
   * @param code the dictionary code
   * @return {@code true} when the dictionary exists
   */
  boolean existsByCode(String code);
}
