package org.simplepoint.installation.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.data.migration.ModuleSchemaAdoption;
import org.simplepoint.data.migration.PostgresSchemaContract;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationPlan;
import org.simplepoint.platform.installation.InstallationRunner;
import org.simplepoint.platform.installation.jdbc.InstallationJournalMigrations;
import org.simplepoint.platform.installation.jdbc.JdbcInstallationJournal;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit structural adoption against independently installed V1 references and reviewed evidence. */
final class SchemaAdoptionInstallation {
  private static final ObjectMapper JSON = new ObjectMapper();

  private SchemaAdoptionInstallation() {}

  static Map<String, Object> execute(boolean preview, ModuleMigrationPlan migrations,
      Map<String, DataSource> sources, Properties properties, String artifact) throws Exception {
    if (!migrations.undeclaredModules().isEmpty()) {
      throw new IllegalArgumentException("Legacy adoption requires complete module migration declarations");
    }
    UUID operation = InstallationOperation.stage(properties, "schema-adoption");
    String target = InstallationMain.required(properties, "installation.schema.target");
    long generation = Long.parseLong(InstallationMain.required(properties, "installation.schema.expected-generation"));
    Path reviewPath = Path.of(InstallationMain.required(properties, "installation.schema.review-file"));
    String storage = InstallationStorage.fingerprint(sources);
    final Review review;
    if (preview) {
      var baseline = new ModuleMigrationPlan(migrations.schemas().stream().map(schema ->
          new ModuleMigrationPlan.ModuleSchema(schema.module(), schema.store(), schema.historyTable(),
              schema.tables(), Map.of("postgresql", List.of(v1(schema))))).toList());
      var references = new TreeMap<String, DataSource>();
      for (var schema : baseline.schemas()) {
        String prefix = "installation.references." + schema.store() + ".";
        references.computeIfAbsent(schema.store(), ignored -> new DriverManagerDataSource(
            InstallationMain.required(properties, prefix + "url"),
            InstallationMain.required(properties, prefix + "username"),
            InstallationMain.required(properties, prefix + "password")));
      }
      new ModuleMigrationEngine().verify(baseline, references);
      String evidence = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
          InstallationFiles.read(Path.of(InstallationMain.required(properties,
              "installation.schema.evidence-file")), 4194304)));
      var approvals = new TreeMap<String, ModuleSchemaAdoption.Approval>();
      for (var schema : baseline.schemas()) {
        try (var reference = references.get(schema.store()).getConnection();
            var actual = sources.get(schema.store()).getConnection()) {
          reference.setReadOnly(true);
          actual.setReadOnly(true);
          try (var statement = reference.createStatement();
              var rows = statement.executeQuery("select count(*) from \"" + schema.historyTable()
                  + "\" where \"version\"='1' and \"type\"='SQL' and \"success\"=true")) {
            rows.next();
            if (rows.getInt(1) != 1) {
              throw new IllegalArgumentException("Reference must independently execute the exact V1 script");
            }
          }
          var expected = PostgresSchemaContract.capture(reference, schema.tables());
          if (!expected.equals(PostgresSchemaContract.capture(actual, schema.tables()))) {
            throw new IllegalStateException("SCHEMA_REVIEW_MISMATCH: " + schema.module());
          }
          approvals.put(schema.module(), new ModuleSchemaAdoption.Approval(operation,
              v1(schema).sha256(), expected.fingerprint(), evidence));
        }
      }
      review = new Review(operation, target, generation, artifact, storage, approvals);
    } else {
      review = JSON.readValue(InstallationFiles.read(reviewPath, 4194304), Review.class);
    }
    if (!review.operation().equals(operation) || !review.target().equals(target)
        || review.generation() != generation || !review.artifact().equals(artifact) || !review.storage().equals(storage)
        || !review.approvals().keySet().equals(migrations.schemas().stream()
            .map(ModuleMigrationPlan.ModuleSchema::module).collect(java.util.stream.Collectors.toSet()))) {
      throw new IllegalArgumentException("Schema review differs from the selected deployment or artifact");
    }
    var actions = new ArrayList<InstallationAction>();
    var adopter = new ModuleSchemaAdoption();
    for (var schema : migrations.schemas()) {
      var approval = review.approvals().get(schema.module());
      if (!operation.equals(approval.operation()) || !v1(schema).sha256().equals(approval.scriptFingerprint())) {
        throw new IllegalArgumentException("Schema approval operation or baseline differs");
      }
      String fingerprint = InstallationFingerprint.of(schema.module(), schema.store(), schema.historyTable(),
          operation.toString(), approval.scriptFingerprint(), approval.schemaFingerprint(), approval.evidenceFingerprint());
      var step = new InstallationPlan.Step("schema-adoption/" + schema.module(), "postgresql-v1-adoption", 1,
          fingerprint, InstallationPlan.Retry.DURABLE_IDEMPOTENT);
      actions.add(new InstallationAction() {
        @Override
        public InstallationPlan.Step definition() {
          return step;
        }

        @Override
        public String apply() {
          adopter.adopt(schema, sources.get(schema.store()), approval);
          return fingerprint;
        }

        @Override
        public Optional<String> completedReceipt() {
          return adopter.completed(schema, sources.get(schema.store()), approval)
              ? Optional.of(fingerprint) : Optional.empty();
        }
      });
    }
    var scoped = InstallationStorage.bind(actions, storage);
    var plan = new InstallationPlan(operation, target, generation, artifact,
        scoped.stream().map(InstallationAction::definition).toList());
    if (preview) {
      InstallationFiles.create(reviewPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(review));
    } else {
      if (!plan.fingerprint().equals(InstallationMain.required(properties, "installation.schema.review-fingerprint"))) {
        throw new IllegalArgumentException("Schema plan differs from the reviewed fingerprint");
      }
      String journalStore = InstallationMain.required(properties, "installation.journal.store");
      DataSource journal = sources.get(journalStore);
      if (journal == null) {
        throw new IllegalArgumentException("Missing installation journal store");
      }
      var engine = new ModuleMigrationEngine();
      for (String store : migrations.schemas().stream().map(ModuleMigrationPlan.ModuleSchema::store).distinct().toList()) {
        engine.migrate(ModuleSchemaAdoption.receiptMigrations(store), Map.of(store, sources.get(store)));
      }
      engine.migrate(InstallationJournalMigrations.plan(journalStore), Map.of(journalStore, journal));
      var result = new InstallationRunner(new JdbcInstallationJournal(journal)).run(plan, scoped);
      if (!result.complete()) {
        throw new IllegalStateException("Schema adoption is incomplete");
      }
    }
    return Map.of("reviewFingerprint", plan.fingerprint(), "adoptedModules", actions.size(), "applied", !preview);
  }

  private static ModuleMigrationPlan.Script v1(ModuleMigrationPlan.ModuleSchema schema) {
    var scripts = schema.scripts().getOrDefault("postgresql", List.of()).stream()
        .filter(script -> script.name().startsWith("V1__")).toList();
    if (scripts.size() != 1) {
      throw new IllegalArgumentException("Schema adoption requires exactly one PostgreSQL V1");
    }
    return scripts.getFirst();
  }

  record Review(UUID operation, String target, long generation, String artifact, String storage,
      Map<String, ModuleSchemaAdoption.Approval> approvals) {
    Review {
      approvals = Map.copyOf(approvals);
    }
  }
}
