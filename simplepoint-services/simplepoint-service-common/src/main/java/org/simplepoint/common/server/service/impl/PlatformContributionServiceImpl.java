package org.simplepoint.common.server.service.impl;

import java.time.LocalDateTime;
import java.util.Objects;
import org.simplepoint.common.server.repository.PlatformContributionRepository;
import org.simplepoint.platform.bootstrap.entity.PlatformContributionRecord;
import org.simplepoint.platform.bootstrap.service.PlatformContributionService;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default platform contribution registry service.
 */
@Service
@RemoteProvider
public class PlatformContributionServiceImpl implements PlatformContributionService {

  private static final int MAX_ERROR_LENGTH = 1024;

  private final PlatformContributionRepository repository;

  /**
   * Creates the platform contribution registry service.
   *
   * @param repository the contribution repository
   */
  public PlatformContributionServiceImpl(PlatformContributionRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public Boolean shouldApply(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey,
      String version,
      String checksum
  ) {
    PlatformContributionRecord record = find(serviceName, moduleCode, contributionType, contributionKey);
    if (record == null) {
      return true;
    }
    if (!Objects.equals(record.getStatus(), PlatformContributionRecord.STATUS_APPLIED)) {
      return true;
    }
    return !Objects.equals(record.getVersion(), version) || !Objects.equals(record.getChecksum(), checksum);
  }

  @Override
  @Transactional
  public void markRunning(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey,
      String version,
      String checksum
  ) {
    PlatformContributionRecord record = getOrCreate(serviceName, moduleCode, contributionType, contributionKey);
    record.setVersion(version);
    record.setChecksum(checksum);
    record.setStatus(PlatformContributionRecord.STATUS_RUNNING);
    record.setError(null);
    record.setStartedAt(LocalDateTime.now());
    record.setUpdatedAt(LocalDateTime.now());
    repository.save(record);
  }

  @Override
  @Transactional
  public void markApplied(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey,
      String version,
      String checksum
  ) {
    PlatformContributionRecord record = getOrCreate(serviceName, moduleCode, contributionType, contributionKey);
    record.setVersion(version);
    record.setChecksum(checksum);
    record.setStatus(PlatformContributionRecord.STATUS_APPLIED);
    record.setError(null);
    record.setAppliedAt(LocalDateTime.now());
    record.setUpdatedAt(LocalDateTime.now());
    repository.save(record);
  }

  @Override
  @Transactional
  public void markFailed(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey,
      String version,
      String checksum,
      String error
  ) {
    PlatformContributionRecord record = getOrCreate(serviceName, moduleCode, contributionType, contributionKey);
    record.setVersion(version);
    record.setChecksum(checksum);
    record.setStatus(PlatformContributionRecord.STATUS_FAILED);
    record.setError(truncateError(error));
    record.setUpdatedAt(LocalDateTime.now());
    repository.save(record);
  }

  private PlatformContributionRecord find(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey
  ) {
    return repository.findFirstByServiceNameAndModuleCodeAndContributionTypeAndContributionKey(
        serviceName,
        moduleCode,
        contributionType,
        contributionKey
    );
  }

  private PlatformContributionRecord getOrCreate(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey
  ) {
    PlatformContributionRecord record = find(serviceName, moduleCode, contributionType, contributionKey);
    if (record == null) {
      record = new PlatformContributionRecord();
      record.setServiceName(serviceName);
      record.setModuleCode(moduleCode);
      record.setContributionType(contributionType);
      record.setContributionKey(contributionKey);
    }
    return record;
  }

  private String truncateError(String error) {
    if (error == null || error.length() <= MAX_ERROR_LENGTH) {
      return error;
    }
    return error.substring(0, MAX_ERROR_LENGTH);
  }
}
