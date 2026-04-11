package org.simplepoint.plugin.dna.federation.api.service;

import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.dna.federation.api.entity.DataQualityRule;

/**
 * Service contract for data quality rules.
 */
public interface DataQualityRuleService extends BaseService<DataQualityRule, String> {

  /**
   * Finds an active rule by id.
   *
   * @param id rule id
   * @return active rule
   */
  Optional<DataQualityRule> findActiveById(String id);

  /**
   * Finds an active rule by business code.
   *
   * @param code rule code
   * @return active rule
   */
  Optional<DataQualityRule> findActiveByCode(String code);

  /**
   * Counts all active quality rules.
   *
   * @return active rule count
   */
  long countActive();

  /**
   * Executes a quality check on a specific rule and persists
   * the result on the entity (lastRunStatus, lastRunMessage, lastRunAt).
   *
   * @param ruleId rule id
   * @return updated rule with execution result
   */
  DataQualityRule executeCheck(String ruleId);
}
