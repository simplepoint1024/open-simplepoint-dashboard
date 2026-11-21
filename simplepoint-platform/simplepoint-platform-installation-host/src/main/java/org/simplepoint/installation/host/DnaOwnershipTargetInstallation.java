package org.simplepoint.installation.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalBindingTargetResolver;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalBindingTargetReview;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Private native target review linked to an already reviewed complete domain inventory. */
final class DnaOwnershipTargetInstallation {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_BYTES = 32 * 1024 * 1024;

  private DnaOwnershipTargetInstallation() {}

  static Map<String, Object> execute(
      boolean preview,
      ModulePlan modules,
      ModuleStorageBindings bindings,
      Properties properties,
      Map<String, DataSource> controlSources,
      String artifact)
      throws Exception {
    var review = load(preview, modules, bindings, properties, controlSources, artifact);
    return Map.of(
        "reviewFingerprint",
        review.fingerprint(),
        "bindings",
        review.targets().bindings().size(),
        "nativeTargetsVerified",
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
      Map<String, DataSource> controlSources,
      String artifact)
      throws Exception {
    var control =
        DnaOwnershipInventoryInstallation.load(
            false, modules, bindings, properties, controlSources, artifact);
    UUID operation = InstallationOperation.stage(properties, "physical-binding-targets");
    Path path =
        Path.of(
            InstallationMain.required(properties, "installation.ownership.targets.review-file"));
    final Review review;
    if (preview) {
      review =
          new Review(
              1,
              operation,
              control.fingerprint(),
              PhysicalBindingTargetResolver.review(control.inventory(), sources(properties)));
      // A target read must not disguise control changes that happened during that remote work.
      DnaOwnershipInventoryInstallation.load(
          false, modules, bindings, properties, controlSources, artifact);
      byte[] document = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(review);
      if (document.length > MAX_BYTES) {
        throw new IllegalArgumentException("Physical target review exceeds its bound");
      }
      InstallationFiles.create(path, document);
    } else {
      review = JSON.readValue(InstallationFiles.read(path, MAX_BYTES), Review.class);
      if (!operation.equals(review.operation())
          || !control.fingerprint().equals(review.controlReviewFingerprint())
          || !control.inventory().fingerprint().equals(review.targets().controlFingerprint())
          || !review
              .fingerprint()
              .equals(
                  InstallationMain.required(
                      properties, "installation.ownership.targets.review-fingerprint"))) {
        throw new IllegalArgumentException(
            "Physical target review differs from installation inputs");
      }
      var current = PhysicalBindingTargetResolver.review(control.inventory(), sources(properties));
      if (!current.fingerprint().equals(review.targets().fingerprint())) {
        throw new IllegalStateException("Physical binding targets changed after review");
      }
      DnaOwnershipInventoryInstallation.load(
          false, modules, bindings, properties, controlSources, artifact);
    }
    return review;
  }

  static Map<PhysicalBindingTargetResolver.Source, DataSource> sources(Properties properties) {
    String raw = properties.getProperty("installation.ownership.target-sources");
    if (raw == null || raw.length() > 32768) {
      throw new IllegalArgumentException("Missing or oversized physical target source bindings");
    }
    var keys =
        raw.isEmpty()
            ? java.util.List.<String>of()
            : Arrays.stream(raw.split(",", -1)).map(String::trim).sorted().toList();
    if (keys.size() > 512
        || keys.stream().distinct().count() != keys.size()
        || keys.stream().anyMatch(key -> !key.matches("[a-z][a-z0-9-]{0,63}"))) {
      throw new IllegalArgumentException("Invalid physical target source bindings");
    }
    var result = new HashMap<PhysicalBindingTargetResolver.Source, DataSource>();
    for (String key : keys) {
      String prefix = "installation.ownership.target-sources." + key + ".";
      var source =
          new PhysicalBindingTargetResolver.Source(
              InstallationMain.required(properties, prefix + "data-source-id"),
              properties.getProperty(prefix + "catalog"));
      var dataSource =
          new DriverManagerDataSource(
              InstallationMain.required(properties, prefix + "url"),
              InstallationMain.required(properties, prefix + "username"),
              InstallationMain.required(properties, prefix + "password"));
      if (result.putIfAbsent(source, dataSource) != null) {
        throw new IllegalArgumentException("Duplicate physical target source binding");
      }
    }
    return Map.copyOf(result);
  }

  record Review(
      int version,
      UUID operation,
      String controlReviewFingerprint,
      PhysicalBindingTargetReview targets) {
    Review {
      if (version != 1
          || operation == null
          || controlReviewFingerprint == null
          || !controlReviewFingerprint.matches("[0-9a-f]{64}")
          || targets == null) {
        throw new IllegalArgumentException("Invalid physical target review");
      }
    }

    String fingerprint() {
      return InstallationFingerprint.of(
          "physical-binding-target-review-v1",
          operation.toString(),
          controlReviewFingerprint,
          targets.fingerprint());
    }
  }
}
