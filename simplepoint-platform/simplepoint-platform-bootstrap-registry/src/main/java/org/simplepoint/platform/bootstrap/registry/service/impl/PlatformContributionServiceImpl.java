package org.simplepoint.platform.bootstrap.registry.service.impl;

import java.time.LocalDateTime;
import java.util.Objects;
import org.simplepoint.platform.bootstrap.ModuleBootstrapScope;
import org.simplepoint.platform.bootstrap.entity.PlatformContributionRecord;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfers;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapRegistryWriteBoundary;
import org.simplepoint.platform.bootstrap.registry.repository.PlatformContributionRepository;
import org.simplepoint.platform.bootstrap.service.PlatformContributionService;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default platform contribution registry service. */
@Service
@RemoteProvider
public class PlatformContributionServiceImpl implements PlatformContributionService {

  private static final int MAX_ERROR_LENGTH = 1024;

  private final PlatformContributionRepository repository;
  private final BootstrapRegistryWriteBoundary boundary;
  private final BootstrapLegacyTransfers transfers;

  /**
   * Creates the platform contribution registry service.
   *
   * @param repository the contribution repository
   * @param boundary the shared registry mutation boundary
   * @param transfers the durable transferred-scope guard
   */
  public PlatformContributionServiceImpl(
      PlatformContributionRepository repository,
      BootstrapRegistryWriteBoundary boundary,
      BootstrapLegacyTransfers transfers) {
    this.repository = repository;
    this.boundary = boundary;
    this.transfers = transfers;
  }

  @Override
  @Transactional(readOnly = true)
  public Boolean shouldApply(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey,
      String version,
      String checksum) {
    PlatformContributionRecord record =
        find(serviceName, moduleCode, contributionType, contributionKey);
    if (record == null) {
      return true;
    }
    if (ModuleBootstrapScope.isModule(serviceName)
        && PlatformContributionRecord.STATUS_RUNNING.equals(record.getStatus())) {
      throw new IllegalStateException("BOOTSTRAP_RECOVERY_REQUIRED: " + contributionKey);
    }
    if (!Objects.equals(record.getStatus(), PlatformContributionRecord.STATUS_APPLIED)) {
      return true;
    }
    return !Objects.equals(record.getVersion(), version)
        || !Objects.equals(record.getChecksum(), checksum);
  }

  @Override
  @Transactional
  public void markRunning(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey,
      String version,
      String checksum) {
    PlatformContributionRecord record =
        getOrCreate(serviceName, moduleCode, contributionType, contributionKey);
    if (ModuleBootstrapScope.isModule(serviceName)) {
      if (PlatformContributionRecord.STATUS_RUNNING.equals(record.getStatus())) {
        throw new IllegalStateException("BOOTSTRAP_RECOVERY_REQUIRED: " + contributionKey);
      }
      if (PlatformContributionRecord.STATUS_APPLIED.equals(record.getStatus())
          && Objects.equals(record.getVersion(), version)
          && Objects.equals(record.getChecksum(), checksum)) {
        throw new IllegalStateException("BOOTSTRAP_ALREADY_APPLIED: " + contributionKey);
      }
    }
    record.setVersion(version);
    record.setChecksum(checksum);
    record.setStatus(PlatformContributionRecord.STATUS_RUNNING);
    record.setError(null);
    record.setStartedAt(LocalDateTime.now());
    record.setUpdatedAt(LocalDateTime.now());
    repository.saveAndFlush(record);
  }

  @Override
  @Transactional
  public void markApplied(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey,
      String version,
      String checksum) {
    PlatformContributionRecord record =
        getOrCreate(serviceName, moduleCode, contributionType, contributionKey);
    requireRunningRevision(serviceName, record, version, checksum);
    record.setVersion(version);
    record.setChecksum(checksum);
    record.setStatus(PlatformContributionRecord.STATUS_APPLIED);
    record.setError(null);
    record.setAppliedAt(LocalDateTime.now());
    record.setUpdatedAt(LocalDateTime.now());
    repository.saveAndFlush(record);
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
      String error) {
    PlatformContributionRecord record =
        getOrCreate(serviceName, moduleCode, contributionType, contributionKey);
    requireRunningRevision(serviceName, record, version, checksum);
    record.setVersion(version);
    record.setChecksum(checksum);
    record.setStatus(PlatformContributionRecord.STATUS_FAILED);
    record.setError(truncateError(error));
    record.setUpdatedAt(LocalDateTime.now());
    repository.saveAndFlush(record);
  }

  private PlatformContributionRecord find(
      String serviceName, String moduleCode, String contributionType, String contributionKey) {
    transfers.requireScope(serviceName, moduleCode, contributionType, contributionKey);
    var record =
        repository.findFirstByServiceNameAndModuleCodeAndContributionTypeAndContributionKey(
            serviceName, moduleCode, contributionType, contributionKey);
    requireMigratedScope(serviceName, moduleCode, contributionType, contributionKey, record);
    return record;
  }

  private PlatformContributionRecord getOrCreate(
      String serviceName, String moduleCode, String contributionType, String contributionKey) {
    boundary.enter();
    PlatformContributionRecord record =
        find(serviceName, moduleCode, contributionType, contributionKey);
    if (record == null) {
      record = new PlatformContributionRecord();
      record.setServiceName(serviceName);
      record.setModuleCode(moduleCode);
      record.setContributionType(contributionType);
      record.setContributionKey(contributionKey);
    }
    return record;
  }

  private void requireMigratedScope(
      String scope, String moduleCode, String type, String key, PlatformContributionRecord record) {
    if (ModuleBootstrapScope.isModule(scope)
        && record == null
        && repository.hasLegacyContribution(moduleCode, type, key)) {
      throw new IllegalStateException("BOOTSTRAP_LEGACY_STATE_REQUIRES_MIGRATION: " + key);
    }
  }

  private void requireRunningRevision(
      String scope, PlatformContributionRecord record, String version, String checksum) {
    if (ModuleBootstrapScope.isModule(scope)
        && (!PlatformContributionRecord.STATUS_RUNNING.equals(record.getStatus())
            || !Objects.equals(record.getVersion(), version)
            || !Objects.equals(record.getChecksum(), checksum))) {
      throw new IllegalStateException("BOOTSTRAP_STATE_CONFLICT: " + record.getContributionKey());
    }
  }

  private String truncateError(String error) {
    if (error == null || error.length() <= MAX_ERROR_LENGTH) {
      return error;
    }
    return error.substring(0, MAX_ERROR_LENGTH);
  }
}
