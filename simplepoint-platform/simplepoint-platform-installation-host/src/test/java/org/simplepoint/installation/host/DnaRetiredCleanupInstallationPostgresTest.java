package org.simplepoint.installation.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipInstallationAdapter;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalRetiredCleanupPlan;

/** Real private review, native namespace lock, stopped executor and durable domain receipt. */
class DnaRetiredCleanupInstallationPostgresTest {
  @TempDir Path directory;

  @Test
  void reviewedCleanupRequiresRetiredWritersAndPreservesExactOriginalIdentity() throws Exception {
    String image = System.getenv("PHYSICAL_OWNERSHIP_TEST_DOCKER_IMAGE");
    Assumptions.assumeTrue(image != null, "Explicit disposable Docker image required");
    String container = "sp-cleanup-test-" + UUID.randomUUID().toString().replace("-", "");
    docker(
        "create",
        "--name",
        container,
        "--network",
        "none",
        "--restart",
        "no",
        "--entrypoint",
        "/bin/sh",
        image,
        "-c",
        "sleep 300");
    try (var fixture = new DnaOwnershipInventoryInstallationPostgresTest.Fixture()) {
      fixture.record();
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_bindings SET deleted_at=now(),"
              + "catalog_name=current_database(),schema_name=?",
          fixture.schema);
      var original =
          new PhysicalOwnershipInstallationAdapter(fixture.source)
              .inventory()
              .bindings()
              .getFirst();
      byte[] evidence =
          "Reviewed original binding and cleanup manifest"
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      String checksum =
          java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(evidence));
      var binding =
          new PhysicalBindingInventory.Binding(
              original.bindingId(),
              original.modelId(),
              original.tenantId(),
              original.target(),
              null,
              original.revision(),
              original.deletedAt(),
              original.model(),
              checksum);
      fixture.jdbc.update("DELETE FROM simpoint_dna_physical_bindings");
      var properties = new Properties();
      properties.setProperty("installation.operation", UUID.randomUUID().toString());
      String prefix = "installation.ownership.cleanup.";
      properties.setProperty(prefix + "review-file", directory.resolve("review.json").toString());
      properties.setProperty(prefix + "plan-file", directory.resolve("plan.json").toString());
      properties.setProperty(
          prefix + "evidence-file", directory.resolve("evidence.json").toString());
      properties.setProperty(prefix + "retirement-kind", "docker");
      properties.setProperty(prefix + "retired-containers", container);
      properties.setProperty("installation.ownership.target-sources", "original");
      String source = "installation.ownership.target-sources.original.";
      properties.setProperty(source + "data-source-id", binding.target().dataSourceId());
      properties.setProperty(source + "catalog", binding.target().catalogName());
      properties.setProperty(source + "url", fixture.source.getUrl());
      properties.setProperty(source + "username", fixture.source.getUsername());
      properties.setProperty(source + "password", fixture.source.getPassword());
      var plan =
          new PhysicalRetiredCleanupPlan(
              InstallationOperation.stage(properties, "retired-cleanup"),
              checksum,
              new PhysicalBindingInventory(1, List.of(binding)));
      var json =
          new ObjectMapper()
              .registerModule(new JavaTimeModule())
              .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
      InstallationFiles.create(directory.resolve("plan.json"), json.writeValueAsBytes(plan));
      InstallationFiles.create(directory.resolve("evidence.json"), evidence);
      docker("start", container);
      var stores = Map.<String, javax.sql.DataSource>of(fixture.store, fixture.source);
      var review =
          DnaRetiredCleanupInstallation.execute(
              "ownership-cleanup-review",
              fixture.modules,
              fixture.bindings,
              properties,
              stores,
              "a".repeat(64));
      properties.setProperty(
          prefix + "review-fingerprint", review.get("reviewFingerprint").toString());
      assertThat(review).containsEntry("adopted", false).containsEntry("activated", false);
      assertThatThrownBy(
              () ->
                  DnaRetiredCleanupInstallation.execute(
                      "ownership-cleanup-adopt",
                      fixture.modules,
                      fixture.bindings,
                      properties,
                      stores,
                      "a".repeat(64)))
          .hasMessageContaining("not established");
      docker("stop", "--time", "1", container);
      fixture.jdbc.execute("CREATE VIEW " + binding.target().tableName() + " AS SELECT 1 AS id");
      assertThatThrownBy(
              () ->
                  DnaRetiredCleanupInstallation.execute(
                      "ownership-cleanup-adopt",
                      fixture.modules,
                      fixture.bindings,
                      properties,
                      stores,
                      "a".repeat(64)))
          .hasMessage("Retired cleanup native verification failed")
          .hasCauseInstanceOf(java.sql.SQLException.class);
      fixture.jdbc.execute("DROP VIEW " + binding.target().tableName());
      assertThat(
              DnaRetiredCleanupInstallation.execute(
                  "ownership-cleanup-adopt",
                  fixture.modules,
                  fixture.bindings,
                  properties,
                  stores,
                  "a".repeat(64)))
          .containsEntry("adopted", true)
          .containsEntry("activated", false);
      assertThat(
              DnaRetiredCleanupInstallation.execute(
                  "ownership-cleanup-verify",
                  fixture.modules,
                  fixture.bindings,
                  properties,
                  stores,
                  "a".repeat(64)))
          .containsEntry("adopted", true);
      assertThat(new PhysicalOwnershipInstallationAdapter(fixture.source).inventory())
          .isEqualTo(plan.inventory());
      assertThat(Files.readString(directory.resolve("review.json")))
          .doesNotContain("jdbc:", fixture.source.getPassword());
    } finally {
      docker("rm", "--force", container);
    }
  }

  private static void docker(String... args) throws Exception {
    var command = new java.util.ArrayList<>(List.of("docker"));
    command.addAll(List.of(args));
    var process =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("Disposable cleanup executor timed out");
    }
    assertThat(process.exitValue()).isZero();
  }
}
