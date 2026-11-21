package org.simplepoint.installation.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipInstallationAdapter;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipModelReview;
import org.simplepoint.plugin.dna.runtime.service.schema.MysqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalOwnershipSchemaInspector;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PostgresqlRuntimeSchemaDialect;

/** Offline model/physical evidence, using the same executable dialects as DNA publication. */
final class DnaOwnershipSchemaInstallation {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final int MAX_BYTES = 32 * 1024 * 1024;

  private DnaOwnershipSchemaInstallation() {}

  static Map<String, Object> execute(
      boolean preview,
      ModulePlan modules,
      ModuleStorageBindings bindings,
      Properties properties,
      Map<String, DataSource> sources,
      String artifact)
      throws Exception {
    var review = load(preview, modules, bindings, properties, sources, artifact);
    return Map.of(
        "reviewFingerprint",
        review.fingerprint(),
        "models",
        review.models().inventory().bindings().size(),
        "tables",
        review.tables().size(),
        "schemaVerified",
        !preview,
        "legacyAdopted",
        false,
        "activated",
        false);
  }

  static Review load(
      boolean preview,
      ModulePlan modules,
      ModuleStorageBindings bindings,
      Properties properties,
      Map<String, DataSource> sources,
      String artifact)
      throws Exception {
    var targets =
        DnaOwnershipTargetInstallation.load(
            false, modules, bindings, properties, sources, artifact);
    var control =
        DnaOwnershipInventoryInstallation.load(
            false, modules, bindings, properties, sources, artifact);
    var owner =
        new PhysicalOwnershipInstallationAdapter(
            sources.get(bindings.require("dna-runtime").store()));
    UUID operation = InstallationOperation.stage(properties, "physical-ownership-schema");
    Path path =
        Path.of(InstallationMain.required(properties, "installation.ownership.schema.review-file"));
    Review expected = null;
    if (!preview) {
      expected = JSON.readValue(InstallationFiles.read(path, MAX_BYTES), Review.class);
      if (!operation.equals(expected.operation())
          || !targets.fingerprint().equals(expected.targetReviewFingerprint())
          || !expected
              .fingerprint()
              .equals(
                  InstallationMain.required(
                      properties, "installation.ownership.schema.review-fingerprint"))) {
        throw new IllegalArgumentException(
            "Physical schema review differs from installation inputs");
      }
    }
    var models = owner.models(control.inventory());
    var tables =
        inspector()
            .inspect(models, targets.targets(), DnaOwnershipTargetInstallation.sources(properties));
    if (!models.fingerprint().equals(owner.models(control.inventory()).fingerprint())) {
      throw new IllegalStateException("Physical model history changed during schema review");
    }
    var actual = new Review(1, operation, targets.fingerprint(), models, tables);
    if (preview) {
      byte[] document = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(actual);
      if (document.length > MAX_BYTES) {
        throw new IllegalArgumentException("Physical schema review exceeds its bound");
      }
      InstallationFiles.create(path, document);
    } else if (!actual.fingerprint().equals(expected.fingerprint())) {
      throw new IllegalStateException("Physical schema evidence changed after review");
    }
    return actual;
  }

  static PhysicalOwnershipSchemaInspector inspector() {
    var codec = new PhysicalSchemaSnapshotCodec(JSON);
    // Reuse the executable domain dialects and authoritative core contributions.
    return new PhysicalOwnershipSchemaInspector(
        List.of(new PostgresqlRuntimeSchemaDialect(codec), new MysqlRuntimeSchemaDialect(codec)),
        ServiceLoader.load(JdbcDatabaseDialect.class).stream()
            .map(ServiceLoader.Provider::get)
            .toList());
  }

  record Review(
      int version,
      UUID operation,
      String targetReviewFingerprint,
      PhysicalOwnershipModelReview models,
      List<PhysicalOwnershipSchemaInspector.Table> tables) {
    Review {
      if (version != 1
          || operation == null
          || targetReviewFingerprint == null
          || !targetReviewFingerprint.matches("[0-9a-f]{64}")
          || models == null
          || tables == null
          || tables.size() > 80000) {
        throw new IllegalArgumentException("Invalid physical schema review");
      }
      tables = List.copyOf(tables);
    }

    String fingerprint() throws com.fasterxml.jackson.core.JsonProcessingException {
      return InstallationFingerprint.of(
          "physical-schema-review-v1",
          operation.toString(),
          targetReviewFingerprint,
          models.fingerprint(),
          JSON.writeValueAsString(tables));
    }
  }
}
