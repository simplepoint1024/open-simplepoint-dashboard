package org.simplepoint.platform.bootstrap.registry.migration;

import java.util.List;
import java.util.UUID;

/** Trusted local installer inputs; never included in the bootstrap RPC contract. */
public final class BootstrapLegacyTransfer {
  private BootstrapLegacyTransfer() {}

  /** Historical contribution identity, independent of its physical host. */
  public record Identity(String moduleCode, String type, String key) {
    /** Matches the persistent key limits without accepting blank or control-character keys. */
    public Identity {
      text(moduleCode, 128);
      text(type, 64);
      text(key, 160);
    }
  }

  /** Exact successful legacy revision and all source hosts observed by a read-only preview. */
  public record Snapshot(
      Identity identity,
      String version,
      String checksum,
      String fingerprint,
      List<String> sourceServices) {
    /** Immutable, bounded preview; no credentials or arbitrary business payloads. */
    public Snapshot {
      java.util.Objects.requireNonNull(identity);
      text(version, 64);
      text(checksum, 256);
      if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Invalid bootstrap transfer fingerprint");
      }
      sourceServices = List.copyOf(sourceServices);
      if (sourceServices.isEmpty()
          || sourceServices.size() > 64
          || sourceServices.stream().distinct().count() != sourceServices.size()) {
        throw new IllegalArgumentException("Invalid bootstrap source set");
      }
      sourceServices.forEach(source -> text(source, 128));
    }
  }

  /** An operation moves authority to a module ledger or a dedicated domain owner. */
  public record Command(UUID operationId, String targetScope, Snapshot expected) {
    /** Target scope is explicit; the installer must stop old executors before transfer. */
    public Command {
      java.util.Objects.requireNonNull(operationId);
      java.util.Objects.requireNonNull(expected);
      if (targetScope == null
          || targetScope.length() > 128
          || !targetScope.matches(
              "(?:module/[a-z][a-z0-9]*(?:-[a-z0-9]+)*|owner/[a-z][a-z0-9]*(?:-[a-z0-9]+)*/[a-z][a-z0-9]*(?:-[a-z0-9]+)*)")) {
        throw new IllegalArgumentException("Invalid bootstrap transfer target");
      }
    }
  }

  /** Durable proof of the retired registry revision; no claim of business database atomicity. */
  public record Receipt(
      UUID operationId,
      String targetScope,
      Identity identity,
      String version,
      String checksum,
      String fingerprint) {}

  private static void text(String value, int limit) {
    if (value == null
        || value.isBlank()
        || value.length() > limit
        || !value.equals(value.strip())
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Invalid bootstrap transfer metadata");
    }
  }
}
