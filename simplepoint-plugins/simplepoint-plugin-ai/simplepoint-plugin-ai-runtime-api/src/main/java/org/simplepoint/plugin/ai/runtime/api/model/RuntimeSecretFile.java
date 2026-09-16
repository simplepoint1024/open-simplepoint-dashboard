package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * One short-lived secret file transported only across the Runtime mTLS channel.
 *
 * @param name safe file name exposed under the workload secret directory
 * @param value plaintext value; never persisted in the workload record
 * @param targetPath absolute file path below the Runtime secret mount
 * @param targetEnvironment optional child-process environment variable
 */
public record RuntimeSecretFile(
    String name,
    String value,
    String targetPath,
    String targetEnvironment
) {

  /** Preserves the legacy code-named FILE binding. */
  public RuntimeSecretFile(final String name, final String value) {
    this(name, value, null, null);
  }
}
