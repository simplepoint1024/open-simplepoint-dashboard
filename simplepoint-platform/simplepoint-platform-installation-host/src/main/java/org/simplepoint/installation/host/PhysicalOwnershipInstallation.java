package org.simplepoint.installation.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.platform.installation.InstallationRunner;
import org.simplepoint.platform.installation.jdbc.InstallationJournalMigrations;
import org.simplepoint.platform.installation.jdbc.JdbcInstallationJournal;
import org.simplepoint.platform.installation.ownership.PhysicalNamespaceInstallation;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Private offline physical-directory plans, separate from legacy adoption and activation. */
final class PhysicalOwnershipInstallation {
  private static final ObjectMapper JSON = new ObjectMapper();

  private PhysicalOwnershipInstallation() {}

  static Map<String, Object> execute(
      String command,
      Map<String, DataSource> controlSources,
      Properties properties,
      String artifact)
      throws Exception {
    if (!List.of("ownership-review", "ownership-install", "ownership-verify").contains(command)) {
      throw new IllegalArgumentException("Unknown physical ownership installation command");
    }
    UUID operation = InstallationOperation.stage(properties, "physical-ownership");
    String target = InstallationMain.required(properties, "installation.ownership.target");
    long generation =
        Long.parseLong(
            InstallationMain.required(properties, "installation.ownership.expected-generation"));
    Path reviewPath =
        Path.of(InstallationMain.required(properties, "installation.ownership.review-file"));
    String controlStorage = InstallationStorage.fingerprint(controlSources);
    var keys = keys(properties);
    var sources = new TreeMap<String, DataSource>();
    for (String key : keys) {
      String prefix = prefix(key);
      sources.put(
          key,
          new DriverManagerDataSource(
              InstallationMain.required(properties, prefix + "url"),
              InstallationMain.required(properties, prefix + "username"),
              InstallationMain.required(properties, prefix + "password")));
    }
    final Review review;
    if ("ownership-review".equals(command)) {
      var targets = new ArrayList<PhysicalNamespaceInstallation.Target>();
      for (String key : keys) {
        targets.add(
            PhysicalNamespaceInstallation.review(
                key,
                sources.get(key),
                InstallationMain.required(properties, prefix(key) + "namespace"),
                expectedIdentity(properties, operation, key)));
      }
      review = new Review(1, operation, target, generation, artifact, controlStorage, targets);
    } else {
      review = JSON.readValue(InstallationFiles.read(reviewPath, 1048576), Review.class);
    }
    if (!operation.equals(review.operation())
        || !target.equals(review.target())
        || generation != review.generation()
        || !artifact.equals(review.artifact())
        || !controlStorage.equals(review.controlStorage())
        || !keys.equals(
            review.namespaces().stream().map(PhysicalNamespaceInstallation.Target::key).toList())) {
      throw new IllegalArgumentException(
          "Physical ownership review differs from deployment inputs");
    }
    for (var namespace : review.namespaces()) {
      if (!namespace
              .descriptor()
              .namespace()
              .equals(InstallationMain.required(properties, prefix(namespace.key()) + "namespace"))
          || !namespace
              .namespaceId()
              .equals(expectedIdentity(properties, operation, namespace.key()))) {
        throw new IllegalArgumentException("Physical ownership review namespace binding differs");
      }
    }
    var prepared =
        PhysicalNamespaceInstallation.prepare(
            operation, target, generation, artifact, review.namespaces(), sources);
    var scoped = InstallationStorage.bind(prepared.actions(), controlStorage);
    var plan =
        new org.simplepoint.platform.installation.InstallationPlan(
            operation,
            target,
            generation,
            artifact,
            scoped.stream().map(action -> action.definition()).toList());
    if ("ownership-review".equals(command)) {
      // Exclusive, forced owner-only publication precedes every target DDL operation.
      InstallationFiles.create(
          reviewPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(review));
    } else {
      if (!plan.fingerprint()
          .equals(
              InstallationMain.required(properties, "installation.ownership.review-fingerprint"))) {
        throw new IllegalArgumentException(
            "Physical ownership plan differs from reviewed fingerprint");
      }
      if ("ownership-install".equals(command)) {
        String journalStore = InstallationMain.required(properties, "installation.journal.store");
        DataSource journal = controlSources.get(journalStore);
        if (journal == null) {
          throw new IllegalArgumentException(
              "Missing physical ownership installation journal store");
        }
        new ModuleMigrationEngine()
            .migrate(
                InstallationJournalMigrations.plan(journalStore), Map.of(journalStore, journal));
        var result = new InstallationRunner(new JdbcInstallationJournal(journal)).run(plan, scoped);
        if (!result.complete()) {
          throw new IllegalStateException("Physical ownership installation did not complete");
        }
      }
      prepared.verifyComplete();
    }
    return Map.of(
        "reviewFingerprint",
        plan.fingerprint(),
        "namespaces",
        keys.size(),
        "applied",
        "ownership-install".equals(command),
        "verified",
        !"ownership-review".equals(command),
        "legacyAdopted",
        false,
        "activated",
        false);
  }

  private static List<String> keys(Properties properties) {
    String raw = InstallationMain.required(properties, "installation.ownership.namespaces");
    if (raw.length() > 32768) {
      throw new IllegalArgumentException("Too many physical namespace installation bindings");
    }
    var keys = Arrays.stream(raw.split(",", -1)).map(String::trim).sorted().toList();
    if (keys.isEmpty()
        || keys.size() > 512
        || keys.stream().distinct().count() != keys.size()
        || keys.stream().anyMatch(key -> !key.matches("[a-z][a-z0-9-]{0,63}"))) {
      throw new IllegalArgumentException("Invalid physical namespace installation bindings");
    }
    return keys;
  }

  private static UUID expectedIdentity(Properties properties, UUID operation, String key) {
    String declared = properties.getProperty(prefix(key) + "namespace-id");
    return declared == null
        ? UUID.nameUUIDFromBytes((operation + ":namespace:" + key).getBytes(StandardCharsets.UTF_8))
        : UUID.fromString(declared);
  }

  private static String prefix(String key) {
    return "installation.ownership.namespaces." + key + ".";
  }

  record Review(
      int version,
      UUID operation,
      String target,
      long generation,
      String artifact,
      String controlStorage,
      List<PhysicalNamespaceInstallation.Target> namespaces) {
    Review {
      if (version != 1) {
        throw new IllegalArgumentException("Unsupported physical ownership review version");
      }
      namespaces = List.copyOf(namespaces);
    }
  }
}
