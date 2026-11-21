package org.simplepoint.installation.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipInstallationAdapter;

/** Persists a domain-owned control inventory; no physical claims, activation or domain SQL here. */
final class DnaOwnershipInventoryInstallation {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final int MAX_BYTES = 32 * 1024 * 1024;

  private DnaOwnershipInventoryInstallation() {}

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
        "bindings",
        review.inventory().bindings().size(),
        "controlBindingsVerified",
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
    if (modules.modules().stream().noneMatch(module -> module.id().equals("dna-runtime"))) {
      throw new IllegalArgumentException(
          "Physical ownership inventory requires selected dna-runtime");
    }
    String store = bindings.require("dna-runtime").store();
    DataSource source = sources.get(store);
    if (source == null) {
      throw new IllegalArgumentException("Missing selected dna-runtime control store");
    }
    UUID operation = InstallationOperation.stage(properties, "physical-binding-inventory");
    Path path =
        Path.of(
            InstallationMain.required(properties, "installation.ownership.inventory.review-file"));
    String storage = InstallationStorage.fingerprint(Map.of(store, source));
    var owner = new PhysicalOwnershipInstallationAdapter(source);
    final Review review;
    if (preview) {
      review = new Review(1, operation, artifact, store, storage, owner.inventory());
      byte[] document = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(review);
      if (document.length > MAX_BYTES) {
        throw new IllegalArgumentException("Physical binding inventory review exceeds its bound");
      }
      InstallationFiles.create(path, document);
    } else {
      review = JSON.readValue(InstallationFiles.read(path, MAX_BYTES), Review.class);
      if (!operation.equals(review.operation())
          || !artifact.equals(review.artifact())
          || !store.equals(review.store())
          || !storage.equals(review.storage())
          || !review
              .fingerprint()
              .equals(
                  InstallationMain.required(
                      properties, "installation.ownership.inventory.review-fingerprint"))) {
        throw new IllegalArgumentException(
            "Physical binding inventory review differs from installation inputs");
      }
      if (!review.inventory().fingerprint().equals(owner.inventory().fingerprint())) {
        throw new IllegalStateException("Physical binding inventory changed after review");
      }
    }
    return review;
  }

  record Review(
      int version,
      UUID operation,
      String artifact,
      String store,
      String storage,
      PhysicalBindingInventory inventory) {
    Review {
      if (version != 1
          || operation == null
          || artifact == null
          || !artifact.matches("[0-9a-f]{64}")
          || store == null
          || store.isBlank()
          || storage == null
          || !storage.matches("[0-9a-f]{64}")
          || inventory == null) {
        throw new IllegalArgumentException("Invalid physical binding inventory review");
      }
    }

    String fingerprint() {
      return InstallationFingerprint.of(
          "physical-binding-inventory-review-v1",
          operation.toString(),
          artifact,
          store,
          storage,
          inventory.fingerprint());
    }
  }
}
