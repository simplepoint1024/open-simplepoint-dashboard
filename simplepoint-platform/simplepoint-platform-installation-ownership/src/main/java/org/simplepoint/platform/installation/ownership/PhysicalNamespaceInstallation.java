package org.simplepoint.platform.installation.ownership;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.data.ownership.PhysicalNamespaceDescriptor;
import org.simplepoint.data.ownership.PhysicalOwnershipException;
import org.simplepoint.data.ownership.PhysicalOwnershipInspection;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationPlan;

/** Explicit installer adapter; namespace creation never certifies legacy adoption or activation. */
public final class PhysicalNamespaceInstallation {
  private PhysicalNamespaceInstallation() {}

  /** Reads a proposed target without exposing driver URLs or changing physical ownership. */
  public static Target review(String key, DataSource source, String namespace, UUID identity) {
    Objects.requireNonNull(identity, "identity");
    try (var connection = source.getConnection()) {
      var inspection = PhysicalOwnershipInstaller.inspect(connection, namespace);
      if (inspection.namespaceId() != null && !identity.equals(inspection.namespaceId())) {
        throw new IllegalStateException("PHYSICAL_NAMESPACE_INSTALLATION_TARGET_CHANGED");
      }
      return new Target(key, inspection.target(), identity);
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  /** Exact reviewed input. Connection credentials and configured URLs never enter the plan. */
  public record Target(String key, PhysicalNamespaceDescriptor descriptor, UUID namespaceId) {
    /** Validates the logical binding separately from native, case-sensitive coordinates. */
    public Target {
      if (key == null || !key.matches("[a-z][a-z0-9-]{0,63}")) {
        throw new IllegalArgumentException("Invalid physical namespace installation key");
      }
      Objects.requireNonNull(descriptor, "descriptor");
      Objects.requireNonNull(namespaceId, "namespaceId");
    }

    String fingerprint() {
      return InstallationFingerprint.of(
          "physical-namespace-installation-v1",
          key,
          namespaceId.toString(),
          descriptor.dialect(),
          descriptor.serverIdentity(),
          descriptor.database(),
          descriptor.databaseIdentity(),
          descriptor.namespace(),
          descriptor.namespaceIdentity(),
          descriptor.protocolChecksum());
    }
  }

  /**
   * Performs explicit read-only target review before any journal or DDL mutation. All target
   * bindings must match, including aliases; a later failure cannot silently install earlier ones.
   */
  public static Prepared prepare(
      UUID operation,
      String deploymentTarget,
      long generation,
      String releaseFingerprint,
      List<Target> reviewedTargets,
      Map<String, DataSource> sources) {
    var targets = List.copyOf(reviewedTargets);
    var bindings = Map.copyOf(sources);
    var keys = new HashSet<String>();
    var coordinates = new HashSet<PhysicalNamespaceDescriptor>();
    if (targets.isEmpty() || targets.size() > 512) {
      throw new IllegalArgumentException("Invalid physical namespace installation size");
    }
    for (var target : targets) {
      if (!keys.add(target.key()) || !coordinates.add(target.descriptor())) {
        throw new IllegalArgumentException("Duplicate physical namespace installation binding");
      }
    }
    if (!keys.equals(bindings.keySet())) {
      throw new IllegalArgumentException("Physical namespace installation bindings differ");
    }
    var actions = new ArrayList<InstallationAction>();
    for (var target : targets) {
      actions.add(action(target, bindings.get(target.key())));
    }
    var plan =
        new InstallationPlan(
            operation,
            deploymentTarget,
            generation,
            releaseFingerprint,
            actions.stream().map(InstallationAction::definition).toList());
    // Validate the whole immutable plan before making even a partial directory installation.
    for (var target : targets) {
      inspect(target, bindings.get(target.key()));
    }
    return new Prepared(plan, actions);
  }

  /** Privileged executable bindings are ephemeral; persist the exact Target list before run. */
  public record Prepared(InstallationPlan plan, List<InstallationAction> actions) {
    /** Copies the ordered actions that correspond to the persisted plan. */
    public Prepared {
      Objects.requireNonNull(plan, "plan");
      actions = List.copyOf(actions);
    }

    /** A cached completed journal never substitutes for current physical directory evidence. */
    public void verifyComplete() {
      for (var action : actions) {
        if (action.completedReceipt().isEmpty()) {
          throw new IllegalStateException("PHYSICAL_NAMESPACE_INSTALLATION_INCOMPLETE");
        }
      }
    }
  }

  private static InstallationAction action(Target target, DataSource source) {
    var step =
        new InstallationPlan.Step(
            "physical-ownership/" + target.key(),
            "physical-namespace-directory",
            1,
            target.fingerprint(),
            InstallationPlan.Retry.DURABLE_IDEMPOTENT);
    String receipt =
        InstallationFingerprint.of(step.kind(), step.inputFingerprint(), "verified-v1");
    return new InstallationAction() {
      @Override
      public InstallationPlan.Step definition() {
        return step;
      }

      @Override
      public String apply() {
        try (var connection = source.getConnection()) {
          PhysicalOwnershipInstaller.installReviewed(
              connection, target.descriptor(), target.namespaceId());
        } catch (SQLException failure) {
          throw unavailable(failure);
        }
        return completedReceipt()
            .orElseThrow(
                () -> new IllegalStateException("PHYSICAL_NAMESPACE_INSTALLATION_INCOMPLETE"));
      }

      @Override
      public Optional<String> completedReceipt() {
        return inspect(target, source).complete() ? Optional.of(receipt) : Optional.empty();
      }
    };
  }

  private static PhysicalOwnershipInspection inspect(Target expected, DataSource source) {
    try (var connection = source.getConnection()) {
      var actual =
          PhysicalOwnershipInstaller.inspect(connection, expected.descriptor().namespace());
      if (!expected.descriptor().equals(actual.target())
          || actual.namespaceId() != null && !expected.namespaceId().equals(actual.namespaceId())) {
        throw new IllegalStateException("PHYSICAL_NAMESPACE_INSTALLATION_TARGET_CHANGED");
      }
      return actual;
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  private static IllegalStateException unavailable(SQLException failure) {
    // A JDBC connection failure can include a credential-bearing URL; retain bounded diagnostics,
    // not the original driver message, in installer reports and journal exception chains.
    String diagnostic =
        failure instanceof PhysicalOwnershipException ownership ? ownership.code().name() : "JDBC";
    return new IllegalStateException(
        "PHYSICAL_NAMESPACE_INSTALLATION_UNAVAILABLE: " + diagnostic,
        new SQLException(
            "Physical namespace installation metadata unavailable",
            failure.getSQLState(),
            failure.getErrorCode()));
  }
}
