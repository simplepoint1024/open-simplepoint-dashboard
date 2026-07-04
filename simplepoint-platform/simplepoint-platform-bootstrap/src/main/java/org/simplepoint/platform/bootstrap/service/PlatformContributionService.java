package org.simplepoint.platform.bootstrap.service;

import org.simplepoint.remoting.RemoteContract;

/**
 * Registry service for platform bootstrap contribution application state.
 */
@RemoteContract(name = "platform.contribution")
public interface PlatformContributionService {

  /**
   * Checks whether a contribution should be applied.
   *
   * @param serviceName      the service name
   * @param moduleCode       the module code
   * @param contributionType the contribution type
   * @param contributionKey  the contribution key
   * @param version          the contribution version
   * @param checksum         the contribution checksum
   * @return true when the contribution should be applied
   */
  Boolean shouldApply(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey,
      String version,
      String checksum
  );

  /**
   * Marks a contribution as running.
   *
   * @param serviceName      the service name
   * @param moduleCode       the module code
   * @param contributionType the contribution type
   * @param contributionKey  the contribution key
   * @param version          the contribution version
   * @param checksum         the contribution checksum
   */
  void markRunning(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey,
      String version,
      String checksum
  );

  /**
   * Marks a contribution as applied.
   *
   * @param serviceName      the service name
   * @param moduleCode       the module code
   * @param contributionType the contribution type
   * @param contributionKey  the contribution key
   * @param version          the contribution version
   * @param checksum         the contribution checksum
   */
  void markApplied(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey,
      String version,
      String checksum
  );

  /**
   * Marks a contribution as failed.
   *
   * @param serviceName      the service name
   * @param moduleCode       the module code
   * @param contributionType the contribution type
   * @param contributionKey  the contribution key
   * @param version          the contribution version
   * @param checksum         the contribution checksum
   * @param error            the failure message
   */
  void markFailed(
      String serviceName,
      String moduleCode,
      String contributionType,
      String contributionKey,
      String version,
      String checksum,
      String error
  );
}
