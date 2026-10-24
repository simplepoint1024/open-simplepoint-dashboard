package org.simplepoint.platform.bootstrap.registry.repository;

import org.simplepoint.platform.bootstrap.entity.PlatformContributionRecord;
import org.simplepoint.platform.bootstrap.id.PlatformContributionRecordId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

/** Repository for platform contribution application records. */
public interface PlatformContributionRepository
    extends JpaRepository<PlatformContributionRecord, PlatformContributionRecordId> {

  /**
   * Finds a platform contribution record.
   *
   * @param serviceName the service name
   * @param moduleCode the module code
   * @param contributionType the contribution type
   * @param contributionKey the contribution key
   * @return the platform contribution record, or null
   */
  PlatformContributionRecord
      findFirstByServiceNameAndModuleCodeAndContributionTypeAndContributionKey(
          String serviceName, String moduleCode, String contributionType, String contributionKey);

  /** Detects old host-keyed records which require explicit storage migration before modular use. */
  @org.springframework.data.jpa.repository.Query(
      """
      select (count(c) > 0) from PlatformContributionRecord c
      where c.moduleCode = :moduleCode and c.contributionType = :contributionType
        and c.contributionKey = :contributionKey and c.serviceName not like 'module/%'
      """)
  boolean hasLegacyContribution(
      @Param("moduleCode") String moduleCode,
      @Param("contributionType") String contributionType,
      @Param("contributionKey") String contributionKey);
}
