package org.simplepoint.platform.bootstrap;

/**
 * Supplies a platform bootstrap contribution.
 */
@FunctionalInterface
public interface PlatformBootstrapContribution {

  /**
   * Returns the contribution descriptor and action.
   *
   * @return the bootstrap contribution
   */
  BootstrapContribution contribution();
}
