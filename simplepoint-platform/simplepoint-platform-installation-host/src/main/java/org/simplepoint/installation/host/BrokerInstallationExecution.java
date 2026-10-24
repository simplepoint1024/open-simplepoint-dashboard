package org.simplepoint.installation.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.events.composition.ModuleEventPlan;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationPlan;
import org.simplepoint.platform.installation.InstallationRunner;
import org.simplepoint.platform.installation.jdbc.InstallationJournalMigrations;
import org.simplepoint.platform.installation.jdbc.JdbcInstallationJournal;

/** Reviewed broker transitions with per-effect journal entries and read-only recovery checks. */
final class BrokerInstallationExecution {
  private static final ObjectMapper JSON = new ObjectMapper();

  private BrokerInstallationExecution() {}

  static Map<String, Object> execute(boolean preview, ModuleEventPlan events, Properties properties,
      Map<String, DataSource> sources, String artifact) throws Exception {
    UUID operation = InstallationOperation.stage(properties, "broker");
    String target = InstallationMain.required(properties, "installation.broker.target");
    long generation = Long.parseLong(InstallationMain.required(properties, "installation.broker.expected-generation"));
    Path file = Path.of(InstallationMain.required(properties, "installation.broker.review-file"));
    var broker = new BrokerInstallation(properties);
    String storage = InstallationStorage.fingerprint(sources);
    var review = preview
        ? new Review(operation, target, generation, artifact, storage, broker.targetFingerprint(), broker.permissionSnapshot(events))
        : JSON.readValue(InstallationFiles.read(file, 1048576), Review.class);
    if (!review.operation().equals(operation) || !review.target().equals(target) || review.generation() != generation
        || !review.artifact().equals(artifact) || !review.storage().equals(storage)
        || !review.broker().equals(broker.targetFingerprint())) {
      throw new IllegalArgumentException("Broker review does not match this deployment, target or artifact");
    }
    var actions = InstallationStorage.bind(broker.prepare(events, review.permissions()), storage);
    var plan = new InstallationPlan(operation, target, generation, artifact,
        actions.stream().map(InstallationAction::definition).toList());
    if (preview) {
      InstallationFiles.create(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(review));
    } else {
      if (!plan.fingerprint().equals(InstallationMain.required(properties, "installation.broker.review-fingerprint"))) {
        throw new IllegalArgumentException("Broker installation differs from the reviewed fingerprint");
      }
      String journalStore = InstallationMain.required(properties, "installation.journal.store");
      DataSource source = sources.get(journalStore);
      if (source == null) {
        throw new IllegalArgumentException("Missing installation journal store");
      }
      new ModuleMigrationEngine().migrate(InstallationJournalMigrations.plan(journalStore), Map.of(journalStore, source));
      if (!new InstallationRunner(new JdbcInstallationJournal(source)).run(plan, actions).complete()) {
        throw new IllegalStateException("Broker installation is incomplete");
      }
      for (var action : actions) {
        if (action.completedReceipt().isEmpty()) {
          throw new IllegalStateException("Installed broker effect is no longer present");
        }
      }
    }
    return Map.of("reviewFingerprint", plan.fingerprint(), "steps", actions.size(), "applied", !preview,
        "eventOwners", events.channels().size(), "durableSubscriptions",
        events.channels().values().stream().mapToInt(channel -> channel.subscriptions().size()).sum());
  }

  record Review(UUID operation, String target, long generation, String artifact, String storage, String broker,
      Map<String, Map<String, String>> permissions) {
    Review {
      var copy = new java.util.TreeMap<String, Map<String, String>>();
      permissions.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
      permissions = Map.copyOf(copy);
    }
  }
}
