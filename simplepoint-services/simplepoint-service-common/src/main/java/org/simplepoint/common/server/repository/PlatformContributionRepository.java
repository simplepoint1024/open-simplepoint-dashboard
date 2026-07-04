package org.simplepoint.common.server.repository;

import org.simplepoint.platform.bootstrap.entity.PlatformContributionRecord;
import org.simplepoint.platform.bootstrap.id.PlatformContributionRecordId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for platform contribution application records.
 */
public interface PlatformContributionRepository extends
    JpaRepository<PlatformContributionRecord, PlatformContributionRecordId> {

  /**
   * Finds a platform contribution record.
   *
   * @param serviceName      the service name
   * @param moduleCode       the module code
   * @param contributionType the contribution type
   * @param contributionKey  the contribution key
   * @return the platform contribution record, or null
   */
  PlatformContributionRecord findFirstByServiceNameAndModuleCodeAndContributionTypeAndContributionKey(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey
  );
}
