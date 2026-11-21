package org.simplepoint.installation.host;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.simplepoint.data.ownership.PhysicalNamespaceDescriptor;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalBindingTargetResolver;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalBindingTargetReview;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalRetiredCleanupAdopter;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalRetiredCleanupPlan;

/** Explicit legacy cleanup adoption before the ordinary complete ownership inventory. */
final class DnaRetiredCleanupInstallation {
  private static final String PREFIX = "installation.ownership.cleanup.";
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private DnaRetiredCleanupInstallation() {}

  static Map<String, Object> execute(
      String command,
      ModulePlan modules,
      ModuleStorageBindings bindings,
      Properties properties,
      Map<String, DataSource> stores,
      String artifact)
      throws Exception {
    if (modules.modules().stream().noneMatch(module -> module.id().equals("dna-runtime"))) {
      throw new IllegalArgumentException("Retired cleanup requires selected dna-runtime");
    }
    var store = stores.get(bindings.require("dna-runtime").store());
    var adopter = new PhysicalRetiredCleanupAdopter(store);
    Path file = Path.of(InstallationMain.required(properties, PREFIX + "review-file"));
    var operation = InstallationOperation.stage(properties, "retired-cleanup");
    String storage = InstallationStorage.fingerprint(Map.of("dna-runtime", store));
    boolean preview = command.equals("ownership-cleanup-review");
    Review review;
    if (preview) {
      var plan =
          JSON.readValue(
              InstallationFiles.read(
                  Path.of(InstallationMain.required(properties, PREFIX + "plan-file")),
                  16 * 1024 * 1024),
              PhysicalRetiredCleanupPlan.class);
      if (!operation.equals(plan.operation())) {
        throw new IllegalArgumentException("Cleanup operation differs from installation");
      }
      byte[] evidence =
          InstallationFiles.read(
              Path.of(InstallationMain.required(properties, PREFIX + "evidence-file")),
              32 * 1024 * 1024);
      if (!plan.evidenceChecksum()
          .equals(
              java.util.HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256").digest(evidence)))) {
        throw new IllegalArgumentException("Cleanup source evidence differs");
      }
      adopter.review(plan);
      review =
          new Review(
              artifact,
              storage,
              plan,
              PhysicalBindingTargetResolver.review(
                  plan.inventory(), DnaOwnershipTargetInstallation.sources(properties)),
              WriterRetirementReview.configured(properties, PREFIX, null));
    } else {
      review = JSON.readValue(InstallationFiles.read(file, 32 * 1024 * 1024), Review.class);
      if (!review.artifact().equals(artifact)
          || !review.storage().equals(storage)
          || !review.plan().operation().equals(operation)
          || !review
              .fingerprint()
              .equals(InstallationMain.required(properties, PREFIX + "review-fingerprint"))) {
        throw new IllegalArgumentException("Cleanup review differs from installation");
      }
      WriterRetirementReview.configured(properties, PREFIX, review.retirement());
      review.retirement().verifier(null).run();
    }
    var sources = DnaOwnershipTargetInstallation.sources(properties);
    var targets = PhysicalBindingTargetResolver.review(review.plan().inventory(), sources);
    if (!review.targets().fingerprint().equals(targets.fingerprint())) {
      throw new IllegalStateException("Cleanup physical target changed");
    }
    var frozen = review;
    var groups = groups(review, sources);
    if (groups.size() > 255) {
      throw new IllegalArgumentException("Cleanup exceeds the installation namespace bound");
    }
    absent(
        groups,
        0,
        () -> {
          if (command.equals("ownership-cleanup-adopt")) {
            frozen.retirement().verifier(null).run();
            adopter.adopt(frozen.plan());
          } else {
            adopter.review(frozen.plan());
          }
        });
    if (preview) {
      InstallationFiles.create(
          file, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(review));
    }
    boolean complete = adopter.hasReceipt(review.plan());
    if (command.equals("ownership-cleanup-verify") && !complete) {
      throw new IllegalStateException("Cleanup adoption receipt is absent");
    }
    return Map.of(
        "reviewFingerprint",
        review.fingerprint(),
        "bindings",
        review.plan().inventory().bindings().size(),
        "adopted",
        complete,
        "activated",
        false);
  }

  private static List<Group> groups(
      Review review, Map<PhysicalBindingTargetResolver.Source, DataSource> sources) {
    var original =
        review.plan().inventory().bindings().stream()
            .collect(java.util.stream.Collectors.toMap(b -> b.bindingId(), b -> b));
    return review.targets().bindings().stream()
        .collect(java.util.stream.Collectors.groupingBy(b -> b.namespace().target()))
        .entrySet()
        .stream()
        .sorted(java.util.Comparator.comparing(entry -> entry.getKey().toString()))
        .map(
            entry -> {
              var binding = original.get(entry.getValue().getFirst().bindingId());
              return new Group(
                  entry.getKey(),
                  sources.get(
                      new PhysicalBindingTargetResolver.Source(
                          binding.target().dataSourceId(), binding.target().catalogName())),
                  entry.getValue().stream()
                      .map(PhysicalBindingTargetReview.Binding::table)
                      .sorted()
                      .toList());
            })
        .toList();
  }

  private static void absent(List<Group> groups, int index, Runnable action) {
    if (index == groups.size()) {
      action.run();
      return;
    }
    var group = groups.get(index);
    try (var connection = group.source().getConnection()) {
      PhysicalOwnershipInstaller.withAbsentReviewed(
          connection, group.target(), group.tables(), () -> absent(groups, index + 1, action));
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(
          "Retired cleanup native verification failed",
          new java.sql.SQLException(
              "Native absence verification failed", failure.getSQLState(), failure.getErrorCode()));
    }
  }

  private record Group(
      PhysicalNamespaceDescriptor target, DataSource source, List<String> tables) {}

  record Review(
      String artifact,
      String storage,
      PhysicalRetiredCleanupPlan plan,
      PhysicalBindingTargetReview targets,
      WriterRetirementReview retirement) {
    String fingerprint() {
      return InstallationFingerprint.of(
          "retired-cleanup-review-v1",
          artifact,
          storage,
          plan.fingerprint(),
          targets.fingerprint(),
          retirement.fingerprint());
    }
  }
}
