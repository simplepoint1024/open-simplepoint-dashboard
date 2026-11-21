package org.simplepoint.installation.host;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.simplepoint.data.ownership.PhysicalAdoptionManifest;
import org.simplepoint.data.ownership.PhysicalNamespaceDescriptor;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationPlan;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalBindingTargetResolver;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipAdoptionPlan;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipControlAdopter;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipInstallationAdapter;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipModelReview;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalOwnershipSchemaInspector;

/** Ordered owner operations for the shared durable installation journal, with no domain SQL. */
final class DnaOwnershipAdoptionCoordinator {
  private final PhysicalOwnershipAdoptionPlan plan;
  private final PhysicalOwnershipControlAdopter control;
  private final PhysicalOwnershipInstallationAdapter reader;
  private final PhysicalOwnershipSchemaInspector inspector;
  private final Map<PhysicalNamespaceDescriptor, DataSource> sources;
  private final Map<PhysicalNamespaceDescriptor, List<PhysicalOwnershipSchemaInspector.Table>>
      evidence;
  private final Runnable requireRetiredWriters;

  DnaOwnershipAdoptionCoordinator(
      PhysicalOwnershipAdoptionPlan plan,
      DataSource controlStore,
      Map<PhysicalBindingTargetResolver.Source, DataSource> supplied,
      List<PhysicalOwnershipSchemaInspector.Table> tables,
      PhysicalOwnershipSchemaInspector inspector,
      Runnable requireRetiredWriters) {
    this.plan = Objects.requireNonNull(plan, "plan");
    this.control = new PhysicalOwnershipControlAdopter(controlStore);
    this.reader = new PhysicalOwnershipInstallationAdapter(controlStore);
    this.inspector = Objects.requireNonNull(inspector, "inspector");
    this.requireRetiredWriters =
        Objects.requireNonNull(requireRetiredWriters, "requireRetiredWriters");
    if (plan.manifests().isEmpty() || plan.manifests().size() > 255) {
      throw new IllegalArgumentException("Adoption requires one to 255 reviewed namespaces");
    }
    supplied = Map.copyOf(supplied);
    if (!PhysicalBindingTargetResolver.review(plan.models().inventory(), supplied)
        .fingerprint()
        .equals(plan.targets().fingerprint())) {
      throw new IllegalStateException("Physical adoption target changed");
    }
    var resolved =
        plan.targets().bindings().stream().collect(Collectors.toMap(b -> b.bindingId(), b -> b));
    var selected = new java.util.HashMap<PhysicalNamespaceDescriptor, DataSource>();
    for (var binding : plan.models().inventory().bindings()) {
      selected.putIfAbsent(
          resolved.get(binding.bindingId()).namespace().target(),
          supplied.get(
              new PhysicalBindingTargetResolver.Source(
                  binding.target().dataSourceId(), binding.target().catalogName())));
    }
    this.sources = Map.copyOf(selected);
    this.evidence =
        tables.stream()
            .collect(Collectors.groupingBy(PhysicalOwnershipSchemaInspector.Table::namespace));
    for (var manifest : plan.manifests()) {
      var expected = evidence.get(manifest.target());
      if (expected == null || expected.size() != manifest.claims().size()) {
        throw new IllegalArgumentException("Missing physical adoption schema evidence");
      }
      var claims =
          expected.stream()
              .map(
                  t ->
                      new PhysicalAdoptionManifest.Claim(
                          t.table(), t.owner(), t.exists(), t.actualChecksum()))
              .sorted(java.util.Comparator.comparing(PhysicalAdoptionManifest.Claim::table))
              .toList();
      if (!claims.equals(manifest.claims())) {
        throw new IllegalArgumentException("Physical adoption schema evidence differs");
      }
    }
  }

  /**
   * Domain facts commit before activation. The journal owns retries and uncertain acknowledgements.
   */
  List<InstallationAction> actions() {
    var actions = new ArrayList<InstallationAction>();
    for (var manifest : plan.manifests()) {
      actions.add(
          action(
              "claim/" + manifest.namespaceId(),
              manifest.fingerprint(),
              () -> {
                requireRetiredWriters.run();
                verifyModels(false);
                try (var connection = sources.get(manifest.target()).getConnection()) {
                  PhysicalOwnershipInstaller.adoptReviewed(
                      connection,
                      manifest,
                      (c, m) -> inspector.verifyReviewed(c, m, evidence.get(m.target())));
                }
              },
              () -> nativeReceipt(manifest, false)));
    }
    actions.add(
        action(
            "control",
            plan.fingerprint(),
            () -> {
              requireRetiredWriters.run();
              requireNativeReceipts(false);
              control.adopt(plan);
            },
            () -> control.hasReceipt(plan)));
    for (var manifest : plan.manifests()) {
      actions.add(
          action(
              "activate/" + manifest.namespaceId(),
              manifest.fingerprint(),
              () -> {
                requireRetiredWriters.run();
                requireNativeReceipts(false);
                if (!control.hasReceipt(plan)) {
                  throw new IllegalStateException("Physical adoption control receipt is absent");
                }
                verifyModels(true);
                try (var connection = sources.get(manifest.target()).getConnection()) {
                  PhysicalOwnershipInstaller.activateAdopted(
                      connection,
                      manifest,
                      (c, m) -> inspector.verifyReviewed(c, m, evidence.get(m.target())));
                }
              },
              () -> nativeReceipt(manifest, true)));
    }
    return List.copyOf(actions);
  }

  /** Final maintenance-window verification; services may start only after it succeeds. */
  void verifyComplete() {
    requireRetiredWriters.run();
    requireNativeReceipts(true);
    if (!control.hasReceipt(plan)) {
      throw new IllegalStateException("Physical adoption control receipt is absent");
    }
    verifyModels(true);
  }

  private void verifyModels(boolean adopted) {
    var inventory = adopted ? plan.adoptedInventory() : plan.models().inventory();
    var expected =
        new PhysicalOwnershipModelReview(
            1, inventory, plan.models().definitions(), plan.models().publications());
    if (!reader.models(inventory).fingerprint().equals(expected.fingerprint())) {
      throw new IllegalStateException("Physical adoption model review changed");
    }
  }

  private void requireNativeReceipts(boolean activated) {
    for (var manifest : plan.manifests()) {
      if (!nativeReceipt(manifest, activated)) {
        throw new IllegalStateException("Physical adoption native receipt is absent");
      }
    }
  }

  private boolean nativeReceipt(PhysicalAdoptionManifest manifest, boolean activated) {
    try (var connection = sources.get(manifest.target()).getConnection()) {
      return activated
          ? PhysicalOwnershipInstaller.hasActivationReceipt(connection, manifest)
          : PhysicalOwnershipInstaller.hasAdoptionReceipt(connection, manifest);
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "Physical adoption receipt unavailable",
          new SQLException(
              "Native receipt read failed", failure.getSQLState(), failure.getErrorCode()));
    }
  }

  private InstallationAction action(
      String name,
      String fingerprint,
      CheckedCommand command,
      java.util.function.BooleanSupplier completed) {
    String id = "physical-adoption/" + name;
    var step =
        new InstallationPlan.Step(
            id,
            "physical-adoption",
            1,
            InstallationFingerprint.of(plan.fingerprint(), name, fingerprint),
            InstallationPlan.Retry.RECONCILE_ONLY);
    return new InstallationAction() {
      @Override
      public InstallationPlan.Step definition() {
        return step;
      }

      @Override
      public String apply() throws Exception {
        command.run();
        return completedReceipt()
            .orElseThrow(
                () -> new IllegalStateException("Physical adoption effect has no durable receipt"));
      }

      @Override
      public Optional<String> completedReceipt() {
        return completed.getAsBoolean()
            ? Optional.of(InstallationFingerprint.of(id, step.inputFingerprint(), "complete-v1"))
            : Optional.empty();
      }
    };
  }

  @FunctionalInterface
  private interface CheckedCommand {
    void run() throws Exception;
  }
}
