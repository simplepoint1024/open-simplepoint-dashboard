package org.simplepoint.plugin.dna.federation.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.dna.federation.api.entity.DataQualityRule;
import org.simplepoint.plugin.dna.federation.api.repository.DataQualityRuleRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for data quality rules.
 */
@Repository
public interface JpaDataQualityRuleRepository
    extends BaseRepository<DataQualityRule, String>, DataQualityRuleRepository {

  @Override
  @Query("""
      select r
      from DataQualityRule r
      where r.id = :id and r.deletedAt is null
      """)
  Optional<DataQualityRule> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select r
      from DataQualityRule r
      where r.code = :code and r.deletedAt is null
      """)
  Optional<DataQualityRule> findActiveByCode(@Param("code") String code);
}
