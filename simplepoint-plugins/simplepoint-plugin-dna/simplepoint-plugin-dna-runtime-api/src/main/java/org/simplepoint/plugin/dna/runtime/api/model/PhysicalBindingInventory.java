package org.simplepoint.plugin.dna.runtime.api.model;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete, non-secret control binding snapshot for offline ownership review, not activation. */
public record PhysicalBindingInventory(int contractVersion, List<Binding> bindings) {
  /** Complete inventories above this bound fail rather than truncating retained ownership. */
  public static final int MAX_BINDINGS = 10000;

  /** Bounds and canonicalizes snapshots, rejecting duplicate identities and declared targets. */
  public PhysicalBindingInventory {
    if (contractVersion != 1 || bindings == null || bindings.size() > MAX_BINDINGS) {
      throw new IllegalArgumentException("Invalid physical binding inventory");
    }
    var identities = new HashSet<String>();
    var models = new HashSet<String>();
    var targets = new HashSet<List<String>>();
    for (var binding : bindings) {
      Objects.requireNonNull(binding, "binding");
      if (!identities.add(binding.bindingId())
          || !models.add(binding.modelId())
          || !targets.add(
              List.of(
                  binding.target().dataSourceId(),
                  binding.target().catalogName(),
                  binding.target().schemaName(),
                  binding.target().tableName()))) {
        throw new IllegalArgumentException("Ambiguous physical binding inventory");
      }
    }
    bindings = bindings.stream().sorted(Comparator.comparing(Binding::bindingId)).toList();
  }

  /**
   * Hashes exact nullable values and lifecycle revisions; display labels and credentials are
   * absent.
   */
  public String fingerprint() {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update("dna-physical-binding-inventory-v1".getBytes(StandardCharsets.UTF_8));
      for (var binding : bindings) {
        var values = new ArrayList<String>();
        values.add(binding.bindingId());
        values.add(binding.modelId());
        values.add(binding.tenantId());
        values.add(binding.target().dataSourceId());
        values.add(binding.target().catalogName());
        values.add(binding.target().schemaName());
        values.add(binding.target().tableName());
        values.add(binding.target().isolationMode().name());
        values.add(binding.namespaceId());
        values.add(Long.toString(binding.revision()));
        values.add(instant(binding.deletedAt()));
        values.add(binding.model().appId());
        values.add(binding.model().formId());
        values.add(binding.model().status().name());
        values.add(Integer.toString(binding.model().latestVersion()));
        values.add(
            binding.model().activeVersion() == null
                ? null
                : binding.model().activeVersion().toString());
        values.add(Long.toString(binding.model().revision()));
        values.add(instant(binding.model().deletedAt()));
        if (binding.cleanupEvidence() != null) {
          values.add(binding.cleanupEvidence());
        }
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
          for (String value : values) {
            byte[] encoded = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
            output.writeInt(encoded == null ? -1 : encoded.length);
            if (encoded != null) {
              output.write(encoded);
            }
          }
        }
        digest.update(bytes.toByteArray());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException | IOException failure) {
      throw new IllegalStateException(
          "Physical binding inventory could not be fingerprinted", failure);
    }
  }

  /** Raw persisted target names; no trimming, default namespace substitution or alias merging. */
  public record Target(
      String dataSourceId,
      String catalogName,
      String schemaName,
      String tableName,
      PhysicalIsolationMode isolationMode) {
    /** Keeps raw storage names intact, including the historical empty namespace sentinel. */
    public Target {
      required(dataSourceId, 64);
      optionalNamespace(catalogName);
      optionalNamespace(schemaName);
      required(tableName, 128);
      Objects.requireNonNull(isolationMode, "isolationMode");
    }
  }

  /** Includes retired/deleted models because their physical ownership must remain reserved. */
  public record Model(
      String appId,
      String formId,
      PhysicalModelStatus status,
      int latestVersion,
      Integer activeVersion,
      long revision,
      Instant deletedAt) {
    /** Validates persisted version counters without discarding transitional lifecycle states. */
    public Model {
      required(appId, 64);
      required(formId, 64);
      Objects.requireNonNull(status, "status");
      if (revision < 0
          || latestVersion < 1
          || activeVersion != null && (activeVersion < 1 || activeVersion > latestVersion)) {
        throw new IllegalArgumentException("Invalid physical model inventory state");
      }
    }
  }

  /**
   * Null namespace identity is an unadopted legacy binding, never permission to claim its table.
   */
  public record Binding(
      String bindingId,
      String modelId,
      String tenantId,
      Target target,
      String namespaceId,
      long revision,
      Instant deletedAt,
      Model model,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String cleanupEvidence) {
    /** Ordinary bindings retain their original contract and fingerprint. */
    public Binding(
        String bindingId,
        String modelId,
        String tenantId,
        Target target,
        String namespaceId,
        long revision,
        Instant deletedAt,
        Model model) {
      this(bindingId, modelId, tenantId, target, namespaceId, revision, deletedAt, model, null);
    }

    /** Requires stable identities; legacy namespace absence remains distinguishable. */
    public Binding {
      required(bindingId, 36);
      required(modelId, 36);
      required(tenantId, 255);
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(model, "model");
      if (revision < 0
          || namespaceId != null && !UUID.fromString(namespaceId).toString().equals(namespaceId)
          || cleanupEvidence != null
              && (!cleanupEvidence.matches("[0-9a-f]{64}")
                  || model.status() != PhysicalModelStatus.RETIRED
                  || model.deletedAt() == null
                  || deletedAt == null)) {
        throw new IllegalArgumentException("Invalid physical binding inventory state");
      }
    }
  }

  private static void required(String value, int length) {
    if (value == null || value.isBlank() || value.length() > length || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Invalid physical binding inventory value");
    }
  }

  private static void optionalNamespace(String value) {
    if (value == null || value.length() > 128 || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Invalid persisted physical namespace name");
    }
  }

  private static String instant(Instant value) {
    return value == null ? null : value.toString();
  }
}
