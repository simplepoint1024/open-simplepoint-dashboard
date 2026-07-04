package org.simplepoint.platform.bootstrap;

/**
 * Describes a platform bootstrap contribution.
 *
 * @param moduleCode       the owning module code
 * @param contributionType the contribution type
 * @param contributionKey  the stable contribution key
 * @param version          the semantic contribution version
 * @param checksum         the content checksum or version-derived fingerprint
 * @param order            the execution order
 * @param action           the contribution action
 */
public record BootstrapContribution(
    String moduleCode,
    String contributionType,
    String contributionKey,
    String version,
    String checksum,
    int order,
    Action action
) {

  /**
   * Creates a versioned bootstrap contribution.
   *
   * @param moduleCode       the owning module code
   * @param contributionType the contribution type
   * @param contributionKey  the stable contribution key
   * @param version          the semantic contribution version
   * @param order            the execution order
   * @param action           the contribution action
   * @return the bootstrap contribution
   */
  public static BootstrapContribution versioned(
      String moduleCode,
      String contributionType,
      String contributionKey,
      String version,
      int order,
      Action action
  ) {
    return new BootstrapContribution(
        moduleCode,
        contributionType,
        contributionKey,
        version,
        moduleCode + ":" + contributionType + ":" + contributionKey + ":" + version,
        order,
        action
    );
  }

  /**
   * Runs bootstrap contribution logic.
   */
  @FunctionalInterface
  public interface Action {

    /**
     * Runs the contribution.
     *
     * @throws Exception when contribution execution fails
     */
    void run() throws Exception;
  }
}
