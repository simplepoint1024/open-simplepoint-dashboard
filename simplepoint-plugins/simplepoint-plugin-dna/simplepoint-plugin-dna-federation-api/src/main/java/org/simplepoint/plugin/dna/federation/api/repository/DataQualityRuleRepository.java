package org.simplepoint.plugin.dna.federation.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.DataQualityRule;

/**
 * Repository contract for data quality rules.
 */
public interface DataQualityRuleRepository extends BaseRepository<DataQualityRule, String> {

  /**
   * Finds an active quality rule by id.
   *
   * @param id rule id
   * @return active rule
   */
  Optional<DataQualityRule> findActiveById(String id);

  /**
   * Finds an active quality rule by business code.
   *
   * @param code rule code
   * @return active rule
   */
  Optional<DataQualityRule> findActiveByCode(String code);
}
