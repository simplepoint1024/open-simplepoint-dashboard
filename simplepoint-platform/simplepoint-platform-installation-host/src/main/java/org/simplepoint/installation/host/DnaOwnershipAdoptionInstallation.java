package org.simplepoint.installation.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.ownership.PhysicalAdoptionManifest;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationPlan;
import org.simplepoint.platform.installation.InstallationRunner;
import org.simplepoint.platform.installation.jdbc.InstallationJournalMigrations;
import org.simplepoint.platform.installation.jdbc.JdbcInstallationJournal;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipAdoptionPlan;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalOwnershipSchemaInspector;

/** Privileged local adoption CLI; immutable reviews survive partially committed operations. */
final class DnaOwnershipAdoptionInstallation {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final int MAX_BYTES = 48 * 1024 * 1024;
  private static final String PREFIX = "installation.ownership.adoption.";

  private DnaOwnershipAdoptionInstallation() {}

  static Map<String, Object> execute(
      String command,
      ModulePlan modules,
      ModuleStorageBindings bindings,
      Properties properties,
      Map<String, DataSource> sources,
      String artifact)
      throws Exception {
    return execute(command, modules, bindings, properties, sources, artifact, null);
  }

  // Package-private service probe substitution is only for native host tests.
  static Map<String, Object> execute(
      String command,
      ModulePlan modules,
      ModuleStorageBindings bindings,
      Properties properties,
      Map<String, DataSource> sources,
      String artifact,
      LocalServiceRetirement.Probe probe)
      throws Exception {
    if (!List.of("ownership-adoption-review", "ownership-adopt", "ownership-adoption-verify")
            .contains(command)
        || modules.modules().stream().noneMatch(m -> m.id().equals("dna-runtime"))) {
      throw new IllegalArgumentException("Physical adoption requires selected dna-runtime");
    }
    boolean preview = command.equals("ownership-adoption-review");
    String store = bindings.require("dna-runtime").store();
    var control = sources.get(store);
    if (control == null) {
      throw new IllegalArgumentException("Missing selected DNA control storage");
    }
    String storage = InstallationStorage.fingerprint(Map.of(store, control));
    var operation = InstallationOperation.stage(properties, "physical-ownership-adoption");
    String target = InstallationMain.required(properties, PREFIX + "target");
    long generation =
        Long.parseLong(InstallationMain.required(properties, PREFIX + "expected-generation"));
    Path file = Path.of(InstallationMain.required(properties, PREFIX + "review-file"));
    Review retained =
        preview ? null : JSON.readValue(InstallationFiles.read(file, MAX_BYTES), Review.class);
    var retirement =
        WriterRetirementReview.configured(
            properties, PREFIX, retained == null ? null : retained.retirement());
    final Review review;
    if (preview) {
      var schema =
          DnaOwnershipSchemaInstallation.load(
              false, modules, bindings, properties, sources, artifact);
      var targets =
          DnaOwnershipTargetInstallation.load(
              false, modules, bindings, properties, sources, artifact);
      var resolved =
          targets.targets().bindings().stream()
              .collect(Collectors.toMap(b -> b.bindingId(), b -> b));
      var legacyNamespaces =
          schema.models().inventory().bindings().stream()
              .filter(b -> b.namespaceId() == null)
              .map(b -> resolved.get(b.bindingId()).namespace())
              .distinct()
              .toList();
      String fingerprint = schema.fingerprint();
      var manifests =
          legacyNamespaces.stream()
              .map(
                  namespace ->
                      new PhysicalAdoptionManifest(
                          1,
                          namespace.target(),
                          namespace.namespaceId(),
                          operation,
                          fingerprint,
                          schema.tables().stream()
                              .filter(t -> t.namespace().equals(namespace.target()))
                              .map(
                                  t ->
                                      new PhysicalAdoptionManifest.Claim(
                                          t.table(), t.owner(), t.exists(), t.actualChecksum()))
                              .toList()))
              .toList();
      review =
          new Review(
              1,
              target,
              generation,
              artifact,
              storage,
              retirement,
              new PhysicalOwnershipAdoptionPlan(
                  operation, fingerprint, schema.models(), targets.targets(), manifests),
              schema.tables());
    } else {
      review = retained;
    }
    if (!review.target().equals(target)
        || review.generation() != generation
        || !review.artifact().equals(artifact)
        || !review.storage().equals(storage)
        || !review.retirement().equals(retirement)
        || !review.adoption().operation().equals(operation)
        || !review
            .adoption()
            .reviewFingerprint()
            .equals(
                InstallationMain.required(
                    properties, "installation.ownership.schema.review-fingerprint"))) {
      throw new IllegalArgumentException(
          "Physical adoption review differs from installation inputs");
    }
    var retired = retirement.verifier(probe);
    var coordinator =
        new DnaOwnershipAdoptionCoordinator(
            review.adoption(),
            control,
            DnaOwnershipTargetInstallation.sources(properties),
            review.tables(),
            DnaOwnershipSchemaInstallation.inspector(),
            retired);
    var actions =
        InstallationStorage.bind(
            coordinator.actions(), InstallationFingerprint.of(storage, retirement.fingerprint()));
    var plan =
        new InstallationPlan(
            operation,
            target,
            generation,
            artifact,
            actions.stream().map(a -> a.definition()).toList());
    if (preview) {
      byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(review);
      if (bytes.length > MAX_BYTES) {
        throw new IllegalArgumentException("Physical adoption review exceeds its bound");
      }
      InstallationFiles.create(file, bytes);
    } else {
      if (!plan.fingerprint()
          .equals(InstallationMain.required(properties, PREFIX + "review-fingerprint"))) {
        throw new IllegalArgumentException("Physical adoption plan fingerprint differs");
      }
      retired.run();
      if (command.equals("ownership-adopt")) {
        String journalStore = InstallationMain.required(properties, "installation.journal.store");
        var journal = sources.get(journalStore);
        if (journal == null) {
          throw new IllegalArgumentException("Missing physical adoption journal storage");
        }
        new ModuleMigrationEngine()
            .migrate(
                InstallationJournalMigrations.plan(journalStore), Map.of(journalStore, journal));
        if (!new InstallationRunner(new JdbcInstallationJournal(journal))
            .run(plan, actions)
            .complete()) {
          throw new IllegalStateException("Physical adoption installation did not complete");
        }
      }
      coordinator.verifyComplete();
    }
    return Map.of(
        "reviewFingerprint",
        plan.fingerprint(),
        "namespaces",
        review.adoption().manifests().size(),
        "bindings",
        review.adoption().models().inventory().bindings().size(),
        "legacyAdopted",
        !preview,
        "activated",
        !preview,
        "applied",
        command.equals("ownership-adopt"));
  }

  record Review(
      int version,
      String target,
      long generation,
      String artifact,
      String storage,
      WriterRetirementReview retirement,
      PhysicalOwnershipAdoptionPlan adoption,
      List<PhysicalOwnershipSchemaInspector.Table> tables) {
    Review {
      if (version != 1
          || target == null
          || generation < 0
          || artifact == null
          || storage == null
          || retirement == null
          || adoption == null
          || tables == null
          || tables.size() > 80000) {
        throw new IllegalArgumentException("Invalid physical adoption review");
      }
      InstallationFingerprint.require(artifact);
      InstallationFingerprint.require(storage);
      tables = List.copyOf(tables);
    }
  }
}
